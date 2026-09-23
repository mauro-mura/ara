package io.ara.adapters.mcp;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.json.TypeRef;
import io.modelcontextprotocol.spec.McpClientTransport;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers {@link AraMcpClientAdapter#close()}'s executor-ownership rule (concurrency
 * hardening P4/U13): an executor {@link McpClientFactory} created just for this one
 * client must be shut down when the client closes; an executor the caller supplied —
 * possibly shared across several clients — must never be touched.
 *
 * <p>Never calls {@link McpSyncClient#initialize()} — a real handshake needs a transport
 * that actually speaks JSON-RPC, which {@link #FAKE_TRANSPORT} does not implement (every
 * method beyond {@code closeGracefully()} throws). {@link McpClient.SyncSpec#build()}
 * itself performs no I/O, so an uninitialized client's {@code close()} is exactly what's
 * under test here, and it is enough on its own to observe the executor being (or not
 * being) shut down.
 */
class AraMcpClientAdapterTest {

    /** Never actually invoked here beyond {@code closeGracefully()} — see the class javadoc. */
    private static final McpClientTransport FAKE_TRANSPORT = new McpClientTransport() {
        @Override
        public Mono<Void> connect(
                Function<Mono<McpSchema.JSONRPCMessage>, Mono<McpSchema.JSONRPCMessage>> handler) {
            throw new UnsupportedOperationException();
        }
        @Override public Mono<Void> closeGracefully() { return Mono.empty(); }
        @Override public Mono<Void> sendMessage(McpSchema.JSONRPCMessage message) {
            throw new UnsupportedOperationException();
        }
        @Override public <T> T unmarshalFrom(Object data, TypeRef<T> typeRef) {
            throw new UnsupportedOperationException();
        }
    };

    private static McpSyncClient uninitializedSdkClient() {
        return McpClient.sync(FAKE_TRANSPORT).build();
    }

    @Test
    void close_shutsDownAnExecutorItCreatedItself() {
        ExecutorService owned = Executors.newVirtualThreadPerTaskExecutor();
        AraMcpClientAdapter adapter = new AraMcpClientAdapter(uninitializedSdkClient(), owned, true);

        adapter.close();

        assertTrue(owned.isShutdown(), "an executor the adapter created for itself must be shut down on close()");
    }

    @Test
    void close_neverTouchesACallerSuppliedExecutor() {
        ExecutorService callerOwned = Executors.newVirtualThreadPerTaskExecutor();
        AraMcpClientAdapter adapter = new AraMcpClientAdapter(uninitializedSdkClient(), callerOwned, false);

        try {
            adapter.close();
            assertFalse(callerOwned.isShutdown(),
                    "a caller-supplied executor — possibly shared across other clients — must never be shut down by close()");
        } finally {
            callerOwned.shutdownNow();
        }
    }
}
