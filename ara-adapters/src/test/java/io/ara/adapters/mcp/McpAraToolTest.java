package io.ara.adapters.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.ara.core.mcp.McpClient;
import io.ara.core.mcp.McpTool;
import io.ara.core.mcp.McpToolResult;
import io.ara.core.tool.ToolResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers how {@link McpAraTool} turns the model's argument JSON into an MCP call.
 *
 * <p>The assertion that carries the weight is that a malformed argument string produces no
 * call at all. Degrading to an empty argument map — which is what this did — is not a safe
 * fallback for a tool invocation: the server runs anyway, with none of the parameters the
 * model meant to pass, and returns something plausible. The bug then surfaces as a wrong
 * answer somewhere downstream rather than as a failed tool call, which is why every test here
 * checks {@link RecordingMcpClient#calls} and not just the returned {@link ToolResult}.
 */
class McpAraToolTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Records every invocation so tests can assert the server was — or was not — reached. */
    private static final class RecordingMcpClient implements McpClient {
        final List<Map<String, Object>> calls = new ArrayList<>();

        @Override public CompletableFuture<List<McpTool>> listTools() {
            return CompletableFuture.completedFuture(List.of());
        }
        @Override public CompletableFuture<McpToolResult> callTool(String name, Map<String, Object> args) {
            calls.add(args);
            return CompletableFuture.completedFuture(new McpToolResult("ok", false));
        }
        @Override public void close() {}
    }

    private RecordingMcpClient client;
    private McpAraTool tool;

    @BeforeEach
    void setUp() throws Exception {
        client = new RecordingMcpClient();
        McpTool descriptor = new McpTool("search", "searches things",
                MAPPER.readTree("""
                        {"type":"object","properties":{"query":{"type":"string"}},
                         "required":["query"]}"""));
        tool = new McpAraTool(descriptor, new McpToolRegistry(client));
    }

    // ── Malformed arguments must not reach the server ─────────────────────────

    @Test
    void failsWithoutCallingTheServerOnMalformedJson() {
        ToolResult result = tool.execute("{\"query\": unquoted}");

        assertTrue(result.isFailed());
        assertTrue(result.error().contains("Malformed argument JSON"), result.error());
        assertTrue(client.calls.isEmpty(), "the server must not be called with dropped arguments");
    }

    @Test
    void failsWithoutCallingTheServerOnJsonThatIsNotAnObject() {
        ToolResult result = tool.execute("[1, 2, 3]");

        assertTrue(result.isFailed());
        assertTrue(client.calls.isEmpty(), "the server must not be called with dropped arguments");
    }

    // ── Well-formed arguments ─────────────────────────────────────────────────

    @Test
    void passesParsedArgumentsThrough() {
        ToolResult result = tool.execute("{\"query\":\"kittens\",\"limit\":5}");

        assertTrue(result.isSuccess());
        assertEquals(1, client.calls.size());
        assertEquals("kittens", client.calls.get(0).get("query"));
        assertEquals(5, client.calls.get(0).get("limit"));
    }

    @Test
    void treatsAnEmptyArgumentStringAsNoArguments() {
        for (String empty : new String[] {null, "", "  ", "{}"}) {
            client.calls.clear();

            ToolResult result = tool.execute(empty);

            assertTrue(result.isSuccess(), "input: " + empty);
            assertEquals(1, client.calls.size(), "input: " + empty);
            assertTrue(client.calls.get(0).isEmpty(), "input: " + empty);
        }
    }

    @Test
    void treatsABareJsonNullAsNoArguments() {
        ToolResult result = tool.execute("null");

        assertTrue(result.isSuccess());
        assertEquals(1, client.calls.size());
        assertEquals(Map.of(), client.calls.get(0));
    }

    // ── Server-reported failure stays a failure ───────────────────────────────

    @Test
    void reportsAServerSideToolError() {
        McpClient failing = new McpClient() {
            @Override public CompletableFuture<List<McpTool>> listTools() {
                return CompletableFuture.completedFuture(List.of());
            }
            @Override public CompletableFuture<McpToolResult> callTool(String n, Map<String, Object> a) {
                return CompletableFuture.completedFuture(new McpToolResult("upstream exploded", true));
            }
            @Override public void close() {}
        };
        McpAraTool failingTool = new McpAraTool(
                new McpTool("search", "d", null), new McpToolRegistry(failing));

        ToolResult result = failingTool.execute("{}");

        assertTrue(result.isFailed());
        assertEquals("upstream exploded", result.error());
    }

    // ── Interrupt handling (P3 hardening) ─────────────────────────────────────

    /**
     * {@code join()} used to ignore an interrupt entirely, leaving the worker parked until
     * the SDK's own {@code requestTimeout} (30s by default) instead of reacting to
     * cancellation immediately — e.g. from {@code ReactExecutionSupport}'s deadline
     * watchdog interrupting the worker thread.
     */
    @Test
    void interruptIsNoticedImmediately_notOnlyAfterTheServerResponds() throws Exception {
        java.util.concurrent.CompletableFuture<McpToolResult> neverCompletes = new java.util.concurrent.CompletableFuture<>();
        McpClient hanging = new McpClient() {
            @Override public CompletableFuture<List<McpTool>> listTools() {
                return CompletableFuture.completedFuture(List.of());
            }
            @Override public CompletableFuture<McpToolResult> callTool(String n, Map<String, Object> a) {
                return neverCompletes;
            }
            @Override public void close() {}
        };
        McpAraTool hangingTool = new McpAraTool(new McpTool("search", "d", null), new McpToolRegistry(hanging));

        java.util.concurrent.atomic.AtomicReference<ToolResult> result = new java.util.concurrent.atomic.AtomicReference<>();
        Thread worker = new Thread(() -> result.set(hangingTool.execute("{}")));
        worker.start();
        Thread.sleep(50); // let the worker reach get()
        worker.interrupt();
        worker.join(5_000);

        assertFalse(worker.isAlive(), "the worker must not stay parked past the interrupt");
        assertTrue(result.get().isFailed());
        assertTrue(result.get().error().contains("cancelled"), result.get().error());
        assertTrue(neverCompletes.isCancelled(), "the pending future must be cancelled, not left dangling");
    }

    // ── Schema advertisement ──────────────────────────────────────────────────

    @Test
    void advertisesTheServersInputSchema() {
        assertTrue(tool.argumentSchema().contains("\"query\""));
    }

    @Test
    void advertisesNoParametersWhenTheServerDeclaresNoSchema() {
        McpAraTool schemaless = new McpAraTool(
                new McpTool("ping", "d", null), new McpToolRegistry(client));

        assertEquals("{}", schemaless.argumentSchema());
        assertFalse(schemaless.description().isEmpty());
    }
}
