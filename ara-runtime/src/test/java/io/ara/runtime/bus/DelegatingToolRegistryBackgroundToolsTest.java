package io.ara.runtime.bus;

import io.ara.core.agent.AsyncDelegation;
import io.ara.core.agent.AsyncSupport;
import io.ara.core.agent.DelegateStateAccess;
import io.ara.core.agent.SessionStore;
import io.ara.core.auth.ScopeSet;
import io.ara.core.bus.AgentMessage;
import io.ara.core.bus.MessageBus;
import io.ara.core.tool.AraTool;
import io.ara.core.tool.ToolRegistry;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code await_tasks} and {@code cancel_task} exist for an agent that may delegate in the
 * background and for no one else, and they follow {@code delegate_task} in and out of the
 * agent's enabled set.
 */
class DelegatingToolRegistryBackgroundToolsTest {

    private static final MessageBus BUS = new MessageBus() {
        @Override public void send(AgentMessage message) { }
        @Override public AgentMessage request(AgentMessage message, Duration timeout) {
            return AgentMessage.reply(message, message.recipientId(), "ok");
        }
    };

    private static ToolRegistry empty() {
        return new ToolRegistry() {
            @Override public List<AraTool> resolveEnabled(List<String> enabledToolIds) { return List.of(); }
            @Override public java.util.Optional<AraTool> findById(String toolId) { return java.util.Optional.empty(); }
            @Override public List<AraTool> all() { return List.of(); }
            @Override public io.ara.core.tool.ToolResult execute(String toolId, String argumentJson) {
                return io.ara.core.tool.ToolResult.failure(toolId, "unknown");
            }
        };
    }

    private static DelegatingToolRegistry registry(AsyncDelegation async) {
        return new DelegatingToolRegistry(empty(), BUS, "coordinator", Duration.ofSeconds(5),
                DelegateStateAccess.OVERLAY, SessionStore.noop(), ScopeSet.EMPTY, null, async,
                (Function<String, AsyncSupport>) id -> null);
    }

    private static List<String> ids(List<AraTool> tools) {
        return tools.stream().map(AraTool::toolId).toList();
    }

    @Test
    void anAgentThatMayDelegateInTheBackground_getsAllThreeTools() {
        DelegatingToolRegistry registry = registry(AsyncDelegation.defaults());

        assertEquals(List.of("delegate_task", "await_tasks", "cancel_task"), ids(registry.resolveEnabled(null)));
        assertEquals(List.of("delegate_task", "await_tasks", "cancel_task"), ids(registry.all()));
        assertTrue(registry.findById("await_tasks").isPresent());
        assertTrue(registry.findById("cancel_task").isPresent());
    }

    @Test
    void anAgentThatMayNot_getsOnlyDelegateTask() {
        DelegatingToolRegistry registry = registry(null);

        assertEquals(List.of("delegate_task"), ids(registry.resolveEnabled(null)));
        assertTrue(registry.findById("await_tasks").isEmpty());
        assertTrue(registry.findById("cancel_task").isEmpty());
    }

    @Test
    void theExtraTools_followDelegateTaskInTheEnabledList() {
        DelegatingToolRegistry registry = registry(AsyncDelegation.defaults());

        assertEquals(List.of(), ids(registry.resolveEnabled(List.of())));
        assertEquals(List.of(), ids(registry.resolveEnabled(List.of("something_else"))));
        assertEquals(List.of("delegate_task", "await_tasks", "cancel_task"),
                ids(registry.resolveEnabled(List.of("delegate_task"))));
    }

    @Test
    void theExtraTools_areNeverGatedForApproval_andHaveNoSpec() {
        DelegatingToolRegistry registry = registry(AsyncDelegation.defaults());

        assertTrue(registry.specFor("await_tasks").isEmpty());
        assertTrue(registry.approvalRequestFor("cancel_task", "{}", io.ara.core.agent.AgentTask.of("x")).isEmpty());
    }
}
