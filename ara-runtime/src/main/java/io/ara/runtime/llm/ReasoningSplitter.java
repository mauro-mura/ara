package io.ara.runtime.llm;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Splits a model's raw output into the reasoning it wrote inline and the text meant for the user,
 * one chunk at a time. Package-private; one instance per model call, not thread-safe.
 *
 * <p><b>Why a state machine over markers.</b> The markers are literal strings, and a stream can cut
 * one anywhere ({@code "<thi"} then {@code "nk>"}). A regular expression over the whole text works
 * once the answer is complete, but cannot say what is safe to show <em>now</em>. So the splitter
 * holds back the longest tail of the buffer that could still become a marker, and releases
 * everything before it. The held tail is never more than the longest marker minus one character,
 * which keeps the delay of a streamed answer to a few characters.
 *
 * <p><b>What a marker does.</b> Each marker moves the machine to the reasoning mode, to the text
 * mode, or is only dropped. Anything that is not a marker is written to the current mode as it is.
 * A text that contains none of the markers comes out unchanged (apart from the leading blank
 * space, see below). A reasoning region that never closes stays reasoning to the end: the model was
 * cut off while thinking, and showing half a chain of thought as an answer is worse than showing
 * none.
 *
 * <p><b>Leading blank space.</b> Models put a blank line between the closing marker and the answer.
 * Blank characters are held back until the first visible character of the text, then dropped; a
 * text that is only blank stays empty.
 *
 * <p><b>Discarded alternative:</b> detecting the format from the first characters. A model that
 * answers directly without thinking would then be misread as "no format", and the next call could
 * differ. The format is chosen by whoever wraps the client, who knows the endpoint.
 */
final class ReasoningSplitter {

    private enum Action { TO_REASONING, TO_TEXT }

    private record Marker(String literal, Action action) {}

    private static final List<Marker> THINK_MARKERS = List.of(
            new Marker("<think>", Action.TO_REASONING),
            new Marker("</think>", Action.TO_TEXT));

    private static final List<Marker> HARMONY_MARKERS = List.of(
            new Marker("<|channel|>analysis<|message|>", Action.TO_REASONING),
            new Marker("<|channel|>final<|message|>", Action.TO_TEXT),
            new Marker("<|end|>", Action.TO_TEXT),
            new Marker("<|return|>", Action.TO_TEXT),
            new Marker("<|start|>assistant", Action.TO_TEXT));

    private final List<Marker> markers = new ArrayList<>();
    private final StringBuilder held = new StringBuilder();
    private final StringBuilder reasoning = new StringBuilder();
    private boolean inReasoning;
    private boolean textStarted;

    ReasoningSplitter(Set<ReasoningExtractingLlmClient.Style> styles) {
        if (styles.isEmpty()) {
            throw new IllegalArgumentException("at least one style is required");
        }
        if (styles.contains(ReasoningExtractingLlmClient.Style.THINK_TAGS)) markers.addAll(THINK_MARKERS);
        if (styles.contains(ReasoningExtractingLlmClient.Style.HARMONY)) markers.addAll(HARMONY_MARKERS);
    }

    /** Feeds one chunk; returns the text that is safe to show now. Reasoning accumulates in {@link #reasoning()}. */
    String feed(String chunk) {
        held.append(chunk);
        StringBuilder visible = new StringBuilder();
        while (true) {
            int at = -1;
            Marker found = null;
            for (Marker m : markers) {
                int i = held.indexOf(m.literal());
                if (i >= 0 && (at < 0 || i < at)) {
                    at = i;
                    found = m;
                }
            }
            if (found == null) {
                break;
            }
            emit(held.substring(0, at), visible);
            held.delete(0, at + found.literal().length());
            inReasoning = found.action() == Action.TO_REASONING;
        }
        int keep = longestPossibleMarkerPrefixAtTail();
        emit(held.substring(0, held.length() - keep), visible);
        held.delete(0, held.length() - keep);
        return visible.toString();
    }

    /** The end of the stream: whatever was held back was not a marker after all. */
    String finish() {
        StringBuilder visible = new StringBuilder();
        emit(held.toString(), visible);
        held.setLength(0);
        return visible.toString();
    }

    String reasoning() {
        return reasoning.toString().strip();
    }

    private void emit(String piece, StringBuilder visible) {
        if (piece.isEmpty()) {
            return;
        }
        if (inReasoning) {
            reasoning.append(piece);
            return;
        }
        String shown = textStarted ? piece : piece.stripLeading();
        if (!shown.isEmpty()) {
            textStarted = true;
            visible.append(shown);
        }
    }

    private int longestPossibleMarkerPrefixAtTail() {
        int longest = 0;
        for (Marker m : markers) {
            int max = Math.min(m.literal().length() - 1, held.length());
            for (int len = max; len > longest; len--) {
                if (m.literal().startsWith(held.substring(held.length() - len))) {
                    longest = len;
                    break;
                }
            }
        }
        return longest;
    }
}
