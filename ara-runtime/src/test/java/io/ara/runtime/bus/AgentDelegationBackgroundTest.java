package io.ara.runtime.bus;

import io.ara.core.agent.AgentResponse;
import io.ara.core.agent.AgentTask;
import io.ara.core.agent.AsyncDelegation;
import io.ara.core.agent.AsyncSupport;
import io.ara.core.agent.DelegateStateAccess;
import io.ara.core.agent.SessionId;
import io.ara.core.agent.SessionStore;
import io.ara.core.auth.ScopeSet;
import io.ara.core.bus.AgentMessage;
import io.ara.core.bus.Delegation;
import io.ara.core.bus.MessageBus;
import io.ara.core.tool.ToolResult;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code delegate_task} with {@code "wait": false}: who may start a background task, what the
 * model is told, and what the delegate is (and is not) handed. Driven against a bus that only
 * records, so nothing here depends on an agent running.
 */
class AgentDelegationBackgroundTest {

    private static final String WAITED   = "{\"agent_id\":\"worker\",\"task\":\"do it\"}";
    private static final String BACKGROUND = "{\"agent_id\":\"worker\",\"task\":\"do it\",\"wait\":false,\"label\":\"sources\"}";

    /** Records every message and answers a waited call at once; a background one stays running. */
    private static final class RecordingBus implements MessageBus {
        final List<AgentMessage> requested = new ArrayList<>();
        final List<AgentMessage> delegated = new ArrayList<>();
        RuntimeException failToStart;

        @Override public void send(AgentMessage message) { }

        @Override public AgentMessage request(AgentMessage message, Duration timeout) {
            requested.add(message);
            return AgentMessage.reply(message, message.recipientId(), "waited answer");
        }

        @Override public Delegation delegate(AgentMessage message) {
            if (failToStart != null) throw failToStart;
            delegated.add(message);
            return new Delegation("t-" + delegated.size(), new CompletableFuture<AgentResponse>(), () -> { });
        }
    }

    private static AgentDelegationTool tool(MessageBus bus, AsyncDelegation async, Function<String, AsyncSupport> support) {
        return new AgentDelegationTool(bus, "coordinator", Duration.ofSeconds(5), DelegateStateAccess.OVERLAY,
                SessionStore.noop(), ScopeSet.EMPTY, null, async, support);
    }

    private static AgentDelegationTool enabled(MessageBus bus) {
        return tool(bus, AsyncDelegation.defaults(), id -> null);
    }

    /** A calling task whose run carries a fresh registry. */
    private static AgentTask callerWith(BackgroundTasks tasks) {
        return tasks.attachTo(AgentTask.of("coordinate").withSessionId(SessionId.of("caller-session")));
    }

    @Test
    void waitFalse_startsTheDelegateAtOnce_andAnswersWithItsTaskId() {
        RecordingBus bus = new RecordingBus();
        BackgroundTasks tasks = new BackgroundTasks(AsyncDelegation.defaults());

        ToolResult result = enabled(bus).execute(BACKGROUND, callerWith(tasks));

        assertTrue(result.success(), result.error());
        assertTrue(result.output().contains("\"task_id\":\"t-1\""), result.output());
        assertTrue(result.output().contains("\"label\":\"sources\""), result.output());
        assertTrue(result.output().contains("\"status\":\"working\""), result.output());
        assertEquals(1, bus.delegated.size());
        assertTrue(bus.requested.isEmpty(), "a background call must not wait");
        assertTrue(tasks.hasTasks());
    }

    @Test
    void withoutWaitFalse_theCallIsWaited_exactlyAsBefore() {
        RecordingBus bus = new RecordingBus();

        ToolResult result = enabled(bus).execute(WAITED, callerWith(new BackgroundTasks(AsyncDelegation.defaults())));

        assertEquals("[worker] waited answer", result.output());
        assertTrue(bus.delegated.isEmpty());
    }

