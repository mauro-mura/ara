package io.ara.runtime.agent;

import io.ara.core.agent.AgentCard;
import io.ara.core.agent.AgentConfig;
import io.ara.core.agent.AgentResponse;
import io.ara.core.agent.AgentState;
import io.ara.core.agent.AgentTask;
import io.ara.core.agent.AraAgent;
import io.ara.core.common.AgentId;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/** Focused on {@link AgentRegistry#replace} — register/deregister/findById are exercised indirectly elsewhere. */
class AgentRegistryTest {

    @Test
    void replace_withNoPreviousAgent_behavesLikeRegister() {
        AgentRegistry registry = new AgentRegistry();
        AraAgent agent = stubAgent("a", "t");

        AraAgent previous = registry.replace(agent);

        assertNull(previous);
        assertSame(agent, registry.findById(AgentId.of("a")).orElseThrow());
        assertEquals(1, registry.count());
    }

    @Test
    void replace_withExistingAgent_returnsItAndInstallsTheNewOne() {
        AgentRegistry registry = new AgentRegistry();
        AraAgent oldAgent = stubAgent("a", "old-type");
        AraAgent newAgent = stubAgent("a", "new-type");
        registry.register(oldAgent);

        AraAgent previous = registry.replace(newAgent);

        assertSame(oldAgent, previous);
        assertSame(newAgent, registry.findById(AgentId.of("a")).orElseThrow());
        assertEquals(1, registry.count(), "replace must not leave both agents registered under the same id");
    }

    @Test
    void replace_neverThrowsOnDuplicateId_unlikeRegister() {
        AgentRegistry registry = new AgentRegistry();
        registry.register(stubAgent("a", "t"));

        // register() would throw IllegalArgumentException here — replace() must not.
        assertDoesNotThrow(() -> registry.replace(stubAgent("a", "t2")));
    }

    @Test
    void replace_doesNotTerminateTheDisplacedAgent() {
        AgentRegistry registry = new AgentRegistry();
        AtomicBoolean terminated = new AtomicBoolean(false);
        AraAgent oldAgent = new AraAgent() {
            final AgentId id = AgentId.of("a");
            final AgentConfig cfg = AgentConfig.defaults().agentId(id).agentType("old-type").build();
            @Override public AgentId agentId() { return id; }
            @Override public AgentConfig config() { return cfg; }
            @Override public AgentState currentState() { return AgentState.IDLE; }
            @Override public AgentResponse execute(AgentTask task) {
                return AgentResponse.success(task.taskId(), id, "ok", 1, 0, 0, Duration.ofMillis(1), List.of());
            }
            @Override public void terminate() { terminated.set(true); }
        };
        registry.register(oldAgent);

        registry.replace(stubAgent("a", "new-type"));

        assertFalse(terminated.get(),
                "replace() must leave the displaced agent's fate to the caller, never terminate it itself");
    }

    // ── cards() ──────────────────────────────────────────────────────────────

    @Test
    void cards_isEmpty_whenNoAgentsAreRegistered() {
        AgentRegistry registry = new AgentRegistry();
        assertTrue(registry.cards().isEmpty());
    }

    @Test
    void cards_returnsOneCardPerRegisteredAgent() {
        AgentRegistry registry = new AgentRegistry();
        registry.register(stubAgent("a", "researcher"));
        registry.register(stubAgent("b", "writer"));

        List<AgentCard> cards = registry.cards();

        assertEquals(2, cards.size());
        Set<String> ids = cards.stream().map(c -> c.agentId().value()).collect(Collectors.toSet());
        assertEquals(Set.of("a", "b"), ids);
    }

    @Test
    void cards_omitsDeregisteredAgents() {
        AgentRegistry registry = new AgentRegistry();
        registry.register(stubAgent("a", "researcher"));
        registry.deregister(AgentId.of("a"));

        assertTrue(registry.cards().isEmpty());
    }

    /**
     * {@link AgentRegistry#cards()} is documented to build each card fresh at call time,
     * not to cache a snapshot taken at {@link AgentRegistry#register}. This proves it: a
     * stub agent whose {@code config()} changes after registration must show the new
     * value on the next {@code cards()} call, without re-registering.
     */
    @Test
    void cards_reflectsTheAgentsCurrentConfig_notASnapshotFromRegistration() {
        AgentRegistry registry = new AgentRegistry();
        MutableConfigAgent agent = new MutableConfigAgent("a", "1.0.0");
        registry.register(agent);

        // Call cards() once *before* mutating — a caching implementation (e.g.
        // computeIfAbsent on first read) would capture "1.0.0" right here, and a
        // version-oblivious test that only calls cards() once, after the mutation, would
        // never observe the difference: the very first call already sees the new value.
        assertEquals("1.0.0", registry.cards().get(0).version());

        agent.setVersion("2.0.0");

        AgentCard card = registry.cards().get(0);
        assertEquals("2.0.0", card.version(),
                "cards() must build from the agent's current config on every call, not one cached at the first call");
    }

    // ── Stubs ─────────────────────────────────────────────────────────────────

    /**
     * An {@link AraAgent} whose {@link #config()} can be swapped after construction —
     * {@code AgentCard.version()} carries the identity's {@code version} straight
     * through, making it a cheap, visible signal of which config a given card was built
     * from.
     */
    private static final class MutableConfigAgent implements AraAgent {
        private final AgentId id;
        private volatile AgentConfig cfg;

        MutableConfigAgent(String id, String initialVersion) {
            this.id  = AgentId.of(id);
            this.cfg = AgentConfig.defaults().agentId(this.id).agentType("t").version(initialVersion).build();
        }

        void setVersion(String version) {
            this.cfg = AgentConfig.defaults().agentId(id).agentType("t").version(version).build();
        }

        @Override public AgentId agentId() { return id; }
        @Override public AgentConfig config() { return cfg; }
        @Override public AgentState currentState() { return AgentState.IDLE; }
        @Override public AgentResponse execute(AgentTask task) {
            return AgentResponse.success(task.taskId(), id, "ok", 1, 0, 0, Duration.ofMillis(1), List.of());
        }
        @Override public void terminate() {}
    }

    private static AraAgent stubAgent(String id, String type) {
        AgentId agentId = AgentId.of(id);
        AgentConfig cfg = AgentConfig.defaults().agentId(agentId).agentType(type).build();
        return new AraAgent() {
            @Override public AgentId agentId() { return agentId; }
            @Override public AgentConfig config() { return cfg; }
            @Override public AgentState currentState() { return AgentState.IDLE; }
            @Override public AgentResponse execute(AgentTask task) {
                return AgentResponse.success(task.taskId(), agentId, "ok", 1, 0, 0, Duration.ofMillis(1), List.of());
            }
            @Override public void terminate() {}
        };
    }
}
