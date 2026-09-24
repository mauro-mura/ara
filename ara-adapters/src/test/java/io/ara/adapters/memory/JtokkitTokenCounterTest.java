package io.ara.adapters.memory;

import com.knuddels.jtokkit.api.EncodingType;
import io.ara.core.memory.TokenCounter;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ADR-0087: {@link JtokkitTokenCounter} counts real BPE tokens, not an approximation — so
 * unlike a fuzzy-accuracy assertion against an external ground truth, these tests check the
 * properties a real tokenizer must have and the gap against {@link TokenCounter#approximate()}
 * it exists to close, especially on non-Latin text where {@code chars / 4} is worst.
 */
class JtokkitTokenCounterTest {

    @Test
    void nullOrEmpty_countsAsZero() {
        JtokkitTokenCounter counter = new JtokkitTokenCounter();

        assertEquals(0, counter.count(null));
        assertEquals(0, counter.count(""));
    }

    @Test
    void isDeterministic() {
        JtokkitTokenCounter counter = new JtokkitTokenCounter();
        String text = "The quick brown fox jumps over the lazy dog.";

        assertEquals(counter.count(text), counter.count(text));
    }

    @Test
    void longerText_neverCountsFewerTokensThanAPrefixOfIt() {
        JtokkitTokenCounter counter = new JtokkitTokenCounter();
        String prefix = "The quick brown fox";
        String full   = prefix + " jumps over the lazy dog, again and again.";

        assertTrue(counter.count(full) >= counter.count(prefix),
                "appending text must never decrease the token count");
    }

    @Test
    void supportsAnExplicitEncodingType() {
        JtokkitTokenCounter cl100k = new JtokkitTokenCounter(EncodingType.CL100K_BASE);

        assertTrue(cl100k.count("Hello, world!") > 0);
    }

    /**
     * DONE-WHEN (ADR-0087): a CJK text is where {@code chars / 4} is furthest from a real
     * tokenizer — one CJK character is typically its own token or close to it, not a quarter
     * of one, so the approximation must undercount it by roughly 4x while the real tokenizer
     * reports something in the neighbourhood of the character count instead.
     */
    @Test
    void cjkText_isCountedFarAboveTheCharsPerFourApproximation() {
        String cjk = "这是一个用于验证分词器在中日韩文本上表现的示例句子。";   // no ASCII spaces to inflate either count
        JtokkitTokenCounter real = new JtokkitTokenCounter();
        TokenCounter approximate = TokenCounter.approximate();

        int realTokens = real.count(cjk);
        int approxTokens = approximate.count(cjk);

        assertTrue(realTokens > approxTokens * 2,
                () -> "expected the real tokenizer (" + realTokens + ") to report well above "
                        + "double the chars/4 approximation (" + approxTokens + ") on CJK text");
    }

    /**
     * DONE-WHEN (ADR-0087): a dense JSON block is punctuation-heavy — quotes, braces, colons —
     * which {@code chars / 4} treats as no different from prose, while a real tokenizer spends
     * a disproportionate number of tokens on that punctuation.
     */
    @Test
    void denseJson_isCountedAboveTheCharsPerFourApproximation() {
        String json = "{\"id\":1,\"name\":\"a\",\"tags\":[\"x\",\"y\",\"z\"],\"active\":true,\"score\":0.42}";
        JtokkitTokenCounter real = new JtokkitTokenCounter();
        TokenCounter approximate = TokenCounter.approximate();

        int realTokens = real.count(json);
        int approxTokens = approximate.count(json);

        assertTrue(realTokens > approxTokens,
                () -> "expected the real tokenizer (" + realTokens + ") to exceed the chars/4 "
                        + "approximation (" + approxTokens + ") on punctuation-dense JSON");
    }
}
