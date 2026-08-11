package io.ara.core.llm;

import java.util.Objects;

/**
 * Identifying data for an LLM transport (ADR-039 §3 "asse A"): everything needed to
 * build an {@link LlmClient} for one specific model — as opposed to {@link LlmProfile}'s
 * per-call parameters (asse B: {@code temperature}, {@code topP}, …) and per-agent
 * governance (asse C: cost budget/rates).
 *
 * <p>The transport is the unit of sharing: two {@link LlmProfile}s with different
 * parameters but an identical transport (same {@code modelId}, or an inline override
 * with identical {@code baseUrl}/{@code modelName}/{@code apiKey}) resolve to the
 * <em>same</em> underlying {@link LlmClient}, built once and ref-counted.
 *
 * @param baseUrl   the provider endpoint
 * @param apiKey    credential for the endpoint; may be {@code null}/blank for local
 *                  servers without auth
 * @param modelName the provider-specific model identifier (e.g. {@code "gpt-4o"})
 */
public record LlmTransport(String baseUrl, String apiKey, String modelName) {

    public LlmTransport {
        Objects.requireNonNull(baseUrl, "baseUrl must not be null");
        Objects.requireNonNull(modelName, "modelName must not be null");
        if (baseUrl.isBlank()) throw new IllegalArgumentException("baseUrl must not be blank");
        if (modelName.isBlank()) throw new IllegalArgumentException("modelName must not be blank");
    }
}
