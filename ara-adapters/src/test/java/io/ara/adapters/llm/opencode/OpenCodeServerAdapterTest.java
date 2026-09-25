package io.ara.adapters.llm.opencode;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.ara.core.llm.LlmCallContext;
import io.ara.core.llm.LlmCompletion;
import io.ara.core.llm.LlmException;
import io.ara.core.llm.LlmMessage;
import io.ara.core.media.MediaRef;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

/**
 * What {@link OpenCodeServerAdapter} sends to {@code opencode serve} and how it turns the reply
 * back into an {@link LlmCompletion}, against a fake server that speaks just the four
 * endpoints the adapter uses. Launch mode is exercised with a shell script standing in for the
 * {@code opencode} binary.
 */
class OpenCodeServerAdapterTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final LlmCallContext CTX = new LlmCallContext.Builder().agentType("test").build();

    private HttpServer server;
    private final List<String> requests = new CopyOnWriteArrayList<>();      // "METHOD path"
    private final List<JsonNode> promptBodies = new CopyOnWriteArrayList<>();
    private volatile String authHeader;
    private volatile String replyJson;

    @BeforeEach
    void startFakeOpenCode() throws IOException {
        replyJson = reply("Hello", null);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
    }

    @AfterEach
    void stopFakeOpenCode() {
        server.stop(0);
    }

    private void handle(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        requests.add(ex.getRequestMethod() + " " + path);
        authHeader = ex.getRequestHeaders().getFirst("Authorization");
        String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        String out;
        if (path.equals("/experimental/tool/ids")) out = "[\"invalid\",\"bash\",\"read\",\"edit\"]";
        else if (path.equals("/session") && ex.getRequestMethod().equals("POST")) out = "{\"id\":\"ses_1\"}";
        else if (path.endsWith("/message")) { promptBodies.add(JSON.readTree(body)); out = replyJson; }
        else out = "true";
        byte[] bytes = out.getBytes(StandardCharsets.UTF_8);
        ex.sendResponseHeaders(200, bytes.length);
        ex.getResponseBody().write(bytes);
        ex.close();
    }

    private static String reply(String text, String errorJson) {
        return "{\"info\":{\"finish\":\"stop\",\"tokens\":{\"input\":11,\"output\":3}"
                + (errorJson == null ? "" : ",\"error\":" + errorJson) + "},"
                + "\"parts\":[{\"type\":\"step-start\"},{\"type\":\"reasoning\",\"text\":\"hmm\"},"
                + "{\"type\":\"text\",\"text\":\"" + text + "\"}]}";
    }

    private OpenCodeServerAdapter.Builder connected() {
        return OpenCodeServerAdapter.builder().baseUrl("http://127.0.0.1:" + server.getAddress().getPort());
    }

    @Test
    void complete_returns_text_parts_and_token_usage() {
        LlmCompletion c = connected().build().complete(List.of(LlmMessage.user("hi")), CTX);

        assertEquals("Hello", c.text());            // the reasoning part must not leak into the answer
        assertEquals(11, c.promptTokens());
        assertEquals(3, c.outputTokens());
        assertEquals("stop", c.finishReason());
    }

    @Test
    void each_call_uses_and_deletes_its_own_session() {
        connected().build().complete(List.of(LlmMessage.user("hi")), CTX);

        assertEquals(List.of("GET /experimental/tool/ids", "POST /session",
                "POST /session/ses_1/message", "DELETE /session/ses_1"), requests);
    }

    @Test
    void system_goes_to_system_field_and_lone_user_message_is_sent_verbatim() {
        connected().build().complete(List.of(LlmMessage.system("be brief"), LlmMessage.user("hi")), CTX);

        JsonNode body = promptBodies.get(0);
        assertEquals("be brief", body.path("system").asText());
        assertEquals("hi", body.path("parts").get(0).path("text").asText());
    }

    @Test
    void multi_turn_history_is_flattened_into_a_labelled_transcript() {
        connected().build().complete(List.of(
                LlmMessage.user("q1"), new LlmMessage("assistant", "a1"), LlmMessage.user("q2")), CTX);

        assertEquals("[user]\nq1\n\n[assistant]\na1\n\n[user]\nq2",
                promptBodies.get(0).path("parts").get(0).path("text").asText());
    }

    @Test
    void opencode_builtin_tools_are_disabled_on_every_prompt() {
        connected().build().complete(List.of(LlmMessage.user("hi")), CTX);

        JsonNode tools = promptBodies.get(0).path("tools");
        assertEquals(4, tools.size());
        assertFalse(tools.path("bash").asBoolean(true));
        assertFalse(tools.path("read").asBoolean(true));
        assertFalse(tools.path("edit").asBoolean(true));
        assertFalse(tools.path("invalid").asBoolean(true));
    }

    @Test
    void zen_free_tier_tools_leave_bash_and_read_on_and_nothing_else() {
        connected().zenFreeTierTools(true).build().complete(List.of(LlmMessage.user("hi")), CTX);

        // Zen's free tier refuses any call that does not offer both of these, so they are the
        // only tools that may come out enabled — and opencode is what executes them.
        JsonNode tools = promptBodies.get(0).path("tools");
        assertEquals(4, tools.size(), "no id is invented: only the ones opencode reported");
        assertTrue(tools.path("bash").asBoolean());
        assertTrue(tools.path("read").asBoolean());
        assertFalse(tools.path("edit").asBoolean());
        assertFalse(tools.path("invalid").asBoolean());
    }

    @Test
    void zen_free_tier_refusal_names_the_flag_that_would_fix_it() {
        replyJson = reply("", "{\"name\":\"APIError\",\"data\":{\"message\":\"OpenCode's free tier can only be"
                + " used from within OpenCode\",\"statusCode\":403,\"responseBody\":\"{\\\"error\\\":{\\\"type\\\":"
                + "\\\"FreeTierError\\\"}}\"}}");

        LlmException e = assertThrows(LlmException.class,
                () -> connected().build().complete(List.of(LlmMessage.user("hi")), CTX));

        assertFalse(e.shouldFailover(), "a verdict on the request cannot change by being resent");
        assertTrue(e.getMessage().contains("zenFreeTierTools(true)"), e.getMessage());
    }

    @Test
    void model_is_split_on_the_first_slash_only() {
        connected().model("lmstudio/qwen/qwen3-30b").build().complete(List.of(LlmMessage.user("hi")), CTX);

        JsonNode model = promptBodies.get(0).path("model");
        assertEquals("lmstudio", model.path("providerID").asText());
        assertEquals("qwen/qwen3-30b", model.path("modelID").asText());
    }

    @Test
    void malformed_model_is_rejected_up_front() {
        assertThrows(LlmException.class, () -> connected().model("no-slash").build());
    }

    @Test
    void password_is_sent_as_basic_auth() {
        connected().password("s3cret").build();

        String expected = "Basic " + java.util.Base64.getEncoder().encodeToString("opencode:s3cret".getBytes(StandardCharsets.UTF_8));
        assertEquals(expected, authHeader);
    }

    @Test
    void media_is_a_hard_non_failover_error() {
        MediaRef image = new MediaRef("m1", "image/png", "a.png", 1, URI.create("file:///a.png"));

        LlmException e = assertThrows(LlmException.class, () -> connected().build()
                .complete(List.of(LlmMessage.user("look", List.of(image))), CTX));

        assertFalse(e.shouldFailover());
        assertFalse(requests.contains("POST /session"), "must fail before touching the server");
    }

    @Test
    void provider_error_inside_a_200_reply_is_mapped_and_session_still_deleted() {
        replyJson = reply("", "{\"name\":\"ProviderAuthError\",\"data\":{\"message\":\"bad key\"}}");

        LlmException e = assertThrows(LlmException.class,
                () -> connected().build().complete(List.of(LlmMessage.user("hi")), CTX));

        assertTrue(e.isAuthenticationError());
        assertTrue(requests.contains("DELETE /session/ses_1"));
    }

    @Test
    void reply_without_text_is_an_empty_response_error() {
        replyJson = "{\"info\":{\"finish\":\"stop\"},\"parts\":[{\"type\":\"step-start\"}]}";

        LlmException e = assertThrows(LlmException.class,
                () -> connected().build().complete(List.of(LlmMessage.user("hi")), CTX));

        assertTrue(e.isEmptyResponse());
    }

    @Test
    void a_reply_that_is_only_a_tool_call_names_the_tool() {
        replyJson = "{\"info\":{\"finish\":\"stop\"},\"parts\":[{\"type\":\"tool\",\"tool\":\"invalid\"}]}";

        LlmException e = assertThrows(LlmException.class,
                () -> connected().build().complete(List.of(LlmMessage.user("hi")), CTX));

        assertTrue(e.isEmptyResponse());
        assertTrue(e.getMessage().contains("'invalid'"), e.getMessage());
    }

    @Test
    void unreachable_server_is_a_network_error() {
        int deadPort = server.getAddress().getPort();
        server.stop(0);

        LlmException e = assertThrows(LlmException.class,
                () -> OpenCodeServerAdapter.builder().baseUrl("http://127.0.0.1:" + deadPort).build());

        assertTrue(e.isNetworkError());
    }

    @Test
    void launch_mode_starts_the_binary_reads_its_url_and_kills_it_on_close(@TempDir Path dir) throws Exception {
        Path pidFile = dir.resolve("pid");
        Path fake = fakeBinary(dir, "echo $$ > " + pidFile + "\n"
                + "echo \"opencode server listening on http://127.0.0.1:" + server.getAddress().getPort() + "\"\n"
                + "exec sleep 60\n");

        try (OpenCodeServerAdapter adapter = OpenCodeServerAdapter.builder()
                .executable(fake.toString()).startupTimeout(Duration.ofSeconds(10)).build()) {
            assertEquals("Hello", adapter.complete(List.of(LlmMessage.user("hi")), CTX).text());
            long pid = Long.parseLong(Files.readString(pidFile).trim());
            assertTrue(ProcessHandle.of(pid).isPresent(), "child should be running while the adapter is open");

            adapter.close();

            ProcessHandle.of(pid).ifPresent(h -> h.onExit().orTimeout(5, java.util.concurrent.TimeUnit.SECONDS).join());
            assertTrue(ProcessHandle.of(pid).isEmpty(), "close() must stop the server this adapter launched");
        }
    }

    @Test
    void launch_mode_fails_when_the_binary_never_announces_a_url(@TempDir Path dir) throws Exception {
        Path fake = fakeBinary(dir, "echo nothing useful\n");

        LlmException e = assertThrows(LlmException.class,
                () -> OpenCodeServerAdapter.builder().executable(fake.toString()).build());

        assertTrue(e.getMessage().contains("exited before announcing"));
    }

    @Test
    void launch_mode_fails_when_the_binary_does_not_exist() {
        assertThrows(LlmException.class,
                () -> OpenCodeServerAdapter.builder().executable("/nonexistent/opencode").build());
    }

    @Test
    void close_on_a_connected_adapter_leaves_the_server_alone() {
        OpenCodeServerAdapter adapter = connected().build();

        adapter.close();

        assertEquals("opencode-default", adapter.providerId());
        assertDoesNotThrow(() -> adapter.complete(List.of(LlmMessage.user("hi")), CTX));
    }

    private static Path fakeBinary(Path dir, String script) throws IOException {
        Path file = dir.resolve("fake-opencode");
        Files.writeString(file, "#!/bin/sh\n" + script);
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rwxr-xr-x"));
        return file;
    }
}