    @Test
    void anAgentThatMayNotDelegateInTheBackground_ignoresWaitFalse_andSeesNoTraceOfTheFeature() {
        RecordingBus bus = new RecordingBus();
        AgentDelegationTool off = tool(bus, null, id -> null);

        ToolResult result = off.execute(BACKGROUND, callerWith(new BackgroundTasks(AsyncDelegation.defaults())));

        assertEquals("[worker] waited answer", result.output());
        assertFalse(off.argumentSchema().contains("\"wait\""));
        assertFalse(off.description().contains("Background mode"));
        assertTrue(enabled(bus).argumentSchema().contains("\"wait\""));
        assertTrue(enabled(bus).description().contains("Background mode"));
    }

    @Test
    void aRunWithoutARegistry_refusesToStartAnythingNobodyWouldClose() {
        RecordingBus bus = new RecordingBus();

        ToolResult result = enabled(bus).execute(BACKGROUND, AgentTask.of("a strategy with no registry"));

        assertFalse(result.success());
        assertTrue(result.error().contains("not available in this run"), result.error());
        assertTrue(bus.delegated.isEmpty());
    }

    @Test
    void aRecipientThatAcceptsOnlyWaitedDelegation_isRefusedABackgroundStart() {
        RecordingBus bus = new RecordingBus();
        AgentDelegationTool tool = tool(bus, AsyncDelegation.defaults(), id -> AsyncSupport.FORBIDDEN);

        ToolResult result = tool.execute(BACKGROUND, callerWith(new BackgroundTasks(AsyncDelegation.defaults())));

        assertFalse(result.success());
        assertTrue(result.error().contains("accepts only waited delegation"), result.error());
        assertTrue(bus.delegated.isEmpty());
        assertTrue(tool.execute(WAITED, callerWith(new BackgroundTasks(AsyncDelegation.defaults()))).success(),
                "a waited call to it still works");
    }

    @Test
    void aRecipientThatRunsOnlyInTheBackground_isStartedAsOne_andTheModelIsTold() {
        RecordingBus bus = new RecordingBus();
        AgentDelegationTool tool = tool(bus, AsyncDelegation.defaults(), id -> AsyncSupport.REQUIRED);

        ToolResult result = tool.execute(WAITED, callerWith(new BackgroundTasks(AsyncDelegation.defaults())));

        assertTrue(result.success(), result.error());
        assertTrue(result.output().contains("runs only in the background"), result.output());
        assertEquals(1, bus.delegated.size());
        assertTrue(bus.requested.isEmpty());
    }

    @Test
    void aRecipientThatRunsOnlyInTheBackground_isRefusedToAnAgentThatCannotStartOne() {
        RecordingBus bus = new RecordingBus();
        AgentDelegationTool tool = tool(bus, null, id -> AsyncSupport.REQUIRED);

        ToolResult result = tool.execute(WAITED, AgentTask.of("caller"));

        assertFalse(result.success());
        assertTrue(result.error().contains("cannot start background tasks"), result.error());
        assertTrue(bus.requested.isEmpty() && bus.delegated.isEmpty());
    }

    @Test
    void theLimit_refusesTheStartThatDoesNotFit() {
        RecordingBus bus = new RecordingBus();
        BackgroundTasks tasks = new BackgroundTasks(new AsyncDelegation(1, 1000));
        AgentDelegationTool tool = tool(bus, new AsyncDelegation(1, 1000), id -> null);

        assertTrue(tool.execute(BACKGROUND, callerWith(tasks)).success());
        ToolResult second = tool.execute(BACKGROUND, callerWith(tasks));

        assertFalse(second.success());
        assertTrue(second.error().contains("limit 1") && second.error().contains("t-1"), second.error());
        assertEquals(1, bus.delegated.size());
    }

