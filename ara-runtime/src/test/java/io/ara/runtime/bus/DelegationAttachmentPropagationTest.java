package io.ara.runtime.bus;

import io.ara.core.agent.AgentConfig;
import io.ara.core.agent.AgentResponse;
import io.ara.core.agent.AgentState;
import io.ara.core.agent.AgentTask;
import io.ara.core.agent.AraAgent;
import io.ara.core.agent.RunContext;
import io.ara.core.agent.RunState;
import io.ara.core.bus.AgentMessage;
import io.ara.core.bus.MessageBus;
import io.ara.core.common.AgentId;
import io.ara.core.tool.ToolResult;
import io.ara.runtime.agent.AgentRegistry;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that a caller's {@link AgentTask#runContext()} (ADR-041 rev. 2) — e.g. an
 * opaque {@code SecurityContext} and its prompt variables — survives a {@code
 * delegate_task} hop and reaches the recipient agent's task. Covers both links of the
 * chain independently:
 * <ol>
 *   <li>{@link AgentDelegationTool} passes the calling task's {@code RunContext} onto
 *       the outgoing {@link AgentMessage}.</li>
 *   <li>{@link LocalMessageBus} seeds the task it hands to the recipient agent with the
 *       message's {@code RunContext}.</li>
 * </ol>
 */
class DelegationAttachmentPropagationTest {

    /** A stand-in for the caller's SecurityContext — identity is all the test checks. */
    private static final Object SECURITY_CONTEXT = new Object();

    @Test
    void delegationTool_propagatesCallerRunContextOntoTheOutgoingMessage() {
        AtomicReference<AgentMessage> sent = new AtomicReference<>();
        MessageBus capturingBus = new MessageBus() {
            @Override public void send(AgentMessage message) { sent.set(message); }
            @Override public AgentMessage request(AgentMessage message, Duration timeout) {
                sent.set(message);
                return AgentMessage.reply(message, message.recipientId(), "done");
            }
        };

        AgentDelegationTool tool = new AgentDelegationTool(capturingBus, "caller");
        AgentTask callerTask = AgentTask.of("parent task", Map.of("full_name", "Ada Lovelace"))
                .withAttachment("securityContext", SECURITY_CONTEXT);

        ToolResult result = tool.execute(
                "{\"agent_id\":\"worker\",\"task\":\"do the thing\"}", callerTask);

        assertTrue(result.content().contains("[worker]"), "delegation should succeed");
        assertNotNull(sent.get(), "a message must have been sent to the bus");
        assertSame(SECURITY_CONTEXT, sent.get().runContext().opaque("securityContext", Object.class),
                "the caller's securityContext must be carried on the delegated message");
        assertEquals("Ada Lovelace", sent.get().runContext().promptVar("full_name"),
                "the caller's context must be carried on the delegated message");
    }

    @Test
    void messageBus_seedsRecipientTaskWithTheMessageRunContext() {
        AtomicReference<AgentTask> received = new AtomicReference<>();

        AgentRegistry registry = new AgentRegistry();
        registry.register(new CapturingAgent(AgentId.of("worker"), received));

        LocalMessageBus bus = new LocalMessageBus(registry);
        RunContext callerRunContext = new RunContext(
                Map.of("full_name", "Ada Lovelace"), Map.of("securityContext", SECURITY_CONTEXT),
                RunState.noop());
        AgentMessage message = AgentMessage.of("caller", "worker", "do the thing", callerRunContext);

        bus.request(message, Duration.ofSeconds(5));

        assertNotNull(received.get(), "recipient must have been executed");
        assertSame(SECURITY_CONTEXT, received.get().runContext().opaque("securityContext", Object.class),
                "the recipient's task must carry the caller's securityContext");
        assertEquals("Ada Lovelace", received.get().runContext().promptVar("full_name"),
                "the recipient's task must carry the caller's context");
    }

    /** Minimal agent that records the task it was asked to execute and returns success. */
    private static final class CapturingAgent implements AraAgent {
        private final AgentId id;
        private final AtomicReference<AgentTask> received;

        CapturingAgent(AgentId id, AtomicReference<AgentTask> received) {
            this.id = id;
            this.received = received;
        }

        @Override public AgentId agentId() { return id; }
        @Override public AgentConfig config() {
            return AgentConfig.defaults().agentId(id).agentType("t").build();
        }
        @Override public AgentState currentState() { return AgentState.IDLE; }
        @Override public void terminate() { /* no-op */ }

        @Override
        public AgentResponse execute(AgentTask task) {
            received.set(task);
            return AgentResponse.success(task.taskId(), id, "ok", 1, 0, 0.0, Duration.ZERO, java.util.List.of());
        }
    }
}
