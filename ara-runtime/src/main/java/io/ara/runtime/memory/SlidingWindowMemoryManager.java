package io.ara.runtime.memory;

import io.ara.core.media.MediaRef;
import io.ara.core.media.MediaTypes.MediaKind;
import io.ara.core.memory.MemoryEntry;
import io.ara.core.memory.ToolCallMetadata;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * Token-budget-aware {@link io.ara.core.memory.MemoryManager} that evicts working-memory
 * entries when the estimated token count exceeds {@code maxTokens}.
 *
 * <h2>Token estimation</h2>
 * Uses a char-based approximation for text: {@code tokens ≈ chars / 4}. This matches
 * GPT-family tokenisers within ~15 % for Latin-script text — accurate enough for budget
 * enforcement.
 *
 * <p>Media on an entry adds a <em>flat constant per category</em>, not a function of the
 * payload size. That is a deliberately coarse choice, and it is safe because the collapse it
 * might otherwise have to defend against cannot happen: an entry holds a {@code MediaRef},
 * not bytes, so its character count is negligible whatever a document actually costs, and the
 * window can no longer empty itself trying to fit one. The constants therefore only need to
 * keep the estimate in the right order of magnitude, and a constant is far easier to tune and
 * to test than a size curve fitted to no measurement.
 *
 * <h2>Eviction</h2>
 * Controlled by {@link EvictionPolicy}:
 * <ul>
 *   <li>{@code DROP_OLDEST} — remove from head until budget is met</li>
 *   <li>{@code DROP_MIDDLE} — preserve first N and last N entries, drop the middle</li>
 *   <li>{@code SUMMARIZE} — reserved; degrades silently to {@code DROP_MIDDLE}</li>
 * </ul>
 */
public final class SlidingWindowMemoryManager extends AbstractMemoryManager {

    private static final Logger log = LoggerFactory.getLogger(SlidingWindowMemoryManager.class);

    private static final int CHARS_PER_TOKEN = 4;
    private static final int ANCHOR_COUNT    = 2;

    /**
     * What one attachment is charged against the budget, by category. Chosen on the high side
     * of what providers actually bill so the estimate errs toward evicting early rather than
     * overflowing the model's context.
     *
     * <p><b>Known limitation, stated rather than hidden:</b> a flat constant cannot be an
     * over-estimate for every payload. A very large text file inlined into the prompt will
     * cost more than {@code TEXT} says, because that is the one category whose real token
     * count scales directly with its bytes. A size-derived figure was rejected on purpose —
     * see the class javadoc — so the guard against that case is a quantitative cap on
     * attachment size before the task runs ({@code MediaValidator}), not a cleverer estimate
     * here. If a deployment routinely attaches multi-megabyte text, raise {@code TEXT}.
     */
    private static final Map<MediaKind, Integer> TOKENS_PER_MEDIA = Map.of(
            MediaKind.IMAGE,    1_500,
            MediaKind.DOCUMENT, 6_000,
            MediaKind.TEXT,     4_000
    );

    private final int            maxTokens;
    private final EvictionPolicy policy;

    /**
     * @param maxTokens token budget for working memory (0 = unlimited)
     * @param policy    eviction strategy applied when budget is exceeded
     */
    public SlidingWindowMemoryManager(int maxTokens, EvictionPolicy policy) {
        if (maxTokens < 0) throw new IllegalArgumentException("maxTokens must be >= 0");
        this.maxTokens = maxTokens;
        this.policy    = policy != null ? policy : EvictionPolicy.DROP_MIDDLE;
    }

    @Override
    public void appendToWorkingMemory(String role, String content) {
        working.add(MemoryEntry.of(role, content));
        if (maxTokens > 0) evictIfNeeded();
    }

    @Override
    public void appendToWorkingMemory(String role, String content, ToolCallMetadata metadata) {
        working.add(MemoryEntry.of(role, content, metadata));
        if (maxTokens > 0) evictIfNeeded();
    }

    @Override
    public void appendToWorkingMemory(String role, String content, java.util.List<MediaRef> media) {
        working.add(MemoryEntry.of(role, content, media));
        if (maxTokens > 0) evictIfNeeded();
    }

    // ── Eviction ──────────────────────────────────────────────────────────────

    private void evictIfNeeded() {
        while (estimatedTokens() > maxTokens && working.size() > 1) {
            switch (policy) {
                case DROP_OLDEST -> evictOldest();
                case DROP_MIDDLE -> evictMiddle();
                case SUMMARIZE   -> evictSummarize();
            }
        }
    }

    private void evictOldest() {
        if (working.isEmpty()) return;
        int[] bounds = toolCallGroupBounds(0);
        removeRange(bounds[0], bounds[1]);
    }

    private void evictMiddle() {
        int size = working.size();
        if (size <= ANCHOR_COUNT * 2) {
            evictOldest();
            return;
        }
        int[] bounds = toolCallGroupBounds(ANCHOR_COUNT);
        removeRange(bounds[0], bounds[1]);
    }

    private void evictSummarize() {
        log.warn("SlidingWindowMemoryManager: SUMMARIZE policy not implemented — degrading to DROP_MIDDLE");
        evictMiddle();
    }

    /**
     * Returns the {@code [start, end)} range of the atomic "tool-call group" containing
     * {@code index}: one {@code "assistant_tool_call"}/{@code "assistant_tool_calls"} header
     * entry plus every {@code "tool"}-role result that immediately follows it.
     *
     * <p>{@code ReactStrategy}/{@code PlanExecuteStrategy} always write such a group as
     * consecutive entries with nothing interleaved. Evicting only part of one — e.g. the
     * header but not its result, or a result but not its header — leaves an orphaned {@code
     * "tool"} message with no preceding tool call once {@code ToolConversionUtils
     * .toNativeAwareChatMessage} reconstructs it natively; real providers (OpenAI) reject
     * that outright with a 400. For any entry outside such a group, the range is just
     * {@code [index, index + 1)} — unchanged single-entry eviction.
     */
    private int[] toolCallGroupBounds(int index) {
        int start = index;
        if ("tool".equals(working.get(start).role())) {
            while (start > 0 && "tool".equals(working.get(start - 1).role())) start--;
            if (start > 0 && isToolCallHeader(working.get(start - 1).role())) start--;
        }

        int end = start + 1;
        if (isToolCallHeader(working.get(start).role())) {
            while (end < working.size() && "tool".equals(working.get(end).role())) end++;
        }
        return new int[]{start, end};
    }

    private static boolean isToolCallHeader(String role) {
        return "assistant_tool_call".equals(role) || "assistant_tool_calls".equals(role);
    }

    private void removeRange(int fromInclusive, int toExclusive) {
        for (int i = toExclusive - 1; i >= fromInclusive; i--) {
            working.remove(i);
        }
    }

    private int estimatedTokens() {
        int chars = working.stream()
                .mapToInt(e -> (e.role()    != null ? e.role().length()    : 0)
                             + (e.content() != null ? e.content().length() : 0))
                .sum();
        int mediaTokens = working.stream()
                .flatMap(e -> e.media().stream())
                .mapToInt(SlidingWindowMemoryManager::tokensFor)
                .sum();
        return chars / CHARS_PER_TOKEN + mediaTokens;
    }

    private static int tokensFor(MediaRef ref) {
        return TOKENS_PER_MEDIA.get(ref.kind());
    }
}