    @Test
    void aStartThatTheBusRefuses_givesItsSlotBack_andReportsLikeAWaitedCall() {
        RecordingBus bus = new RecordingBus();
        BackgroundTasks tasks = new BackgroundTasks(new AsyncDelegation(1, 1000));
        AgentDelegationTool tool = tool(bus, new AsyncDelegation(1, 1000), id -> null);
        bus.failToStart = new java.util.NoSuchElementException("nobody");

        ToolResult refused = tool.execute(BACKGROUND, callerWith(tasks));

        assertFalse(refused.success());
        assertEquals("Agent not found: worker", refused.error());
        bus.failToStart = null;
        assertTrue(tool.execute(BACKGROUND, callerWith(tasks)).success(),
                "the slot of the failed start must be free again");
    }

    @Test
    void aBusThatCannotStartInTheBackground_failsTheCallWithItsReason() {
        MessageBus legacy = new MessageBus() {
            @Override public void send(AgentMessage message) { }
            @Override public AgentMessage request(AgentMessage message, Duration timeout) {
                return AgentMessage.reply(message, message.recipientId(), "waited");
            }
        };

        ToolResult result = enabled(legacy).execute(BACKGROUND, callerWith(new BackgroundTasks(AsyncDelegation.defaults())));

        assertFalse(result.success());
        assertTrue(result.error().contains("cannot start a delegation in the background"), result.error());
    }

    @Test
    void theCallersRegistry_isNotHandedToTheDelegate_inEitherMode() {
        RecordingBus bus = new RecordingBus();
        BackgroundTasks tasks = new BackgroundTasks(AsyncDelegation.defaults());
        AgentDelegationTool tool = enabled(bus);

        tool.execute(WAITED, callerWith(tasks));
        tool.execute(BACKGROUND, callerWith(tasks));

        assertTrue(BackgroundTasks.from(bus.requested.get(0).runContext()).isEmpty());
        assertTrue(BackgroundTasks.from(bus.delegated.get(0).runContext()).isEmpty());
    }

    @Test
    void twoBackgroundTasksForTheSameAgent_getSessionsOfTheirOwn() {
        RecordingBus bus = new RecordingBus();
        BackgroundTasks tasks = new BackgroundTasks(AsyncDelegation.defaults());
        AgentDelegationTool tool = enabled(bus);

        tool.execute(BACKGROUND, callerWith(tasks));
        tool.execute(BACKGROUND, callerWith(tasks));

        SessionId first = bus.delegated.get(0).sessionId();
        SessionId second = bus.delegated.get(1).sessionId();
        assertNotNull(first);
        assertTrue(first.value().startsWith("caller-session::deleg::worker::"), first.value());
        assertNotEquals(first, second, "a shared session would turn the second task away as busy");
    }

    @Test
    void aWaitedDelegation_keepsItsDerivedSession() {
        RecordingBus bus = new RecordingBus();

        enabled(bus).execute(WAITED, callerWith(new BackgroundTasks(AsyncDelegation.defaults())));

        assertEquals(SessionId.of("caller-session::deleg::worker"), bus.requested.get(0).sessionId());
    }

    @Test
    void withoutALabel_theTaskIsNamedAfterTheStartOfItsText() {
        RecordingBus bus = new RecordingBus();
        String longTask = "x".repeat(60);

        ToolResult result = enabled(bus).execute(
                "{\"agent_id\":\"worker\",\"task\":\"" + longTask + "\",\"wait\":false}",
                callerWith(new BackgroundTasks(AsyncDelegation.defaults())));

        assertTrue(result.output().contains("\"label\":\"" + "x".repeat(40) + "…\""), result.output());
    }

    @Test
    void waitFalse_alsoWorksWrittenAsAString() {
        RecordingBus bus = new RecordingBus();

        enabled(bus).execute("{\"agent_id\":\"worker\",\"task\":\"do it\",\"wait\":\"false\"}",
                callerWith(new BackgroundTasks(AsyncDelegation.defaults())));

        assertEquals(1, bus.delegated.size());
    }

