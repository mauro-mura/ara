package io.ara.adapters.llm.opencode;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.ara.core.llm.LlmCallContext;
import io.ara.core.llm.LlmClient;
import io.ara.core.llm.LlmCompletion;
import io.ara.core.llm.LlmException;
import io.ara.core.llm.LlmMessage;

/**
 * {@link LlmClient} adapter that uses an <a href="https://opencode.ai">opencode</a> headless
 * server ({@code opencode serve}) as the model backend — so ARA can reuse whatever providers,
 * credentials and subscriptions the user already configured in opencode.
 *
 * <p>The server may be <em>launched</em> on this machine or <em>reached</em> at any URL, so
 * nothing here assumes the server is local. That distinction matters for more than latency:
 * see {@link Builder#zenFreeTierTools(boolean)} and {@link Builder#baseUrl(String)}, whose
 * combination is what grants a model shell access to the server's host.
 *
 * <p><b>Thread-safety:</b> thread-safe. All fields are final and every call opens its own
 * opencode session; the only mutable thing is the child process, touched by {@link #close()}.
 *
 * <h2>Two ways to get a server</h2>
 * <ul>
 *   <li>{@link Builder#baseUrl(String)} — <em>connect</em> to a server that is already
 *       running, on any host reachable from here: loopback, a LAN box, a shared team server.
 *       {@link #close()} leaves it alone: this adapter did not start it.</li>
 *   <li>otherwise — <em>launch</em> {@code opencode serve --port 0 --hostname 127.0.0.1} as a
 *       child process, read the ephemeral URL it prints on stdout, and kill the process on
 *       {@link #close()} (and at JVM exit, so an unclosed adapter cannot orphan a server).
 *       This mode is the loopback-bound one, hence local by construction; the launched binary
 *       is the only opencode running that belongs to this adapter.</li>
 * </ul>
 *
 * <h2>How an {@code LlmClient} call maps onto opencode</h2>
 * <p>opencode is an <em>agent</em> with its own sessions, not a completion endpoint. ARA's
 * {@code LlmClient} is stateless — the caller resends the whole history every call — so each
 * {@link #complete} creates a throwaway session, posts the history as one prompt, reads the
 * reply and deletes the session. Reusing one session across calls was discarded: opencode
 * would then append ARA's resent history to its own copy of it, duplicating every turn and
 * growing the context quadratically.
 *
 * <p>System messages go to opencode's {@code system} field; the remaining turns are
 * flattened into a single {@code [role]}-labelled transcript (a lone user message is sent
 * verbatim, so the common one-shot case carries no scaffolding).
 *
 * <h2>Known limits (deliberate — say so rather than surprise the caller)</h2>
 * <ul>
 *   <li><b>opencode's own tools are switched off</b> for every call (the ids are read once
 *       from {@code /experimental/tool/ids}), unless {@link Builder#zenFreeTierTools(boolean)}
 *       is turned on. ARA runs its own tool loop; leaving opencode's {@code bash}/{@code edit}
 *       enabled would let the model act on the machine behind the runtime's back, past ARA's HITL
 *       gate. Consequently {@link #supportsNativeTools()} is {@code false} and ARA's text-based
 *       tool catalog is used.</li>
 *   <li><b>opencode Zen's free tier is unreachable with the tools off.</b> The gateway answers a
 *       call only when it is offered opencode's own {@code bash} <em>and</em> {@code read}: deny
 *       either one and it replies {@code FreeTierError} — "OpenCode's free tier can only be used
 *       from within OpenCode" — which is what made {@code opencode/big-pickle} unusable here.
 *       Those two are how the gateway recognises an in-client agent call. Offering them hands the
 *       model the machine, so {@link Builder#zenFreeTierTools(boolean)} is opt-in. Whether a
 *       <em>paid</em> Zen model is refused the same way is untested: the paid ids this server
 *       knows about fail earlier, on opencode's side, with a 500.</li>
 *   <li><b>Sampling parameters are ignored</b>: temperature, top-p, max tokens, stop
 *       sequences, seed and the output JSON schema have no counterpart on opencode's message
 *       endpoint, so the model's own defaults apply.</li>
 *   <li><b>Media is rejected</b> with a non-failover {@link LlmException}
 *       ({@code supportedMediaTypes()} is empty) rather than silently dropped.</li>
 *   <li><b>No native streaming</b>: {@link #stream} uses the interface default, which emits
 *       the whole reply as one item. Real token streaming would need the {@code /event} SSE
 *       feed correlated by message id; not added until something needs it.</li>
 *   <li><b>Token counts include opencode's own system prompt</b> (thousands of tokens), so
 *       they overstate what ARA's prompt cost.</li>
 * </ul>
 */
