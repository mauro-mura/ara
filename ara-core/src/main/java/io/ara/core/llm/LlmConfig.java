package io.ara.core.llm;

import java.util.List;
import java.util.Objects;

/**
 * LLM configuration for an agent: which model(s) to use, how to select among them,
 * and I/O logging settings.
 */
public record LlmConfig(
        LlmProfile         primary,
        List<LlmProfile>   fallbacks,
        LlmSelectionPolicy policy,
        boolean            logIo,
        int                logIoMaxChars
) {
    public LlmConfig {
        Objects.requireNonNull(primary, "primary LlmProfile must not be null");
        fallbacks = List.copyOf(Objects.requireNonNullElse(fallbacks, List.of()));
        policy    = Objects.requireNonNullElse(policy, LlmSelectionPolicy.PRIMARY_ONLY);
        if (logIoMaxChars < 0)
            throw new IllegalArgumentException("logIoMaxChars must be >= 0");
    }

    public static LlmConfig of(String modelId) {
        return new LlmConfig(LlmProfile.of(modelId), List.of(),
                LlmSelectionPolicy.PRIMARY_ONLY, false, 1500);
    }

    public static LlmConfig defaults() {
        return new LlmConfig(LlmProfile.of(""), List.of(),
                LlmSelectionPolicy.PRIMARY_ONLY, false, 1500);
    }

    public List<LlmProfile> allProfiles() {
        var all = new java.util.ArrayList<LlmProfile>();
        all.add(primary);
        all.addAll(fallbacks);
        return List.copyOf(all);
    }
}
