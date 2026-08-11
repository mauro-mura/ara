package io.ara.runtime.factory;

import io.ara.core.llm.LlmCallContext;
import io.ara.core.llm.LlmClient;
import io.ara.core.llm.LlmCompletion;
import io.ara.core.llm.LlmMessage;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies {@link FailoverLlmClient#supportsNativeTools()}'s all-match semantics: a fallback
 * that doesn't speak native tools must not silently claim the composite does, since failover
 * could route any given call to it while the caller has already omitted the text-based tool
 * scaffolding on the assumption every candidate supports native tools.
 */
class FailoverLlmClientTest {

    private static LlmClient client(boolean nativeTools) {
        return new LlmClient() {
            @Override public LlmCompletion complete(List<LlmMessage> messages, LlmCallContext context) {
                return new LlmCompletion("ok", 1, 1, "stop", null);
            }
            @Override public String providerId() { return nativeTools ? "native" : "non-native"; }
            @Override public boolean supportsNativeTools() { return nativeTools; }
        };
    }

    @Test
    void supportsNativeTools_trueOnlyWhenEveryCandidateSupportsIt() {
        assertTrue(new FailoverLlmClient(List.of(client(true), client(true))).supportsNativeTools());
        assertFalse(new FailoverLlmClient(List.of(client(true), client(false))).supportsNativeTools());
        assertFalse(new FailoverLlmClient(List.of(client(false))).supportsNativeTools());
    }
}