public final class OpenCodeServerAdapter implements LlmClient, AutoCloseable {

    private static final String PROVIDER = "opencode";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern LISTENING = Pattern.compile("listening on (http://\\S+)");

    /**
     * The tools opencode Zen insists on before it serves a free-tier call: measured by denying
     * them one at a time, a request that offers everything except {@code bash} is refused, and so
     * is one that offers everything except {@code read}; denying {@code edit} or {@code write} is
     * accepted. The gateway is deciding "is this a real opencode agent call?" from the tool
     * list, and those two are opencode's signature.
     *
     * <p>They are the price of a free model, and they are not free: {@code bash} runs commands and
     * {@code read} reads files, on whatever machine the opencode server sits on, behind ARA's back
     * — opencode executes them inside its own loop and only the final text reaches ARA, so the
     * HITL gate never sees the call. Hence a builder flag rather than a default.
     */
    private static final Set<String> ZEN_FREE_TIER_TOOLS = Set.of("bash", "read");

    private final HttpClient http;
    private final URI baseUri;
    private final String authorization;            // nullable — server not password-protected
    private final Duration timeout;
    private final String modelLabel;               // "provider/model" or "default"
    private final ObjectNode modelSelection;       // nullable — server picks its configured default
    private final Set<String> keptTools;           // empty unless zenFreeTierTools(true)
    private final ObjectNode toolSelection;
    private final Process process;                 // nullable — null when connected to an existing server
    private final Thread shutdownHook;             // nullable — set iff process != null

