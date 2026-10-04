package io.ara.core.budget;

import io.ara.core.agent.RunContext;
import io.ara.core.common.Money;

import java.util.Objects;
import java.util.Optional;

/**
 * A running total of what the LLM calls of one task — and of every task it delegates —
 * actually cost: tokens (split prompt / output), money and the number of calls.
 *
 * <p><b>Measuring, not enforcing.</b> A {@link RunBudget} <em>governs</em> a run: it has caps,
 * and a charge past one stops the run. A meter only <em>reads</em>: it has no cap, never fails
 * anything, and exists so that something which composes agents — a workflow, a blueprint — can
 * report what the composition spent. The two are deliberately separate objects that travel on
 * separate {@link RunContext} keys, so attaching a meter can never make a run stricter, and a
 * strategy that already charges a {@code RunBudget} is not charged twice.
 *
 * <p><b>How it is fed.</b> {@code AgentInstance} reads the meter off the task's
 * {@link RunContext} ({@link #from}) and records every LLM call the selected strategy makes,
 * whatever the strategy is. {@code AgentDelegationTool} hands the caller's {@code RunContext}
 * — opaque channel included — to the delegated sub-task, so what a delegate spends lands on
 * the same meter. A task without a meter costs one map lookup and nothing else.
 *
 * <p><b>Currency.</b> A meter has one currency. A call priced in another one adds its tokens
 * and its call but not its money, and is counted in {@link Reading#currencyMismatches()}: a
 * run is never failed from inside a measurement, but the discrepancy is not silent either — the
 * caller that owns the meter decides whether a non-zero count is an error.
 *
 * <p>Thread-safe: concurrent branches of one composition record on the same instance.
 */
public final class SpendMeter {

    /** Opaque-channel key for {@link #attachTo}/{@link #from}, symmetric to {@link RunBudget}. */
    private static final String RUN_CONTEXT_KEY = "io.ara.core.budget.meter";

    private final String currency;

    private Money money;
    private long  promptTokens;
    private long  outputTokens;
    private long  calls;
    private int   currencyMismatches;
    private int   unmeteredStreams;

    private SpendMeter(String currency) {
        this.currency = Objects.requireNonNull(currency, "currency must not be null");
        this.money = Money.zero(currency);
    }

    /** An empty meter in {@code currency}. */
    public static SpendMeter of(String currency) {
        return new SpendMeter(currency);
    }

    public String currency() {
        return currency;
    }

    /**
     * Adds {@code calls} LLM calls that together used {@code promptTokens} / {@code outputTokens}
     * and cost {@code cost}. Money in a currency other than this meter's is left out and counted
     * as a mismatch; tokens and calls are always added.
     */
    public synchronized void record(Money cost, long promptTokens, long outputTokens, long calls) {
        Objects.requireNonNull(cost, "cost must not be null");
        if (promptTokens < 0 || outputTokens < 0 || calls < 0) {
            throw new IllegalArgumentException("tokens and calls must be >= 0");
        }
        if (cost.currency().equals(currency)) {
            money = money.plus(cost);
        } else if (cost.amount().signum() > 0) {
            currencyMismatches++;
        }
        this.promptTokens += promptTokens;
        this.outputTokens += outputTokens;
        this.calls += calls;
    }

    /**
     * Notes one streamed LLM call. A stream carries no usage figures, so it cannot be
     * measured; it is counted so that a reading says "something was not measured" instead of
     * reporting a total that looks complete.
     */
    public synchronized void recordUnmeteredStream() {
        unmeteredStreams++;
    }

    /** Adds everything {@code other} read to this meter — how a per-call meter is folded into its parent. */
    public synchronized void absorb(Reading other) {
        Objects.requireNonNull(other, "other must not be null");
        Money added = other.spend().money();
        if (added.currency().equals(currency)) {
            money = money.plus(added);
        } else if (added.amount().signum() > 0) {
            currencyMismatches++;
        }
        promptTokens += other.promptTokens();
        outputTokens += other.outputTokens();
        calls += other.spend().calls();
        currencyMismatches += other.currencyMismatches();
        unmeteredStreams += other.unmeteredStreams();
    }

    /** What has been recorded so far; safe to call while other threads are still recording. */
    public synchronized Reading reading() {
        return new Reading(Spend.of(money, promptTokens + outputTokens, calls),
                promptTokens, outputTokens, currencyMismatches, unmeteredStreams);
    }

    // ── propagation, symmetric to RunBudget (ADR-0069 D3) ────────────────────

    /** A copy of {@code ctx} carrying this meter on the opaque channel — hand it to a task to have its spend recorded. */
    public RunContext attachTo(RunContext ctx) {
        Objects.requireNonNull(ctx, "ctx must not be null");
        return ctx.withOpaque(RUN_CONTEXT_KEY, this);
    }

    /** The meter carried by {@code ctx}, if any. */
    public static Optional<SpendMeter> from(RunContext ctx) {
        return Optional.ofNullable(ctx == null ? null : ctx.opaque(RUN_CONTEXT_KEY, SpendMeter.class));
    }

    /**
     * A snapshot of a meter.
     *
     * @param spend              money (in the meter's currency), total tokens and number of LLM calls
     * @param promptTokens       prompt tokens across all calls
     * @param outputTokens       output tokens across all calls
     * @param currencyMismatches calls whose money could not be added for lack of a common currency
     * @param unmeteredStreams   streamed calls, which carry no usage and so are not in the totals
     */
    public record Reading(Spend spend, long promptTokens, long outputTokens,
                          int currencyMismatches, int unmeteredStreams) {

        public Reading {
            Objects.requireNonNull(spend, "spend must not be null");
        }

        /** {@code true} when nothing was recorded at all. */
        public boolean isEmpty() {
            return spend.calls() == 0 && unmeteredStreams == 0;
        }
    }
}
