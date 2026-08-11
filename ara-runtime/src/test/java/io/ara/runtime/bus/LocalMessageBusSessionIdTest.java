package io.ara.runtime.bus;

import io.ara.core.agent.AgentConfig;
import io.ara.core.agent.AgentResponse;
import io.ara.core.agent.AgentState;
import io.ara.core.agent.AgentTask;
import io.ara.core.agent.AraAgent;
import io.ara.core.agent.SessionId;
import io.ara.core.bus.AgentMessage;
import io.ara.core.common.AgentId;
import io.ara.runtime.agent.AgentRegistry;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies how {@link LocalMessageBus#request} resolves the {@link SessionId} the
 * recipient's task runs under: a set {@link AgentMessage#sessionId()} is propagated
 * as-is; an absent one now gets a bus-generated ephemeral id instead of staying
 * {@code null} — needed so a timed-out or interrupted {@code request()} has a session
 * to target when it cancels the recipient (see {@code LocalMessageBusCancellationTest}).
 */
class LocalMessageBusSessionIdTest {

    /** Captures the task it received and echoes it back as the response content. */
    private static final class CapturingAgent implements AraAgent {
        private final AgentId id;
        private final AtomicReference<AgentTask> received;

        CapturingAgent(String id, AtomicReference<AgentTask> received) {
            this.id = AgentId.of(id);
            this.received = received;
        }

        @Override public AgentId agentId() { return id; }
        @Override public AgentConfig config() { return AgentConfig.defaults().agentId(id).agentType("t").build(); }
        @Override public AgentState currentState() { return AgentState.IDLE; }
        @Override public AgentResponse execute(AgentTask task) {
            received.set(task);
            return AgentResponse.success(task.taskId(), id, "ok", 1, 0, 0.0, Duration.ZERO, java.util.List.of());
        }
        @Override public void terminate() { }
    }

    @Test
    void request_propagatesASetSessionId_toTheRecipientsTask() {
        AgentRegistry registry = new AgentRegistry();
        AtomicReference<AgentTask> received = new AtomicReference<>();
        registry.register(new CapturingAgent("worker", received));
        LocalMessageBus bus = new LocalMessageBus(registry);

        SessionId sessionId = SessionId.of("caller-session::deleg::worker");
        AgentMessage request = AgentMessage.of("caller", "worker", "do it",
                io.ara.core.agent.RunContext.empty(), sessionId);

        bus.request(request, Duration.ofSeconds(5));

        assertEquals(sessionId, received.get().sessionId());
    }

    @Test
    void request_withNoSessionId_assignsAFreshEphemeralOne() {
        AgentRegistry registry = new AgentRegistry();
        AtomicReference<AgentTask> received = new AtomicReference<>();
        registry.register(new CapturingAgent("worker", received));
        LocalMessageBus bus = new LocalMessageBus(registry);

        AgentMessage request = AgentMessage.of("caller", "worker", "do it");

        bus.request(request, Duration.ofSeconds(5));

        // Not null: request() must always have a SessionId to target if it later needs to
        // cancel the recipient (timeout or interruption) — see toTask()'s javadoc.
        assertNotNull(received.get().sessionId());
    }
}
