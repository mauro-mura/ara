package io.ara.runtime.bus;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.ara.core.bus.AgentMessage;
import io.ara.core.bus.Delegation;
import io.ara.core.bus.MessageBus;
import io.ara.core.tool.ToolResult;

/**
 * The start of one background delegation: take a slot, start the delegate, record it, and say
 * so. Kept apart from {@link AgentDelegationTool}, which only decides <em>whether</em> a
 * delegation is a background one; this is the part that differs between the two modes.
 *
 * <p>Stateless; thread-safe.
 */
final class BackgroundStart {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private BackgroundStart() {}

    /**
     * Starts {@code message} as a background task of {@code tasks}.
     *
     * <p>The slot is taken <em>before</em> the bus is called and given back if the bus throws:
     * starting can park on a human approval, which must not happen under the registry's lock,
     * and two parallel calls of one turn must not both pass a limit that only has room for one.
     *
     * @param notice a sentence to put in the answer, or {@code null}
     * @return the {@code task_id} answer on success; on failure the same {@link ToolResult} a
     *         waited delegation would give for the same bus exception
     */
    static ToolResult start(MessageBus bus, BackgroundTasks tasks, AgentMessage message, String label,
                            String notice) {
        String refusal = tasks.reserve();
        if (refusal != null) {
            return ToolResult.failure(AgentDelegationTool.TOOL_ID, refusal);
        }
        Delegation delegation;
        try {
            delegation = bus.delegate(message);
        } catch (RuntimeException e) {
            tasks.unreserve();
            return AgentDelegationTool.failureFor(message.recipientId(), e);
        }
        tasks.register(label, message.recipientId(), delegation);
        return ToolResult.success(AgentDelegationTool.TOOL_ID,
                started(delegation.taskId(), label, message.recipientId(), notice));
    }

    private static String started(String taskId, String label, String agentId, String notice) {
        ObjectNode answer = MAPPER.createObjectNode();
        answer.put("task_id", taskId);
        answer.put("label", label);
        answer.put("agent_id", agentId);
        answer.put("status", "working");
        if (notice != null) {
            answer.put("notice", notice);
        }
        return answer.toString();
    }
}
