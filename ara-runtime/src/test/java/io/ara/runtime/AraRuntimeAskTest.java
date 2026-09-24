package io.ara.runtime;

import io.ara.core.agent.AgentResponse;
import io.ara.core.agent.AraAgent;
import io.ara.runtime.stubs.ScriptedLlmClient;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link AraRuntime#ask}/{@link AraRuntime#askText} — the shared-default-agent shortcut.
 * Runs offline against {@link ScriptedLlmClient}.
 */
class AraRuntimeAskTest {

    private static AraRuntime runtimeFor(String answer) {
        return AraRuntime.builder()
                .llmClient(ScriptedLlmClient.script().thenFinalAnswer(answer).build())
                .build();
    }

    @Test
    void askText_returnsTheScriptedAnswer() {
        AraRuntime runtime = runtimeFor("virtual threads are lightweight");
        try {
            assertEquals("virtual threads are lightweight", runtime.askText("explain virtual threads"));
        } finally {
            runtime.stop();
        }
    }

    @Test
    void ask_returnsASuccessfulResponse() {
        AraRuntime runtime = runtimeFor("done");
        try {
            AgentResponse response = runtime.ask("anything");
            assertTrue(response.isSuccess(), () -> "execute failed: " + response.failureReason());
            assertEquals("done", response.content());
        } finally {
            runtime.stop();
        }
    }

    @Test
    void repeatedAsks_reuseOneDefaultAgent() {
        AraRuntime runtime = runtimeFor("done");
        try {
            runtime.askText("first");
            runtime.askText("second");

            assertEquals(1, runtime.registry().count(),
                    "the default agent must be created once and reused");
        } finally {
            runtime.stop();
        }
    }

    @Test
    void afterDefaultAgentIsDestroyed_nextAskCreatesAFreshOne() {
        AraRuntime runtime = runtimeFor("done");
        try {
            runtime.askText("first");
            AraAgent first = runtime.registry().all().iterator().next();

            runtime.destroyAgent(first);
            assertEquals(0, runtime.registry().count());

            runtime.askText("second");
            AraAgent second = runtime.registry().all().iterator().next();

            assertEquals(1, runtime.registry().count());
            assertNotEquals(first.agentId(), second.agentId(),
                    "a destroyed default agent must not be handed to the next ask");
        } finally {
            runtime.stop();
        }
    }
}
