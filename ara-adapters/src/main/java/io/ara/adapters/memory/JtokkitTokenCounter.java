package io.ara.adapters.memory;

import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.EncodingRegistry;
import com.knuddels.jtokkit.api.EncodingType;
import io.ara.core.memory.TokenCounter;

import java.util.Objects;

/**
 * {@link TokenCounter} backed by <a href="https://github.com/knuddelsgmbh/jtokkit">jtokkit</a>
 * — a pure-Java, dependency-free BPE tokenizer compatible with OpenAI's own {@code tiktoken}
 * encodings (ADR-0087).
 *
 * <p>Defaults to {@link EncodingType#O200K_BASE}, the encoding behind the current GPT-4o
 * family — the closest a single fixed encoding gets to "the provider ARA users most likely
 * talk to" without ARA picking a model on the caller's behalf. Every {@code tiktoken}
 * encoding undercounts or overcounts against a <em>different</em> provider's own tokenizer
 * (Anthropic, Mistral, …), which none of jtokkit's encodings target; it is still far closer
 * to those providers' real counts than {@link TokenCounter#approximate()}'s {@code chars / 4},
 * which targets no tokenizer at all.
 *
 * <pre>{@code
 * TokenCounter counter = new JtokkitTokenCounter();               // O200K_BASE
 * TokenCounter legacy  = new JtokkitTokenCounter(EncodingType.CL100K_BASE);
 * }</pre>
 */
public final class JtokkitTokenCounter implements TokenCounter {

    private final Encoding encoding;

    /** Uses {@link EncodingType#O200K_BASE} — see the class javadoc for why. */
    public JtokkitTokenCounter() {
        this(EncodingType.O200K_BASE);
    }

    /** Uses the given jtokkit {@link EncodingType}. */
    public JtokkitTokenCounter(EncodingType type) {
        Objects.requireNonNull(type, "type must not be null");
        EncodingRegistry registry = Encodings.newDefaultEncodingRegistry();
        this.encoding = registry.getEncoding(type);
    }

    @Override
    public int count(String text) {
        return text == null || text.isEmpty() ? 0 : encoding.countTokens(text);
    }
}
