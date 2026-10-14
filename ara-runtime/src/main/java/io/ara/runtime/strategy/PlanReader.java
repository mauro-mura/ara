package io.ara.runtime.strategy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns the planner's reply into the list of step goals {@link PlanExecuteStrategy} executes.
 *
 * <p>Two shapes are accepted, tried in this order:
 * <ol>
 *   <li>a JSON object {@code {"steps":[{"id":"s1","goal":"...","dependsOn":[]}, ...]}} — what the
 *       planning prompt asks for, and what a client with native structured output is forced to
 *       produce;</li>
 *   <li>a numbered or bulleted list — what the strategy asked for before the plan was JSON, and
 *       what a model that ignores the JSON instruction usually writes anyway. It is a legitimate
 *       plan in another format, so it is accepted rather than rejected.</li>
 * </ol>
 *
 * <p>{@code id} and {@code dependsOn} are read only by {@link #readSteps(String)} — the shape
 * {@link PlanExecuteStrategy} uses to batch independent steps. {@link #read(String)} still
 * discards them exactly as before: steps run in the order they are written and, by default,
 * each sees everything before it; {@code readSteps} is the opt-in that exposes dependencies to
 * a caller that wants to run some of them together. A dependency graph that cannot be trusted
 * (a dangling or self-referencing id, a forward reference) degrades per-step to "no known
 * dependency" rather than rejecting the plan — see {@link PlanStep}.
 *
 * <p>An empty result means "unreadable": the reply is neither shape, or has no steps. Deciding
 * what to do about that is the caller's job — this class never invents a plan.
 *
 * <p>Stateless and thread-safe.
 */
final class PlanReader {

    /** Name given to the schema on providers that want one (OpenAI's {@code json_schema.name}). */
    static final String SCHEMA_NAME = "execution_plan";

    /**
     * JSON Schema of the plan, sent as the provider-native {@code response_format} when the client
     * supports it. All three step fields are listed as required: providers that enforce strict
     * schemas demand it, and a model that must always write {@code dependsOn} (an empty list when
     * the step needs nothing) is exactly what a later parallel mode wants to find.
     */
    static final String SCHEMA = """
            {
              "type": "object",
              "properties": {
                "steps": {
                  "type": "array",
                  "items": {
                    "type": "object",
                    "properties": {
                      "id":        { "type": "string" },
                      "goal":      { "type": "string" },
                      "dependsOn": { "type": "array", "items": { "type": "string" } }
                    },
                    "required": ["id", "goal", "dependsOn"],
                    "additionalProperties": false
                  }
                }
              },
              "required": ["steps"],
              "additionalProperties": false
            }""";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final Pattern LIST_ITEM = Pattern.compile(
            "(?m)^\\s*(?:\\d+[\\.)]|[-*])\\s+(.+?)\\s*$");

    private PlanReader() {
    }

    /** The step goals in the reply, in order; empty when the reply holds no readable plan. */
    static List<String> read(String reply) {
        return readSteps(reply).stream().map(PlanStep::goal).toList();
    }

    /**
     * One step of a JSON plan: its goal, and the <em>indices</em> (into the returned list, not
     * the model's own {@code id} strings) of the steps it depends on. {@code dependsOn} is
     * empty for a step read from the numbered/bulleted-list shape, which carries no dependency
     * information at all — the same "no known dependency" value a JSON step gets when its
     * {@code dependsOn} names an id this plan does not have (a step the model invented, or a
     * forward reference) or when it names itself: a malformed graph degrades to "depends on
     * nothing", which {@link PlanExecuteStrategy}'s batching reads as "cannot be grouped with
     * anything that has not finished yet" — never a reason to reject the plan or guess a
     * different id.
     */
    record PlanStep(String goal, Set<Integer> dependsOn) {
        PlanStep(String goal) {
            this(goal, Set.of());
        }
    }

    /**
     * Same reading as {@link #read(String)}, carrying each step's {@code dependsOn} resolved to
     * indices into the result. {@link #read(String)} is now a thin wrapper over this one that
     * keeps only the goals — unchanged behaviour, including that it still never reads
     * {@code dependsOn} for anything.
     *
     * <p>This is the one place {@code dependsOn} is actually read: {@link PlanExecuteStrategy}
     * uses it to batch steps whose dependencies are already satisfied, and nothing else in this
     * class or in {@code read(String)} is affected by its presence or absence.
     */
    static List<PlanStep> readSteps(String reply) {
        if (reply == null || reply.isBlank()) {
            return List.of();
        }
        List<PlanStep> fromJson = readJsonSteps(reply);
        return fromJson != null ? fromJson : readListSteps(reply);
    }

    /**
     * The steps of a JSON plan with dependencies resolved, or {@code null} when the reply is not
     * a JSON plan at all (so the caller falls back to the list shape). An empty list means "was
     * a JSON plan, with no usable step".
     */
    private static List<PlanStep> readJsonSteps(String reply) {
        int start = reply.indexOf('{');
        int end = reply.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return null;
        }
        JsonNode root;
        try {
            root = MAPPER.readTree(reply.substring(start, end + 1));
        } catch (Exception notJson) {
            return null;
        }
        JsonNode stepsNode = root.path("steps");
        if (!stepsNode.isArray()) {
            return null;
        }

        // First pass: goals and raw ids, in written order — needed before dependsOn can be
        // resolved to an index, since a step may depend on one written after it (ignored, per
        // the class javadoc, but still parseable rather than order-sensitive by accident).
        List<String> goals = new ArrayList<>();
        List<String> ids = new ArrayList<>();
        List<List<String>> rawDeps = new ArrayList<>();
        for (JsonNode stepNode : stepsNode) {
            String goal = stepNode.isObject() ? stepNode.path("goal").asText("") : stepNode.asText("");
            if (goal.isBlank()) {
                continue;
            }
            goals.add(goal.strip());
            String id = stepNode.isObject() ? stepNode.path("id").asText("") : "";
            ids.add(id);
            List<String> deps = new ArrayList<>();
            if (stepNode.isObject() && stepNode.path("dependsOn").isArray()) {
                stepNode.path("dependsOn").forEach(d -> deps.add(d.asText("")));
            }
            rawDeps.add(deps);
        }

        // id -> index, skipping blank/duplicate ids: a blank id cannot be depended on by
        // construction, and a duplicate's second occurrence would silently steal references
        // meant for the first — excluding both from the lookup means every dependency on either
        // simply does not resolve, which is the same "no known dependency" degradation as a
        // dangling or self reference, not a different failure mode to special-case.
        Map<String, Integer> idToIndex = new HashMap<>();
        Map<String, Integer> seenOnce = new HashMap<>();
        for (int i = 0; i < ids.size(); i++) {
            String id = ids.get(i);
            if (id.isBlank()) continue;
            if (seenOnce.containsKey(id)) {
                idToIndex.remove(id);
            } else {
                seenOnce.put(id, i);
                idToIndex.put(id, i);
            }
        }

        List<PlanStep> result = new ArrayList<>(goals.size());
        for (int i = 0; i < goals.size(); i++) {
            Set<Integer> resolved = new LinkedHashSet<>();
            for (String depId : rawDeps.get(i)) {
                Integer idx = idToIndex.get(depId);
                // A dangling id, a self-reference, or a forward reference (idx >= i: this plan's
                // steps run in written order, so "depends on a step not yet written" cannot be
                // honoured) all degrade to "not a known dependency" rather than failing the plan.
                if (idx != null && idx < i) {
                    resolved.add(idx);
                }
            }
            result.add(new PlanStep(goals.get(i), resolved));
        }
        return result;
    }

    /** The list/bullet shape carries no dependency information: every step depends on nothing. */
    private static List<PlanStep> readListSteps(String reply) {
        return readList(reply).stream().map(PlanStep::new).toList();
    }


    private static List<String> readList(String reply) {
        List<String> goals = new ArrayList<>();
        Matcher matcher = LIST_ITEM.matcher(reply);
        while (matcher.find()) {
            String goal = matcher.group(1).strip();
            if (!goal.isBlank()) {
                goals.add(goal);
            }
        }
        return goals;
    }
}
