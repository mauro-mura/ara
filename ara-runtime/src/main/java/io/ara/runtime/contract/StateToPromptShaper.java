package io.ara.runtime.contract;

import io.ara.core.agent.AgentTask;
import io.ara.core.agent.RunState;
import io.ara.core.agent.processor.PromptShaper;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Appends a formatted block of a {@link RunState} to the system prompt (Agno {@code
 * add_session_state_to_context} parity) — the only sanctioned way to make state visible
 * to an LLM, since {@code RunState} is deliberately never auto-interpolated (see {@link
 * io.ara.core.agent.RunContext}'s javadoc on why {@code opaque} isn't either).
 *
 * <p>Two sources, selected by factory method: {@link #all()}/{@link #of(String...)}
 * project {@code RunContext.state()} (session-scoped); {@link #forUserMemory()}/{@link
 * #forUserMemory(String...)} project {@code RunContext.userMemory()} (cross-session,
 * ADR-043 rev. 3) instead — same rendering, different source and heading, so a reader
 * can tell at a glance which one a given prompt block came from.
 *
 * <p>Appends a rendered block rather than feeding {@code PromptTemplate}'s {@code
 * {key}} placeholders: {@link PromptShaper#shape(String, AgentTask)} hands each shaper
 * only the accumulated prompt string, not a mutable context the next shaper in the
 * chain could read from — there is no way for this shaper to make a value visible to
 * {@code PromptTemplate}'s resolver short of {@code PromptTemplate} reading state
 * directly, which would defeat the leak-safety rule above. An appended block also
 * matches what {@code add_session_state_to_context} actually does in Agno: a whole-blob
 * context injection, not per-key templating.
 */
public final class StateToPromptShaper implements PromptShaper {

    private final Set<String> keys; // null = project the entire snapshot
    private final Function<AgentTask, RunState> source;
    private final String heading;

    private StateToPromptShaper(Set<String> keys, Function<AgentTask, RunState> source, String heading) {
        this.keys    = keys;
        this.source  = source;
        this.heading = heading;
    }

    /** Projects the entire session state snapshot ({@code RunContext.state()}). */
    public static StateToPromptShaper all() {
        return new StateToPromptShaper(null, t -> t.runContext().state(), "Current session state");
    }

    /** Projects only the named session-state keys, in the given order — absent keys are silently skipped. */
    public static StateToPromptShaper of(String... keys) {
        Objects.requireNonNull(keys, "keys must not be null");
        return new StateToPromptShaper(Set.of(keys), t -> t.runContext().state(), "Current session state");
    }

    /** Projects the entire cross-session user memory snapshot ({@code RunContext.userMemory()}, ADR-043 rev. 3). */
    public static StateToPromptShaper forUserMemory() {
        return new StateToPromptShaper(null, t -> t.runContext().userMemory(), "What you remember about this user");
    }

    /** Projects only the named user-memory keys, in the given order — absent keys are silently skipped. */
    public static StateToPromptShaper forUserMemory(String... keys) {
        Objects.requireNonNull(keys, "keys must not be null");
        return new StateToPromptShaper(Set.of(keys), t -> t.runContext().userMemory(), "What you remember about this user");
    }

    @Override
    public String shape(String systemPrompt, AgentTask task) {
        Map<String, Object> snapshot = source.apply(task).snapshot();
        Map<String, Object> selected = keys == null
                ? snapshot
                : snapshot.entrySet().stream()
                        .filter(e -> keys.contains(e.getKey()))
                        .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue,
                                (a, b) -> a, LinkedHashMap::new));

        if (selected.isEmpty()) {
            return systemPrompt;
        }

        String block = selected.entrySet().stream()
                .map(e -> "- " + e.getKey() + ": " + e.getValue())
                .collect(Collectors.joining("\n"));
        return systemPrompt + "\n\n" + heading + ":\n" + block;
    }
}
