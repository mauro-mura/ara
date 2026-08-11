package io.ara.core.llm;

import io.ara.core.agent.AgentConfig;

import java.util.List;
import java.util.concurrent.Flow;

/**
 * Abstraction over any LLM provider (OpenAI, Anthropic, Gemini, Ollama, …).
 *
 * <p>The runtime only ever speaks to this interface — concrete adapters live in
 * {@code ara-llm-providers} and are wired by {@code AgentFactory}.
 *
 * <h2>Migration from AgentConfig to LlmCallContext (ADR-017)</h2>
 * <p>The primary method is now {@link #complete(List, LlmCallContext)} which accepts
 * per-call parameters (output schema, temperature override, stop sequences, seed).
 * Implementations must override this method. The {@link #complete(List, AgentConfig)}
 * overload is deprecated and bridges automatically to the new primary method.
 */
public interface LlmClient {

    /**
     * Sends messages to the LLM with per-call parameters and blocks until completion.
     *
     * <p>This is the primary method. Implementations must override this.
     * The context carries per-call parameters (output schema, temperature override, etc.)
     * in addition to the base configuration copied from {@link AgentConfig}.
     *
     * @param messages the conversation history, oldest first
     * @param context  per-call parameters built from AgentConfig + LlmExecutionHints
     * @return the LLM's completion
     * @throws LlmException if the LLM call fails; check {@link LlmException#isRetryable()} to decide whether to retry
     */
    LlmCompletion complete(List<LlmMessage> messages, LlmCallContext context) throws LlmException;

    /**
     * @deprecated Use {@link #complete(List, LlmCallContext)}. This method bridges
     *             to the new primary by wrapping config in a {@link LlmCallContext}.
     *             Per-call features (output schema, temperature override, stop sequences,
     *             seed) are not available through this path.
     */
    @Deprecated(forRemoval = false)
    default LlmCompletion complete(List<LlmMessage> messages, AgentConfig config) {
        return complete(messages, LlmCallContext.from(config));
    }

    /**
     * Streams tokens from the LLM with per-call parameters.
     *
     * <p>The default implementation delegates to {@link #complete(List, LlmCallContext)}
     * and emits the full response as a single item. Implementations that support native
     * streaming (e.g. {@code Lc4jLlmClient}) override this method.
     */
    default Flow.Publisher<String> stream(List<LlmMessage> messages, LlmCallContext context) {
        return subscriber -> subscriber.onSubscribe(new Flow.Subscription() {
            @Override
            public void request(long n) {
                try {
                    String text = complete(messages, context).text();
                    subscriber.onNext(text);
                    subscriber.onComplete();
                } catch (Exception e) {
                    subscriber.onError(e);
                }
            }
            @Override public void cancel() {}
        });
    }

    /**
     * @deprecated Use {@link #stream(List, LlmCallContext)}.
     */
    @Deprecated(forRemoval = false)
    default Flow.Publisher<String> stream(List<LlmMessage> messages, AgentConfig config) {
        return stream(messages, LlmCallContext.from(config));
    }

    /**
     * Returns the provider identifier string (e.g. {@code "langchain4j-gpt-4o"}).
     *
     * @return a non-null, non-blank provider id
     */
    String providerId();

    /**
     * Returns the provider id of the client that handled the most recent {@link #complete} call.
     *
     * <p>For simple clients this is identical to {@link #providerId()}. Composite clients
     * (e.g. failover) override this to return the id of whichever delegate actually
     * responded, giving callers visibility into which model was used at runtime.
     *
     * @return provider id of the last successful delegate; never null
     */
    default String lastUsedProviderId() {
        return providerId();
    }

    /**
     * Whether this client sends native provider tool/function-calling
     * ({@link LlmCallContext#resolvedTools()} → structured {@code toolSpecifications}
     * on the outgoing request) and reports invocations back via {@link
     * LlmCompletion#hasToolCall()}/{@link LlmCompletion#toolCalls()}.
     *
     * <p>Strategies ({@code ReactStrategy}, {@code PlanExecuteStrategy}) use this to
     * decide whether to also inject the text-based tool catalog and inline
     * {@code {"tool_id":...}} / {@code FINAL_ANSWER} instructions into the system
     * prompt: when {@code true}, that text scaffolding is redundant with (and can
     * actively compete against) the structured channel, so it is omitted.
     *
     * <p>Defaults to {@code false} — the safe default for any client that only
     * supports prompt-based tool routing (e.g. most local/Ollama models). Adapters
     * that do speak native function-calling (OpenAI, Anthropic) override this to
     * {@code true}. Decorators <strong>must</strong> delegate to the wrapped
     * client(s) rather than inherit this default, or they will silently mask the
     * capability of whatever they wrap.
     *
     * @return {@code true} if native tool/function-calling is used for this client
     */
    default boolean supportsNativeTools() {
        return false;
    }
}
