package io.ara.core.llm;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LlmTransportTest {

    @Test
    void nullBaseUrl_throws() {
        assertThrows(NullPointerException.class, () -> new LlmTransport(null, "key", "model"));
    }

    @Test
    void blankBaseUrl_throws() {
        assertThrows(IllegalArgumentException.class, () -> new LlmTransport("  ", "key", "model"));
    }

    @Test
    void nullModelName_throws() {
        assertThrows(NullPointerException.class, () -> new LlmTransport("http://h", "key", null));
    }

    @Test
    void blankModelName_throws() {
        assertThrows(IllegalArgumentException.class, () -> new LlmTransport("http://h", "key", " "));
    }

    @Test
    void nullApiKey_isAllowed_forLocalServersWithoutAuth() {
        assertDoesNotThrow(() -> new LlmTransport("http://localhost:11434", null, "llama3"));
    }
}
