package io.ara.adapters.resilience;

import java.util.List;
import java.util.Objects;

import io.ara.core.retriever.RetrievedChunk;
import io.ara.core.retriever.Retriever;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link Retriever} decorator that implements ordered failover across multiple backing stores
 * serving the same knowledge base (e.g. a primary Qdrant instance and a read replica).
 *
 * <p>On each call to {@link #retrieve}, it tries candidates in declaration order. Any
 * {@link RuntimeException} advances to the next candidate; only when every candidate is
 * exhausted is the last exception re-thrown.
 *
 * <h2>Failover on exception only, never on an empty result</h2>
 * <p>A healthy candidate that legitimately has no hits for a query must return an empty list,
 * not defer to the next candidate — a query nobody has indexed yet is a correct empty answer,
 * not a failure. Failing over on emptiness would make a perfectly healthy primary's "no
 * results" indistinguishable from an outage, silently routing every miss to a fallback that
 * may be a stale replica with its own different (and equally correct) empty or non-empty
 * answer. Retrying only on an actual thrown exception is what keeps this decorator a resilience
 * mechanism instead of a second, undocumented ranking policy layered under the caller's back.
 *
 * <p>Read-only and single-method, so unlike {@code EmbeddingEndpointPool}'s companion
 * {@code FailoverSemanticStore} this needs no write policy: {@link #retrieve} has no state to
 * keep in sync across candidates.
 */
public final class FailoverRetriever implements Retriever {

    private static final Logger log = LoggerFactory.getLogger(FailoverRetriever.class);

    private final List<Retriever> candidates;

    public FailoverRetriever(List<Retriever> candidates) {
        Objects.requireNonNull(candidates, "candidates must not be null");
        if (candidates.isEmpty()) throw new IllegalArgumentException("At least one candidate required");
        this.candidates = List.copyOf(candidates);
    }

    @Override
    public List<RetrievedChunk> retrieve(String query, int maxResults) {
        RuntimeException lastFailure = null;

        for (int i = 0; i < candidates.size(); i++) {
            Retriever candidate = candidates.get(i);
            try {
                List<RetrievedChunk> result = candidate.retrieve(query, maxResults);
                if (i > 0) {
                    log.info("Retriever failover succeeded with candidate {} (primary failed after {} attempt(s))",
                            i, i);
                }
                return result;
            } catch (RuntimeException ex) {
                lastFailure = ex;
                boolean hasNext = i < candidates.size() - 1;
                if (hasNext) {
                    log.warn("Retriever candidate {} failed — switching to next fallback. Reason: {}",
                            i, ex.getMessage());
                } else {
                    log.error("All {} retriever candidate(s) failed. Last error: {}",
                            candidates.size(), ex.getMessage());
                }
            }
        }

        throw lastFailure;
    }
}
