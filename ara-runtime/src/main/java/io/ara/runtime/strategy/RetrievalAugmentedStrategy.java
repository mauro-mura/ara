package io.ara.runtime.strategy;

import io.ara.core.agent.AgentConfig;
import io.ara.core.agent.AgentTask;
import io.ara.core.agent.ExecutionResult;
import io.ara.core.agent.ExecutionStrategy;
import io.ara.core.llm.LlmCallContext;
import io.ara.core.llm.LlmClient;
import io.ara.core.llm.LlmCompletion;
import io.ara.core.llm.LlmMessage;
import io.ara.core.memory.MemoryManager;
import io.ara.core.retriever.RetrievedChunk;
import io.ara.core.retriever.Retriever;
import io.ara.core.retriever.RetrieverRouter;
import io.ara.core.tool.ToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.Flow;

/**
 * Decorator that adds Retrieval-Augmented Generation to any {@link ExecutionStrategy}.
 *
 * <p>Before delegating to the wrapped strategy, this decorator:
 * <ol>
 *   <li>Resolves the {@link Retriever} to use for this task via {@link RetrieverRouter#select},
 *       keyed by {@code AgentConfig.retrieverId()} — one registered instance of this strategy
 *       serves every agent, each picking its own retriever by id, exactly like {@code LlmRouter}
 *       resolves the {@code LlmClient} from {@code LlmConfig} (ADR-030 pattern applied to
 *       retrieval).</li>
 *   <li>Retrieves the most relevant passages from that {@link Retriever} using the task input.</li>
 *   <li>Wraps the real {@link LlmClient} with an {@link AugmentingLlmClient} that transparently
 *       injects the retrieved context into the system message of every LLM call.</li>
 *   <li>Delegates to the wrapped strategy with the augmenting client — the strategy itself is
 *       unaware of the retrieval step.</li>
 * </ol>
 *
 * <p>This design means RAG composes freely with any strategy:
 * <ul>
 *   <li>{@code wrap(new ReactStrategy(), router)} — ReAct with guaranteed context injection;
 *       the agent can also call tools on top of the retrieved context.</li>
 *   <li>{@code wrap(new PlanExecuteStrategy(), router)} — structured planning with RAG context.</li>
 * </ul>
 *
 * <p>Single-retriever callers can pass a plain {@link Retriever} to {@link #wrap(ExecutionStrategy,
 * Retriever)} — it is wrapped in a trivial router that ignores {@code retrieverId} and always
 * returns that one instance, preserving the pre-multi-retriever call sites.
 *
 * <h2>Strategy name</h2>
 * The name is {@code "rag+" + delegate.strategyName()}, e.g. {@code "rag+react"}.
 * Register both the plain strategy and its augmented variant in {@link ExecutionPlanner}.
 *
 * <h2>Context injection</h2>
 * Retrieved passages are appended to the first {@code system} message found in the
 * outgoing message list. If no system message exists, a new one is prepended.
 * All subsequent LLM calls within the same strategy pass (e.g. multiple ReAct iterations)
 * receive the same context block — retrieval happens once per task.
 */
public final class RetrievalAugmentedStrategy implements ExecutionStrategy {

    private static final Logger log = LoggerFactory.getLogger(RetrievalAugmentedStrategy.class);

    static final int DEFAULT_MAX_CHUNKS = 5;

    private final ExecutionStrategy delegate;
    private final RetrieverRouter   router;
    private final int               maxChunks;

    private RetrievalAugmentedStrategy(ExecutionStrategy delegate, RetrieverRouter router, int maxChunks) {
        this.delegate  = Objects.requireNonNull(delegate, "delegate must not be null");
        this.router    = Objects.requireNonNull(router,   "router must not be null");
        this.maxChunks = maxChunks;
    }

    /**
     * Wraps {@code delegate} with retrieval augmentation using the given single {@link Retriever}
     * — every task uses this one retriever, regardless of {@code AgentConfig.retrieverId()}.
     *
     * @param delegate  the base strategy to wrap
     * @param retriever the retriever that supplies context passages
     * @return a new {@code RetrievalAugmentedStrategy}
     */
    public static RetrievalAugmentedStrategy wrap(ExecutionStrategy delegate, Retriever retriever) {
        Objects.requireNonNull(retriever, "retriever must not be null");
        return wrap(delegate, ignoredId -> retriever, DEFAULT_MAX_CHUNKS);
    }

    /**
     * Wraps {@code delegate} with retrieval augmentation using the given single {@link Retriever},
     * controlling how many passages are injected.
     *
     * @param delegate  the base strategy to wrap
     * @param retriever the retriever that supplies context passages
     * @param maxChunks maximum number of passages to inject per task
     * @return a new {@code RetrievalAugmentedStrategy}
     */
    public static RetrievalAugmentedStrategy wrap(ExecutionStrategy delegate, Retriever retriever, int maxChunks) {
        Objects.requireNonNull(retriever, "retriever must not be null");
        return wrap(delegate, ignoredId -> retriever, maxChunks);
    }

