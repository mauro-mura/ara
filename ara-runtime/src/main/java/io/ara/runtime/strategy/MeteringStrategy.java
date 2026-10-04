package io.ara.runtime.strategy;

import io.ara.core.agent.AgentConfig;
import io.ara.core.agent.AgentTask;
import io.ara.core.agent.ExecutionResult;
import io.ara.core.agent.ExecutionStrategy;
import io.ara.core.budget.SpendMeter;
import io.ara.core.common.Money;
import io.ara.core.llm.LlmCallContext;
import io.ara.core.llm.LlmClient;
import io.ara.core.llm.LlmCompletion;
import io.ara.core.llm.LlmException;
import io.ara.core.llm.LlmMessage;
import io.ara.core.memory.MemoryManager;
import io.ara.core.tool.ToolRegistry;
import io.ara.runtime.llm.DelegatingLlmClient;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Flow;

/**
 * Records every LLM call a strategy makes on the {@link SpendMeter} the task carries, and
 * otherwise changes nothing: the same shape as {@link RetrievalAugmentedStrategy} — wrap a
 * strategy, decorate the {@link LlmClient} it is handed, delegate — for a concern that, like
 * retrieval, no individual strategy should have to know about.
 *
 * <h2>Why a decorator, and why one the runtime installs</h2>
 * Only the ReAct family charges a {@code RunBudget} today; {@code PlanExecuteStrategy},
 * {@code ReflexionStrategy} and {@code RetrievalAugmentedStrategy} do not. Teaching each of
 * them to report spend would be eight copies of one rule, and the next strategy would have to
 * remember it. Every strategy makes its LLM calls through the client it is passed, so one
 * decorator around that client measures all of them, present and future.
 *
 * <p>It is <b>not</b> selected by name. {@code AgentConfig.plannerStrategy()} is part of the
 * behavioural hash {@code SpecLineage.hash} is computed from, so a {@code "metered+react"}
 * strategy name would change the identity of the very specs being measured — and measuring is
 * infrastructure, not behaviour. {@code AgentInstance} wraps the strategy the planner selected,
 * and {@link #strategyName()} is the wrapped strategy's own, so logs, spans and interceptors
 * see exactly what they saw before.
 *
 * <h2>What it does and does not measure</h2>
 * <ul>
 *   <li>A call is recorded when it <em>returns</em>: one that throws spent nothing the meter
 *       can see. A strategy that fails or times out after three good calls still has those
 *       three on the meter — unlike an {@code AgentResponse}, which a timeout reports with
 *       zero tokens.</li>
 *   <li>A <em>streamed</em> call carries no usage figures; it is counted as unmetered
 *       ({@link SpendMeter.Reading#unmeteredStreams()}) rather than guessed at.</li>
 *   <li>Money is the agent's configured per-1k-token rates applied to the call's prompt/output
 *       split — the same formula {@code AgentInstance} uses for {@code AgentResponse} cost.</li>
 *   <li>A task without a meter is passed straight through: one map lookup per execution.</li>
 * </ul>
 */
public final class MeteringStrategy implements ExecutionStrategy {

    private final ExecutionStrategy delegate;

    private MeteringStrategy(ExecutionStrategy delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate must not be null");
    }

    /** {@code strategy} with metering added; already-metered strategies are returned as they are. */
    public static ExecutionStrategy around(ExecutionStrategy strategy) {
        Objects.requireNonNull(strategy, "strategy must not be null");
        return strategy instanceof MeteringStrategy ? strategy : new MeteringStrategy(strategy);
    }

    /** The wrapped strategy's own name — metering is invisible to everything that reads it. */
    @Override
    public String strategyName() {
        return delegate.strategyName();
    }

    @Override
    public ExecutionResult execute(
            AgentTask task,
            LlmClient llm,
            MemoryManager memory,
            ToolRegistry tools,
            AgentConfig config) {
        SpendMeter meter = SpendMeter.from(task.runContext()).orElse(null);
        if (meter == null) {
            return delegate.execute(task, llm, memory, tools, config);
        }
        return delegate.execute(task, new MeteringLlmClient(llm, meter, config), memory, tools, config);
    }

    private static final BigDecimal ONE_THOUSAND = BigDecimal.valueOf(1_000);

    private static final class MeteringLlmClient extends DelegatingLlmClient {

        private final SpendMeter meter;
        private final Money inputRate;
        private final Money outputRate;

        MeteringLlmClient(LlmClient delegate, SpendMeter meter, AgentConfig config) {
            super(delegate);
            this.meter = meter;
            this.inputRate = config.costInputPer1kTokens();
            this.outputRate = config.costOutputPer1kTokens();
        }

        @Override
        public LlmCompletion complete(List<LlmMessage> messages, LlmCallContext context) throws LlmException {
            LlmCompletion completion = delegate.complete(messages, context);
            meter.record(costOf(completion.promptTokens(), completion.outputTokens()),
                    Math.max(0, completion.promptTokens()), Math.max(0, completion.outputTokens()), 1);
            return completion;
        }

        @Override
        public Flow.Publisher<String> stream(List<LlmMessage> messages, LlmCallContext context) {
            meter.recordUnmeteredStream();
            return delegate.stream(messages, context);
        }

        private Money costOf(int promptTokens, int outputTokens) {
            return inputRate.multiply(per1k(promptTokens)).plus(outputRate.multiply(per1k(outputTokens)));
        }

        private static BigDecimal per1k(int tokens) {
            return BigDecimal.valueOf(Math.max(0, tokens)).divide(ONE_THOUSAND);
        }
    }
}
