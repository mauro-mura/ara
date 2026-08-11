package io.ara.runtime.contract;

import io.ara.core.agent.AgentTask;
import io.ara.core.agent.RunContext;
import io.ara.core.agent.RunState;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class StateToPromptShaperTest {

    private static AgentTask taskWithState(RunState state) {
        return AgentTask.of("hi").withRunContext(RunContext.empty().withState(state));
    }

    private static AgentTask taskWithUserMemory(RunState memory) {
        return AgentTask.of("hi").withRunContext(RunContext.empty().withUserMemory(memory));
    }

    @Test
    void emptyState_promptUnchanged() {
        AgentTask task = taskWithState(RunState.inMemory());
        assertEquals("base prompt", StateToPromptShaper.all().shape("base prompt", task));
    }

    @Test
    void all_appendsEveryEntry() {
        RunState state = RunState.inMemory();
        state.put("mood", "curious");
        state.put("visits", 3);
        AgentTask task = taskWithState(state);

        String shaped = StateToPromptShaper.all().shape("base prompt", task);

        assertTrue(shaped.startsWith("base prompt"));
        assertTrue(shaped.contains("mood: curious"));
        assertTrue(shaped.contains("visits: 3"));
    }

    @Test
    void of_projectsOnlyNamedKeys() {
        RunState state = RunState.inMemory();
        state.put("shown", "yes");
        state.put("hidden", "no");
        AgentTask task = taskWithState(state);

        String shaped = StateToPromptShaper.of("shown").shape("base prompt", task);

        assertTrue(shaped.contains("shown: yes"));
        assertFalse(shaped.contains("hidden"));
    }

    @Test
    void of_missingKey_promptUnchanged() {
        AgentTask task = taskWithState(RunState.inMemory());
        assertEquals("base prompt", StateToPromptShaper.of("absent").shape("base prompt", task));
    }

    @Test
    void doesNotAutoInterpolate_PromptTemplatePlaceholdersAreUntouched() {
        // Never reads/writes RunContext.promptVars — state and promptVars stay separate channels.
        RunState state = RunState.inMemory();
        state.put("k", "should-not-appear-as-a-placeholder-value");
        AgentTask task = taskWithState(state);

        String shaped = StateToPromptShaper.all().shape("Hello {k}", task);

        assertTrue(shaped.startsWith("Hello {k}"), "the {k} placeholder itself must be left for PromptTemplate, untouched");
    }

    // ── forUserMemory() / forUserMemory(keys...) (ADR-043 rev. 3) ──────────────

    @Test
    void forUserMemory_projectsUserMemory_notSessionState() {
        RunState memory = RunState.inMemory();
        memory.put("favorite_color", "teal");
        AgentTask task = taskWithUserMemory(memory);

        String shaped = StateToPromptShaper.forUserMemory().shape("base prompt", task);

        assertTrue(shaped.contains("favorite_color: teal"));
        assertTrue(shaped.contains("What you remember about this user"));
        assertFalse(shaped.contains("Current session state"));
    }

    @Test
    void forUserMemory_ignoresSessionState() {
        RunState state  = RunState.inMemory();
        state.put("session_only", "should-not-appear");
        RunState memory = RunState.inMemory();
        memory.put("cross_session", "should-appear");
        AgentTask task = AgentTask.of("hi").withRunContext(
                RunContext.empty().withState(state).withUserMemory(memory));

        String shaped = StateToPromptShaper.forUserMemory().shape("base prompt", task);

        assertTrue(shaped.contains("cross_session: should-appear"));
        assertFalse(shaped.contains("session_only"));
    }

    @Test
    void forUserMemory_withKeys_projectsOnlyNamedKeys() {
        RunState memory = RunState.inMemory();
        memory.put("shown", "yes");
        memory.put("hidden", "no");
        AgentTask task = taskWithUserMemory(memory);

        String shaped = StateToPromptShaper.forUserMemory("shown").shape("base prompt", task);

        assertTrue(shaped.contains("shown: yes"));
        assertFalse(shaped.contains("hidden"));
    }

    @Test
    void forUserMemory_empty_promptUnchanged() {
        AgentTask task = taskWithUserMemory(RunState.inMemory());
        assertEquals("base prompt", StateToPromptShaper.forUserMemory().shape("base prompt", task));
    }

    @Test
    void all_and_of_stillUseSessionStateHeading_unaffectedByUserMemoryAddition() {
        RunState state = RunState.inMemory();
        state.put("k", "v");
        AgentTask task = taskWithState(state);

        String shaped = StateToPromptShaper.all().shape("base prompt", task);

        assertTrue(shaped.contains("Current session state:"));
        assertFalse(shaped.contains("What you remember about this user"));
    }
}
