package io.ara.runtime.bus;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.ara.core.agent.AgentTask;
import io.ara.core.tool.AraTool;
import io.ara.core.tool.ToolResult;

/**
 * {@code cancel_task}: stops a background sub-task started with {@code delegate_task} and
 * {@code "wait": false}. Cancelling is a request that the delegate honours at its next
 * cooperative check, like terminating a session: a model call already in flight finishes
 * and is still paid for.
 *
 * <p>Registered together with {@link AwaitTasksTool}. Stateless; thread-safe.
 */
public final class CancelTaskTool implements AraTool {

    public static final String TOOL_ID = "cancel_task";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public String toolId() {
        return TOOL_ID;
    }

    @Override
    public String description() {
        return "Stops a background task started with delegate_task and \"wait\": false. A task that "
                + "already finished is not stopped: its result is returned instead.";
    }

    @Override
    public String argumentSchema() {
        return """
                {
                  "type": "object",
                  "properties": {
                    "task_id": { "type": "string", "description": "The task to stop" }
                  },
                  "required": ["task_id"]
                }""";
    }

    @Override
    public ToolResult execute(String argumentJson) {
        return ToolResult.failure(TOOL_ID, "cancel_task needs the run that started the task");
    }

    @Override
    public ToolResult execute(String argumentJson, AgentTask task) {
        BackgroundTasks tasks = BackgroundTasks.from(task.runContext()).orElse(null);
        if (tasks == null) {
            return ToolResult.failure(TOOL_ID, "No background tasks exist in this run.");
        }
        JsonNode taskId;
        try {
            taskId = MAPPER.readTree(argumentJson).get("task_id");
        } catch (Exception e) {
            return ToolResult.failure(TOOL_ID, "Invalid arguments: " + e.getMessage());
        }
        if (taskId == null || !taskId.isTextual() || taskId.asText().isBlank()) {
            return ToolResult.failure(TOOL_ID, "Invalid arguments: missing required text field 'task_id'");
        }
        return ToolResult.success(TOOL_ID, tasks.cancel(taskId.asText()));
    }
}
