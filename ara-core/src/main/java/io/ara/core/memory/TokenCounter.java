package io.ara.core.memory;

/**
 * Estimates how many tokens a piece of text will cost against a working-memory token
 * budget — the value every eviction decision in {@code
 * io.ara.runtime.memory.SlidingWindowMemoryManager} is based on (ADR-0087).
 *
 * <p>Before this port existed, that class computed {@code chars / 4} inline and never
 * exposed the number as a swappable contract. {@link #approximate()} preserves exactly that
 * estimate — accurate within roughly 15% for Latin-script text, far worse for CJK and dense
 * JSON, where a real tokenizer can diverge from it by up to 4x. It stays the default so a
 * manager built without an explicit {@link TokenCounter} keeps behaving exactly as it did
 * before this port: wiring a real tokenizer (e.g. {@code JtokkitTokenCounter} in
 * {@code ara-adapters}) is an improvement to opt into, never a prerequisite.
 *
 * <p>Implementations must be thread-safe and side-effect-free: {@link #count} is called on
 * every working-memory append when a token budget is configured.
 */
@FunctionalInterface
public interface TokenCounter {

    /**
     * Counts the tokens {@code text} would cost.
     *
     * @param text the text to count; {@code null} or empty counts as {@code 0}
     * @return the estimated (or, for a real tokenizer, exact) token count; never negative
     */
    int count(String text);

    /**
     * The historic {@code chars / 4} fallback — see the interface javadoc for its accuracy
     * and why it remains the default.
     *
     * @return a {@link TokenCounter} that estimates one token per four characters
     */
    static TokenCounter approximate() {
        return text -> text == null || text.isEmpty() ? 0 : text.length() / 4;
    }
}
