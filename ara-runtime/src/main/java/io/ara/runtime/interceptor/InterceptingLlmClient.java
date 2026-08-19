package io.ara.runtime.interceptor;

import io.ara.core.agent.AgentExecutionContext;
import io.ara.core.llm.LlmCallContext;
import io.ara.core.llm.LlmClient;
import io.ara.core.llm.LlmCompletion;
import io.ara.core.llm.LlmException;
import io.ara.core.llm.LlmMessage;
import io.ara.runtime.llm.DelegatingLlmClient;

import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * {@link LlmClient} decorator that fires {@link io.ara.core.agent.AgentInterceptor#beforeThink}
 * / {@link io.ara.core.agent.AgentInterceptor#afterThink} around every {@link #complete} call,
 * giving interceptors visibility into individual ReAct "Think" steps — not just the
 * task-level {@code "Executing"} phase that {@code AgentInstance} brackets.
 *
 * <p>{@code contextSupplier} is invoked fresh on every call (rather than captured once) so
 * each notification carries the working memory and state as they stand at that moment,
 * mirroring how {@code AgentInstance.buildContext} is used elsewhere.
 *
 * <p>Mirrors {@code InstrumentedLlmClient} (the OTel equivalent): {@link #stream} is
 * deliberately left un-instrumented, same as that class — streaming responses carry no
 * discrete {@link LlmCompletion} to report until {@code ReactStrategy} has finished
 * collecting tokens and reconstructed one itself.
 */
public final class InterceptingLlmClient extends DelegatingLlmClient {

    private final AgentInterceptorChain interceptorChain;
    private final Supplier<AgentExecutionContext> contextSupplier;

    public InterceptingLlmClient(LlmClient delegate, AgentInterceptorChain interceptorChain,
                                  Supplier<AgentExecutionContext> contextSupplier) {
        super(delegate);
        this.interceptorChain = Objects.requireNonNull(interceptorChain, "interceptorChain must not be null");
        this.contextSupplier  = Objects.requireNonNull(contextSupplier,  "contextSupplier must not be null");
    }

    @Override
    public LlmCompletion complete(List<LlmMessage> messages, LlmCallContext context) throws LlmException {
        AgentExecutionContext execCtx = contextSupplier.get();
        interceptorChain.beforeThink(execCtx);
        LlmCompletion completion = delegate.complete(messages, context);
        interceptorChain.afterThink(execCtx, completion);
        return completion;
    }
}
