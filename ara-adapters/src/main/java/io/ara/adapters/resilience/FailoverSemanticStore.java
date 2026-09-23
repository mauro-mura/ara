package io.ara.adapters.resilience;

import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

import io.ara.core.memory.MemoryEntry;
import io.ara.core.memory.SemanticEntry;
import io.ara.core.memory.SemanticStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link SemanticStore} decorator resilient to one or more backing replicas being down —
 * writes fan out to every replica, reads fail over across them in order.
 *
 * <h2>Why writes and reads need different policies</h2>
 * <p>{@link #upsert}/{@link #upsertAll} go to <b>every</b> replica (best-effort: one replica's
 * failure is logged, not thrown, as long as at least one succeeds), while {@link #search} tries
 * replicas in order and stops at the first that answers. Fanning out writes and failing over
 * reads is the only combination that keeps replicas readable consistently: writing only to the
 * primary and reading with failover would mean a read that lands on the fallback after the
 * primary recovers silently misses everything written while it was down — the corruption a
 * "failover" store exists to prevent, not cause. The inverse (failing over writes too) is not
 * meaningfully different from fan-out here since there is no coordinator to pick "the" write
 * target; fanning out is the same idea made honest about writing to all of them.
 *
 * <p>Fan-out is deliberately not all-or-nothing: {@link #upsert} throws only if <em>every</em>
 * replica rejected the write, because a caller (e.g. {@code SlidingWindowMemoryManager}'s
 * best-effort episodic offload, which already catches and logs a failed write per entry) should
 * not have a healthy write to N-1 replicas turned into a hard failure by one replica's outage.
 *
 * <h2>Known limitation — replicas can drift</h2>
 * <p>A replica down during a write stays missing those entries with nothing to resynchronise
 * it later; this class provides no repair pass. That is an acceptable trade-off for the
 * best-effort episodic recall this backs today, not a general-purpose replication layer — a
 * caller that needs replicas to converge after an outage needs a different mechanism entirely
 * (this one only ever fans a live write out to whoever is reachable at that moment).
 *
 * <h2>Failover on exception only, never on an empty result</h2>
 * <p>See {@link FailoverRetriever} — the same reasoning applies to {@link #search}: a healthy
 * replica with no hits must return an empty list, not be treated as failed.
 */
public final class FailoverSemanticStore implements SemanticStore {

    private static final Logger log = LoggerFactory.getLogger(FailoverSemanticStore.class);

    private final List<SemanticStore> replicas;

    public FailoverSemanticStore(List<SemanticStore> replicas) {
        Objects.requireNonNull(replicas, "replicas must not be null");
        if (replicas.isEmpty()) throw new IllegalArgumentException("At least one replica required");
        this.replicas = List.copyOf(replicas);
    }

    @Override
    public void upsert(String agentId, String role, String type, String content, List<Float> vector) {
        fanOut(store -> store.upsert(agentId, role, type, content, vector));
    }

    /**
     * Overridden (rather than left to {@link SemanticStore}'s default) so each replica's own
     * batched write is used — the default would loop {@link #upsert} once per entry, turning
     * one round-trip per replica into {@code entries.size()} of them, defeating the reason
     * {@link SemanticStore#upsertAll} exists at all.
     */
    @Override
    public void upsertAll(String agentId, List<SemanticEntry> entries) {
        fanOut(store -> store.upsertAll(agentId, entries));
    }

    private void fanOut(Consumer<SemanticStore> write) {
        RuntimeException firstFailure = null;
        int               failures    = 0;

        for (SemanticStore replica : replicas) {
            try {
                write.accept(replica);
            } catch (RuntimeException ex) {
                failures++;
                if (firstFailure == null) firstFailure = ex;
                log.warn("Write failed on one semantic-store replica — continuing with the rest. Reason: {}",
                        ex.getMessage());
            }
        }

        if (failures == replicas.size()) {
            log.error("All {} semantic-store replica(s) rejected this write. Reason: {}",
                    replicas.size(), firstFailure.getMessage());
            throw firstFailure;
        }
    }

    @Override
    public List<MemoryEntry> search(String agentId, List<Float> queryVector, int limit) {
        RuntimeException lastFailure = null;

        for (int i = 0; i < replicas.size(); i++) {
            SemanticStore replica = replicas.get(i);
            try {
                List<MemoryEntry> result = replica.search(agentId, queryVector, limit);
                if (i > 0) {
                    log.info("Search failover succeeded with replica {} (primary failed after {} attempt(s))", i, i);
                }
                return result;
            } catch (RuntimeException ex) {
                lastFailure = ex;
                boolean hasNext = i < replicas.size() - 1;
                if (hasNext) {
                    log.warn("Semantic-store replica {} failed on search — switching to next fallback. Reason: {}",
                            i, ex.getMessage());
                } else {
                    log.error("All {} semantic-store replica(s) failed on search. Last error: {}",
                            replicas.size(), ex.getMessage());
                }
            }

            // P8/U21, 2026-09-23: same fix, same reasoning as FailoverLlmClient (U21bis) —
            // a deadline watchdog interrupting this thread mid-replica must stop the
            // failover loop here, not let it march through every remaining replica, each
            // paying its own full request timeout.
            if (Thread.currentThread().isInterrupted()) {
                log.warn("Semantic-store search failover stopped after replica {} — calling thread "
                                + "was interrupted (deadline exceeded), not trying the remaining {} replica(s)",
                        i, replicas.size() - i - 1);
                break;
            }
        }

        throw lastFailure;
    }
}
