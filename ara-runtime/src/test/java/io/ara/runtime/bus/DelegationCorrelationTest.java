package io.ara.runtime.bus;

import io.ara.core.agent.AgentTask;
import io.ara.core.agent.DelegatedBy;
import io.ara.core.agent.RunContext;
import io.ara.core.agent.SessionId;
import io.ara.core.bus.AgentMessage;
import io.ara.core.bus.MessageBus;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * A delegation keeps the caller's workflow id and tells the delegate who its direct parent is.
 * The workflow id is the caller's run id as the trace defines it: correlation id, else session
 * id, else task id — so a caller that set nothing still has its delegates join its run.
 */
class DelegationCorrelationTest {

    private static final String CALL = "{\"agent_id\":\"worker\",\"task\":\"do the thing\"}";

    /** Delegates once on behalf of {@code callerTask} and returns the message that reached the bus. */
    private static AgentMessage delegate(AgentTask callerTask) {
        AtomicReference<AgentMessage> sent = new AtomicReference<>();
        MessageBus bus = new MessageBus() {
            @Override public void send(AgentMessage message) { sent.set(message); }
            @Override public AgentMessage request(AgentMessage message, Duration timeout) {
                sent.set(message);
                return AgentMessage.reply(message, message.recipientId(), "done");
            }
        };
        AgentDelegationTool tool = new AgentDelegationTool(bus, "coordinator");
        if (callerTask == null) {
            tool.execute(CALL);
        } else {
            tool.execute(CALL, callerTask);
        }
        assertNotNull(sent.get(), "the delegation must reach the bus");
        return sent.get();
    }

    @Test
    void theCallersCorrelationId_isKept() {
        AgentTask caller = AgentTask.of("plan the trip", Map.of(), "wf-1", "operator");

        assertEquals("wf-1", delegate(caller).correlationId());
    }

    @Test
    void withoutACorrelationId_theCallersSessionIsTheWorkflow() {
        AgentTask caller = AgentTask.of("plan the trip").withSessionId(SessionId.of("sess-1"));

        assertEquals("sess-1", delegate(caller).correlationId());
    }

    @Test
    void withNeither_theCallersTaskIsTheWorkflow() {
        AgentTask caller = AgentTask.of("plan the trip");

        assertEquals(caller.taskId(), delegate(caller).correlationId());
    }

    @Test
    void withoutACallingTask_eachDelegationStillGetsAFreshId() {
        String first = delegate(null).correlationId();
        String second = delegate(null).correlationId();

        assertNotNull(first);
        assertNotEquals(first, second);
    }

    @Test
    void theDelegateLearnsItsDirectParent() {
        AgentTask caller = AgentTask.of("plan the trip");

        DelegatedBy parent = delegate(caller).runContext().opaque(RunContext.DELEGATED_BY_KEY, DelegatedBy.class);

        assertEquals(new DelegatedBy("coordinator", caller.taskId()), parent);
    }

    @Test
    void anInheritedParent_isReplaced_notPassedOn() {
        // The caller was itself delegated by "planner": its delegate must see the caller, not "planner".
        AgentTask caller = AgentTask.of("plan the trip")
                .withRunContext(RunContext.empty().withOpaque(RunContext.DELEGATED_BY_KEY, new DelegatedBy("planner", "t-0")));

        DelegatedBy parent = delegate(caller).runContext().opaque(RunContext.DELEGATED_BY_KEY, DelegatedBy.class);

        assertEquals(new DelegatedBy("coordinator", caller.taskId()), parent);
    }

    @Test
    void withoutACallingTask_noParentIsNamed() {
        assertNull(delegate(null).runContext().opaque(RunContext.DELEGATED_BY_KEY, DelegatedBy.class));
    }
}