    @Test
    void awaitAndCancelTools_workThroughTheRunsRegistry_andFailCleanlyWithoutOne() {
        RecordingBus bus = new RecordingBus();
        BackgroundTasks tasks = new BackgroundTasks(AsyncDelegation.defaults());
        AgentTask caller = callerWith(tasks);
        enabled(bus).execute(BACKGROUND, caller);
        tasks.beginTurn();

        ToolResult listed = new AwaitTasksTool().execute("{\"timeout_seconds\":0}", caller);
        ToolResult cancelled = new CancelTaskTool().execute("{\"task_id\":\"t-1\"}", caller);

        assertTrue(listed.output().contains("t-1 \"sources\" (worker): still working"), listed.output());
        assertTrue(cancelled.output().contains("cancelled"), cancelled.output());
        assertFalse(new AwaitTasksTool().execute("{}", AgentTask.of("no registry")).success());
        assertFalse(new CancelTaskTool().execute("{\"task_id\":\"t-1\"}", AgentTask.of("no registry")).success());
        assertFalse(new CancelTaskTool().execute("{}", caller).success());
        assertFalse(new AwaitTasksTool().execute("{}").success());
        assertFalse(new CancelTaskTool().execute("{}").success());
    }

    @Test
    void theRegistryTools_describeThemselves() {
        assertEquals("await_tasks", new AwaitTasksTool().toolId());
        assertEquals("cancel_task", new CancelTaskTool().toolId());
        assertTrue(new AwaitTasksTool().argumentSchema().contains("timeout_seconds"));
        assertTrue(new CancelTaskTool().argumentSchema().contains("task_id"));
        assertFalse(new AwaitTasksTool().description().isBlank());
        assertFalse(new CancelTaskTool().description().isBlank());
    }

    @Test
    void theDescriptionsExample_namesNoTaskId_aModelCouldCopyAsIfItWereReal() {
        // A pilot run showed a model waiting on "t-1", "t-2", "t-3" taken from the example, having never started a task.
        String description = enabled(new RecordingBus()).description();

        assertFalse(description.matches("(?s).*\\bt-\\d.*"), description);
        assertTrue(description.contains("never make one up"), description);
    }

    @Test
    void await_readsTaskIds_whateverShapeTheModelSendsThem() {
        // As an array, as a JSON array written out as text (what a pilot model sent), and comma separated.
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        assertEquals(List.of("a", "b"), AwaitTasksTool.taskIds(mapper.createObjectNode().set("task_ids",
                mapper.createArrayNode().add("a").add("b")).path("task_ids")));
        assertEquals(List.of("t-1", "t-2"), AwaitTasksTool.taskIds(mapper.getNodeFactory().textNode("[\"t-1\", \"t-2\"]")));
        assertEquals(List.of("a", "b", "c"), AwaitTasksTool.taskIds(mapper.getNodeFactory().textNode("a, b c")));
        assertEquals(List.of(), AwaitTasksTool.taskIds(mapper.getNodeFactory().textNode("  ")));
        assertEquals(List.of(), AwaitTasksTool.taskIds(mapper.createObjectNode().path("task_ids")));
    }

    @Test
    void await_ofIdsGivenAsAString_saysUnknownTask_insteadOfNoTasksAreOpen() {
        AgentTask caller = callerWith(new BackgroundTasks(AsyncDelegation.defaults()));

        ToolResult result = new AwaitTasksTool().execute(
                "{\"task_ids\":\"[\\\"t-1\\\", \\\"t-2\\\"]\",\"timeout_seconds\":\"0\"}", caller);

        assertTrue(result.success());
        assertTrue(result.output().contains("unknown task t-1"), result.output());
        assertTrue(result.output().contains("unknown task t-2"), result.output());
        assertFalse(result.output().contains("No background tasks are open"), result.output());
    }
}
