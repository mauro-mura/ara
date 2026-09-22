package io.ara.runtime;

import io.ara.core.agent.AgentConfig;
import io.ara.core.agent.AgentTask;
import io.ara.core.llm.LlmCallContext;
import io.ara.core.llm.LlmClient;
import io.ara.core.llm.LlmCompletion;
import io.ara.core.llm.LlmMessage;
import io.ara.core.memory.MemoryManager;
import io.ara.core.tool.ToolRegistry;
import io.ara.runtime.stubs.InMemoryMemoryManager;
import io.ara.runtime.stubs.ScriptedLlmClient;
import io.ara.runtime.strategy.ReActSupport;
import io.ara.runtime.strategy.ToolCallParser;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The public {@link ReActSupport} facade, exercised from a package outside
 * {@code io.ara.runtime.strategy}: a consumer implementing its own strategy must be able
 * to reuse ARA's ReAct-loop mechanics without re-implementing them.
 */
class ReActSupportTest {

    private static final AgentConfig CONFIG = AgentConfig.defaults().agentType("t").build();

    @Test
    void messageBuffer_materializesWorkingMemory() {
        MemoryManager memory = new InMemoryMemoryManager();
        memory.appendToWorkingMemory("system", "you are helpful");
        memory.appendToWorkingMemory("user", "hello");

        ReActSupport.MessageBuffer buffer = new ReActSupport.MessageBuffer();
        List<LlmMessage> messages = buffer.build(memory, "", "SUFFIX");

        assertEquals(2, messages.size());
        assertEquals("system", messages.get(0).role());
        assertTrue(messages.get(0).content().contains("you are helpful"));
        assertEquals("user", messages.get(1).role());
    }

    @Test
    void toolCatalog_isEmptyWithoutTools() {
        assertEquals("", ReActSupport.toolCatalog(List.of(), false));
        assertEquals("", ReActSupport.toolCatalog(List.of(), true));
    }

    @Test
    void completeWithRetry_returnsScriptedCompletion() throws InterruptedException {
        LlmClient llm = ScriptedLlmClient.script().thenFinalAnswer("hi").build();
        LlmCompletion c = ReActSupport.completeWithRetry(
                llm, List.of(new LlmMessage("user", "q")), LlmCallContext.from(CONFIG),
                Instant.now().plusSeconds(5), CONFIG, "task-1");

        assertTrue(c.text().contains("hi"));
    }

    @Test
    void dispatchSingle_reportsFailureForUnknownTool() {
        ReActSupport.DispatchContext ctx = new ReActSupport.DispatchContext(
                ToolRegistry.empty(), new InMemoryMemoryManager(), new java.util.ArrayList<>(),
                AgentTask.of("q"), 1, Instant.now().plusSeconds(5), false, 0);

        boolean failed = ReActSupport.dispatchSingle(
                new ToolCallParser.ToolCallRequest("no-such-tool", "{}", null), null, ctx);

        assertTrue(failed, "a dispatch to an unknown tool must report failure to the caller");
    }

    @Test
    void chargeRunBudget_withoutARunBudget_isANoOp() {
        assertNull(ReActSupport.chargeRunBudget(
                CONFIG, AgentTask.of("q"), 10, 10, 1, 10, 10, List.of()));
    }
}