    private OpenCodeServerAdapter(Builder b, URI baseUri, Process process) {
        this.baseUri = baseUri;
        this.process = process;
        this.timeout = b.timeout;
        this.authorization = b.password == null ? null : "Basic " + Base64.getEncoder()
                .encodeToString(("opencode:" + b.password).getBytes(StandardCharsets.UTF_8));
        this.modelLabel = b.model == null ? "default" : b.model;
        this.modelSelection = b.model == null ? null : parseModel(b.model);
        this.keptTools = b.zenFreeTierTools ? ZEN_FREE_TIER_TOOLS : Set.of();
        // HTTP/1.1 forced: over plain http the JDK client defaults to offering an h2c upgrade, and
        // opencode's server never answers it — the first request just hangs until the timeout.
        this.http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(10)).build();
        this.toolSelection = fetchToolSelection();
        this.shutdownHook = process == null ? null : new Thread(process::destroyForcibly);
        if (shutdownHook != null) Runtime.getRuntime().addShutdownHook(shutdownHook);
    }

    public static Builder builder() {
        return new Builder();
    }

    @Override
    public String providerId() {
        return PROVIDER + "-" + modelLabel;
    }

    @Override
    public LlmCompletion complete(List<LlmMessage> messages, LlmCallContext context) throws LlmException {
        rejectMedia(messages);
        String sessionId = send("POST", "/session", JSON.createObjectNode().put("title", "ara")).path("id").asText();
        try {
            JsonNode reply = send("POST", "/session/" + sessionId + "/message", promptBody(messages));
            return toCompletion(reply);
        } finally {
            deleteQuietly(sessionId);
        }
    }

    /** Stops the server this adapter launched; does nothing when it connected to an existing one. */
    @Override
    public void close() {
        if (process == null) return;
        process.destroy();
        try {
            Runtime.getRuntime().removeShutdownHook(shutdownHook);
        } catch (IllegalStateException alreadyShuttingDown) {
            // the hook is what is running right now — nothing to remove
        }
    }

    // ── Request ────────────────────────────────────────────────────────────────

    private ObjectNode promptBody(List<LlmMessage> messages) {
        ObjectNode body = JSON.createObjectNode();
        String system = joinContents(messages);
        if (!system.isEmpty()) body.put("system", system);
        if (modelSelection != null) body.set("model", modelSelection);
        body.set("tools", toolSelection);
        ArrayNode parts = body.putArray("parts");
        parts.addObject().put("type", "text").put("text", transcript(messages));
        return body;
    }

    private static String joinContents(List<LlmMessage> messages) {
        StringBuilder sb = new StringBuilder();
        for (LlmMessage m : messages) {
            if (!m.role().equals("system")) continue;
            if (!sb.isEmpty()) sb.append("\n\n");
            sb.append(m.content());
        }
        return sb.toString();
    }

    private static String transcript(List<LlmMessage> messages) {
        List<LlmMessage> turns = messages.stream().filter(m -> !m.role().equals("system")).toList();
        if (turns.size() == 1 && turns.getFirst().role().equals("user")) return turns.getFirst().content();
        StringBuilder sb = new StringBuilder();
        for (LlmMessage m : turns) {
            if (!sb.isEmpty()) sb.append("\n\n");
            sb.append('[').append(label(m)).append("]\n").append(m.content());
        }
        return sb.toString();
    }

    private static String label(LlmMessage m) {
        return m.toolName() == null ? m.role() : m.role() + " " + m.toolName();
    }

    private static void rejectMedia(List<LlmMessage> messages) {
        for (LlmMessage m : messages) {
            if (m.media().isEmpty()) continue;
            var first = m.media().getFirst();
            throw LlmException.unsupportedMediaType(PROVIDER, first.mimeType(), first.name(), java.util.Set.of());
        }
    }

    /** {@code "provider/model"} → opencode's {@code {providerID, modelID}}; split on the first slash only, since model ids contain slashes. */
    private static ObjectNode parseModel(String model) {
        int slash = model.indexOf('/');
        if (slash <= 0 || slash == model.length() - 1) {
            throw LlmException.invalidRequest(PROVIDER, "model must be 'providerID/modelID', got '" + model + "'");
        }
        return JSON.createObjectNode()
                .put("providerID", model.substring(0, slash))
                .put("modelID", model.substring(slash + 1));
    }

    /**
     * The {@code tools} map sent with every prompt: {@code false} denies a tool, so each id
     * opencode reports gets the inverse of {@link #keptTools} — everything off, except what the
     * caller explicitly paid for with {@link Builder#zenFreeTierTools(boolean)}.
     */
    private ObjectNode fetchToolSelection() {
        ObjectNode tools = JSON.createObjectNode();
        for (JsonNode id : send("GET", "/experimental/tool/ids", null)) {
            tools.put(id.asText(), keptTools.contains(id.asText()));
        }
        return tools;
    }

    // ── Response ───────────────────────────────────────────────────────────────

    private static LlmCompletion toCompletion(JsonNode reply) {
        JsonNode info = reply.path("info");
        if (info.hasNonNull("error")) throw mapMessageError(info.get("error"));
        StringBuilder text = new StringBuilder();
        for (JsonNode part : reply.path("parts")) {
            if (part.path("type").asText().equals("text")) text.append(part.path("text").asText());
        }
        if (text.isEmpty()) throw LlmException.emptyResponse(PROVIDER, "opencode answered without any text" + calledTool(reply));
        JsonNode tokens = info.path("tokens");
        return new LlmCompletion(text.toString(), tokens.path("input").asInt(), tokens.path("output").asInt(),
                info.path("finish").asText("stop"), null);
    }

    /**
     * Names the opencode tool the model reached for, so a reply that is only a tool call explains
     * itself: it means the model wanted to act, and this adapter's job is to let ARA's own tool
     * loop do that instead.
     */
    private static String calledTool(JsonNode reply) {
        for (JsonNode part : reply.path("parts")) {
            if (part.path("type").asText().startsWith("tool")) {
                return " (it called its own tool '" + part.path("tool").asText() + "')";
            }
        }
        return "";
    }

    /** opencode reports provider failures inside a 200 reply, as {@code info.error.{name,data.message}}. */
    private static LlmException mapMessageError(JsonNode error) {
        String name = error.path("name").asText();
        String message = name + ": " + error.path("data").path("message").asText("(no message)");
        // The gateway refuses a free-tier call whose tool list lacks bash or read, and the SDK
        // surfaces that refusal as a plain APIError, so the marker is only in responseBody. It is
        // a verdict on the request, not a transient failure: resending it earns the same 403.
        if (error.path("data").path("responseBody").asText("").contains("FreeTierError")) {
            return LlmException.invalidRequest(PROVIDER, message
                    + " — Zen only serves in-client calls that offer opencode's bash and read tools,"
                    + " so a free model needs zenFreeTierTools(true)");
        }
        return switch (name) {
            case "ProviderAuthError" -> LlmException.authenticationError(PROVIDER, message);
            case "ContextOverflowError", "MessageOutputLengthError" -> LlmException.invalidRequest(PROVIDER, message);
            case "ContentFilterError" -> LlmException.contentFiltered(PROVIDER, message);
            case "APIError" -> LlmException.serverError(PROVIDER, message, error.path("data").path("statusCode").asInt(500));
            default -> new LlmException(message);
        };
    }

    // ── HTTP ───────────────────────────────────────────────────────────────────

    private JsonNode send(String method, String path, JsonNode body) {
        HttpRequest.Builder req = HttpRequest.newBuilder(baseUri.resolve(path)).timeout(timeout)
                .header("Content-Type", "application/json");
        if (authorization != null) req.header("Authorization", authorization);
        req.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                                        : HttpRequest.BodyPublishers.ofString(body.toString()));
        try {
            HttpResponse<String> res = http.send(req.build(), HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() / 100 != 2) throw mapHttpError(res.statusCode(), res.body());
            return JSON.readTree(res.body());
        } catch (HttpTimeoutException e) {
            throw LlmException.timeout(PROVIDER, "opencode did not answer within " + timeout, e);
        } catch (IOException e) {
            throw LlmException.networkError(PROVIDER, "cannot reach opencode at " + baseUri + ": " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw LlmException.networkError(PROVIDER, "interrupted while waiting for opencode", e);
        }
    }

    private static LlmException mapHttpError(int status, String body) {
        String message = "HTTP " + status + ": " + body;
        return switch (status) {
            case 401, 403 -> LlmException.authenticationError(PROVIDER, message);
            case 429 -> LlmException.rateLimit(PROVIDER, message);
            case 400, 404, 422 -> LlmException.invalidRequest(PROVIDER, message);
            default -> LlmException.serverError(PROVIDER, message, status);
        };
    }

    /** Session cleanup must never mask the completion (or the real error) the caller is waiting for. */
    private void deleteQuietly(String sessionId) {
        try {
            send("DELETE", "/session/" + sessionId, null);
        } catch (LlmException ignored) {
            // Best-effort, and the leak is accepted rather than propagated: failing the
            // completion over a failed DELETE would be a worse trade than a stray session.
            // What that costs depends on the mode (see Builder#baseUrl) — on a server this
            // adapter launched, the process dies with it; on a shared one the session is
            // left behind for whoever else uses that server, so a connect-mode caller with
            // a failing DELETE is quietly leaking one session per complete() call. Worth
            // knowing before pointing this at a server that isn't yours.
        }
    }

    // ── Builder ────────────────────────────────────────────────────────────────

    /** Not thread-safe; confine to the building thread. */
    public static final class Builder {
        private String baseUrl;
        private String executable = "opencode";
        private String model;
        private String password;
        private boolean zenFreeTierTools;
        private Duration timeout = Duration.ofMinutes(5);
        private Duration startupTimeout = Duration.ofSeconds(30);

        /**
         * Connect to a server that is already running instead of launching one. Any reachable
         * URL: loopback, a LAN box, a shared team server — nothing here is restricted to
         * localhost, which is why {@link #zenFreeTierTools(boolean)} reads "the opencode host"
         * rather than "this machine". {@link #close()} leaves a connected server alone.
         */
        public Builder baseUrl(String v) { this.baseUrl = v; return this; }

        /** Launch mode only: the opencode binary. Defaults to {@code opencode} on the PATH. */
        public Builder executable(String v) { this.executable = v; return this; }

        /** {@code providerID/modelID} as opencode names them; unset = the server's configured default. */
        public Builder model(String v) { this.model = v; return this; }

        /** Server password ({@code OPENCODE_SERVER_PASSWORD}): sent when connecting, set on the child when launching. */
        public Builder password(String v) { this.password = v; return this; }

        /**
         * Leave opencode's own {@code bash} and {@code read} enabled so opencode Zen will serve a
         * free-tier model ({@code opencode/big-pickle} and the other zero-cost ones). Off by
         * default because the price is real: the model can then read files and run commands on
         * the opencode host, inside opencode's own loop, and ARA neither sees the call nor can
         * gate it. Turn it on for a machine you would let a model loose in — a scratch box, a
         * container — or keep it off and use a paid model or a local one.
         *
         * <p>Which machine that is depends on the other builder settings, and the difference is
         * the whole point: combined with {@link #baseUrl(String)} pointing at a shared or remote
         * server, this hands {@code bash} and {@code read} on <em>that</em> host, not on yours.
         * Launch mode is the only one where the two coincide.
         */
        public Builder zenFreeTierTools(boolean v) { this.zenFreeTierTools = v; return this; }

        /** Per-request timeout. A whole agent turn runs inside one request, hence the generous default. */
        public Builder timeout(Duration v) { this.timeout = v; return this; }

        /** Launch mode only: how long to wait for the server to announce its URL. */
        public Builder startupTimeout(Duration v) { this.startupTimeout = v; return this; }

        public OpenCodeServerAdapter build() {
            if (baseUrl != null) return new OpenCodeServerAdapter(this, withTrailingSlash(baseUrl), null);
            Process process = launch();
            try {
                return new OpenCodeServerAdapter(this, awaitUrl(process), process);
            } catch (RuntimeException e) {
                process.destroyForcibly();
                throw e;
            }
        }

        private static URI withTrailingSlash(String url) {
            return URI.create(url.endsWith("/") ? url : url + "/");
        }

        private Process launch() {
            ProcessBuilder pb = new ProcessBuilder(executable, "serve", "--port", "0", "--hostname", "127.0.0.1")
                    .redirectErrorStream(true);
            if (password != null) pb.environment().put("OPENCODE_SERVER_PASSWORD", password);
            try {
                return pb.start();
            } catch (IOException e) {
                throw LlmException.connectionError(PROVIDER, "cannot start '" + executable + " serve': " + e.getMessage(), e);
            }
        }

        /**
         * Reads the child's output until it prints {@code opencode server listening on <url>}.
         * The read happens on its own virtual thread so {@link #startupTimeout} can bound it;
         * after the URL is found the same thread keeps draining output, because a child whose
         * stdout pipe fills up blocks on its next log line.
         */
        private URI awaitUrl(Process process) {
            CompletableFuture<URI> url = new CompletableFuture<>();
            Thread.ofVirtual().start(() -> {
                try (BufferedReader out = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = out.readLine()) != null) {
                        Matcher m = LISTENING.matcher(line);
                        if (m.find() && !url.isDone()) url.complete(withTrailingSlash(m.group(1)));
                    }
                    url.completeExceptionally(new IOException("opencode exited before announcing its URL"));
                } catch (IOException e) {
                    url.completeExceptionally(e);
                }
            });
            try {
                return url.get(startupTimeout.toMillis(), TimeUnit.MILLISECONDS);
            } catch (java.util.concurrent.TimeoutException e) {
                throw LlmException.timeout(PROVIDER, "opencode did not announce its URL within " + startupTimeout, e);
            } catch (java.util.concurrent.ExecutionException e) {
                throw LlmException.connectionError(PROVIDER, e.getCause().getMessage(), e.getCause());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw LlmException.connectionError(PROVIDER, "interrupted while starting opencode", e);
            }
        }
    }
}
