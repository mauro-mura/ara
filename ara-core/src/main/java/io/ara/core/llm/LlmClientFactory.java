package io.ara.core.llm;

/**
 * Factory for creating {@link LlmClient} instances from transport-identifying data.
 *
 * <p>Defined in {@code ara-core} so that {@code ara-runtime} can accept it without
 * depending on any specific LLM module. The concrete lambda is wired in
 * {@code AraPlatformFactory} inside {@code ara-server}.
 *
 * <p>Receives only {@link LlmTransport} (ADR-039 §3 "asse A") — {@code baseUrl}/{@code
 * apiKey}/{@code modelName} — not the full {@link LlmProfile}: parameters ({@code
 * temperature}, {@code streamingEnabled}, {@code nativeJsonSchema}, …) are asse B and
 * flow per-call via {@link LlmCallContext} instead, since the same transport is shared
 * across every profile that references it regardless of their parameters.
 */
@FunctionalInterface
public interface LlmClientFactory {

    /**
     * Creates an {@link LlmClient} for the given transport. Called once per distinct
     * transport (by id, or by content-addressed id for an inline override) — never
     * per call — since the resulting client is shared and ref-counted.
     *
     * @param transport the transport to build a client for
     * @return a ready-to-use {@link LlmClient}
     */
    LlmClient create(LlmTransport transport);
}