    /**
     * Wraps {@code delegate} with retrieval augmentation backed by a {@link RetrieverRouter} —
     * each agent selects its own {@link Retriever} via {@code AgentConfig.retrieverId()}.
     *
     * @param delegate the base strategy to wrap
     * @param router   resolves the retriever to use per task, by {@code retrieverId}
     * @return a new {@code RetrievalAugmentedStrategy}
     */
    public static RetrievalAugmentedStrategy wrap(ExecutionStrategy delegate, RetrieverRouter router) {
        return wrap(delegate, router, DEFAULT_MAX_CHUNKS);
    }

    /**
     * Wraps {@code delegate} with retrieval augmentation backed by a {@link RetrieverRouter},
     * controlling how many passages are injected.
     *
     * @param delegate  the base strategy to wrap
     * @param router    resolves the retriever to use per task, by {@code retrieverId}
     * @param maxChunks maximum number of passages to inject per task
     * @return a new {@code RetrievalAugmentedStrategy}
     */
    public static RetrievalAugmentedStrategy wrap(ExecutionStrategy delegate, RetrieverRouter router, int maxChunks) {
        return new RetrievalAugmentedStrategy(delegate, router, maxChunks);
    }

    @Override
    public String strategyName() {
        return "rag+" + delegate.strategyName();
    }

    @Override
    public ExecutionResult execute(
            AgentTask task,
            LlmClient llm,
            MemoryManager memory,
            ToolRegistry tools,
            AgentConfig config) {

        // ── Resolve the retriever for this agent, then retrieve once per task ──
        Retriever retriever = router.select(config.retrieverId());
        List<RetrievedChunk> chunks = retriever.retrieve(task.input(), maxChunks);
        log.debug("[{}] Retrieved {} chunk(s) for task [{}]",
                strategyName(), chunks.size(), task.taskId());

        // ── Wrap LLM — transparent to delegate ────────────────────────────────
        LlmClient effectiveLlm = chunks.isEmpty()
                ? llm
                : new AugmentingLlmClient(llm, buildContextBlock(chunks));

        // ── Delegate — unaware of retrieval ───────────────────────────────────
        return delegate.execute(task, effectiveLlm, memory, tools, config);
    }

    // ── Context formatting ────────────────────────────────────────────────────

    private static String buildContextBlock(List<RetrievedChunk> chunks) {
        StringBuilder sb = new StringBuilder();
        sb.append("## Retrieved Context\n");
        sb.append("The following passages were retrieved from the knowledge base ");
        sb.append("and are relevant to the user's question. ");
        sb.append("Use them as your primary source.\n\n");
        for (int i = 0; i < chunks.size(); i++) {
            RetrievedChunk c = chunks.get(i);
            sb.append("[").append(i + 1).append("] ")
              .append("Source: ").append(c.title())
              // Locale.ROOT: the default-locale overload renders 0.95 as "0,95" on a
              // comma-decimal JVM (it_IT, de_DE, ...), and this string goes into a prompt.
              .append(" (score=").append(String.format(Locale.ROOT, "%.2f", c.score())).append(")\n")
              .append(c.content())
              .append("\n\n");
        }
        return sb.toString().strip();
    }

    // ── AugmentingLlmClient ───────────────────────────────────────────────────

    /**
     * Thin decorator that prepends retrieved context to the system message of every
     * LLM call. Both {@link #complete} and {@link #stream} are intercepted so that
     * streaming-enabled strategies also receive the augmented context.
     */
    private static final class AugmentingLlmClient implements LlmClient {

        private final LlmClient delegate;
        private final String    contextBlock;

        AugmentingLlmClient(LlmClient delegate, String contextBlock) {
            this.delegate     = delegate;
            this.contextBlock = contextBlock;
        }

        @Override
        public LlmCompletion complete(List<LlmMessage> messages, LlmCallContext context) {
            return delegate.complete(augment(messages), context);
        }

        @Override
        public Flow.Publisher<String> stream(List<LlmMessage> messages, LlmCallContext context) {
            return delegate.stream(augment(messages), context);
        }

        @Override
        public String providerId() {
            return delegate.providerId();
        }

        @Override
        public boolean supportsNativeTools() {
            return delegate.supportsNativeTools();
        }

        private List<LlmMessage> augment(List<LlmMessage> messages) {
            List<LlmMessage> result = new ArrayList<>(messages.size() + 1);
            boolean injected = false;
            for (LlmMessage msg : messages) {
                if (!injected && "system".equals(msg.role())) {
                    result.add(LlmMessage.system(msg.content() + "\n\n" + contextBlock));
                    injected = true;
                } else {
                    result.add(msg);
                }
            }
            if (!injected) {
                // No system message found — prepend context as system message
                result.add(0, LlmMessage.system(contextBlock));
            }
            return result;
        }
    }
}
