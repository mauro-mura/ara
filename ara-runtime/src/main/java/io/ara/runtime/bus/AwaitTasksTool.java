package io.ara.runtime.bus;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.ara.core.agent.AgentTask;
import io.ara.core.tool.AraTool;
import io.ara.core.tool.ToolResult;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * {@code await_tasks}: waits for background sub-tasks to finish and returns their results.
 *
 * <p>Meant for the one moment a model has nothing else to do until a task ends. Results do
 * not need it to arrive: the loop delivers each finished task by itself before the next model
 * call (see {@link BackgroundTasks#deliverFinished}). With a zero timeout it only lists what
 * is still open, which is the cheap way to ask.
 *
 * <p>Registered only for an agent that may delegate in the background, together with the
 * {@code wait} parameter of {@code delegate_task}. Stateless; thread-safe.
 */
public final class AwaitTasksTool implements AraTool {

    public static final String TOOL_ID = "await_tasks";

    /** Waited when the model gives no timeout: long enough to be useful, short enough to be a decision. */
    static final int DEFAULT_TIMEOUT_SECONDS = 60;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public String toolId() {
        return TOOL_ID;
    }

    @Override
    public String description() {
        return "Waits for background tasks started with delegate_task and \"wait\": false, and returns "
                + "their results. Use it only when you have nothing else to do until a task ends: finished "
                + "tasks are delivered to you automatically. With timeout_seconds 0 it just lists the "
                + "tasks still open.";
    }

    @Override
    public String argumentSchema() {
        return """
                {
                  "type": "object",
                  "properties": {
                    "task_ids": {
                      "type": "array",
                      "items": { "type": "string" },
                      "description": "The tasks to wait for. Omit to wait for every task open now."
                    },
                    "timeout_seconds": {
                      "type": "integer",
                      "description": "How long to wait at most; 0 only lists the open tasks. Default 60."
                    }
                  }
                }""";
    }

    /**
     * The ids in {@code task_ids}, from an array — or from the string a model sometimes sends in its
     * place: a JSON array written out as text (<code>"[\"a\", \"b\"]"</code>), or ids separated by
     * commas. Reading such a field as "no ids" would answer "no tasks are open" to a model that named
     * tasks, hiding its mistake; this reads what it meant. Anything else yields no ids, which means
     * "every task open now".
     */
    static List<String> taskIds(JsonNode field) {
        List<String> ids = new ArrayList<>();
        if (field.isArray()) {
            field.forEach(id -> ids.add(id.asText()));
        } else if (field.isTextual() && !field.asText().isBlank()) {
            String text = field.asText().trim();
            try {
                JsonNode parsed = text.startsWith("[") ? MAPPER.readTree(text) : null;
                if (parsed != null && parsed.isArray()) {
                    parsed.forEach(id -> ids.add(id.asText()));
                    return ids;
                }
            } catch (Exception e) {
                // Not valid JSON after all; fall through to splitting on commas.
            }
            for (String id : text.split("[,\\s]+")) {
                String cleaned = id.replaceAll("^[\\[\"']+|[\\]\"']+$", "");
                if (!cleaned.isBlank()) ids.add(cleaned);
            }
        }
        return ids;
    }

    @Override
    public ToolResult execute(String argumentJson) {
        return ToolResult.failure(TOOL_ID, "await_tasks needs the run that started the tasks");
    }

    @Override
    public ToolResult execute(String argumentJson, AgentTask task) {
        BackgroundTasks tasks = BackgroundTasks.from(task.runContext()).orElse(null);
        if (tasks == null) {
            return ToolResult.failure(TOOL_ID, "No background tasks exist in this run.");
        }
        JsonNode args;
        try {
            args = argumentJson == null || argumentJson.isBlank() ? MAPPER.createObjectNode() : MAPPER.readTree(argumentJson);
        } catch (Exception e) {
            return ToolResult.failure(TOOL_ID, "Invalid arguments: " + e.getMessage());
        }
        List<String> ids = taskIds(args.path("task_ids"));
        long seconds = Math.max(0, args.path("timeout_seconds").asLong(DEFAULT_TIMEOUT_SECONDS));
        return ToolResult.success(TOOL_ID, tasks.await(ids, Duration.ofSeconds(seconds)));
    }
}
