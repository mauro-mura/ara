/**
 * Failover decorators for the {@code ara-core} store/retrieval ports.
 *
 * <p>{@link io.ara.adapters.resilience.FailoverRetriever} decorates
 * {@link io.ara.core.retriever.Retriever} and {@link io.ara.adapters.resilience.FailoverSemanticStore}
 * decorates {@link io.ara.core.memory.SemanticStore} — both live in {@code ara-core}, which is
 * the only module {@code ara-adapters} depends on.
 *
 * <p><b>{@code io.ara.runtime.memory.KbStore} is out of reach from here</b> — it lives in
 * {@code ara-runtime}, which depends on {@code ara-adapters}, not the other way around, so a
 * decorator for it cannot live in this module without an illegal dependency cycle. Its
 * implementations ({@code DocumentStore}, {@code InMemoryDocumentStore}) are still poolable
 * through {@link io.ara.adapters.resilience.FailoverRetriever}, since {@code KbStore} extends
 * {@link io.ara.core.retriever.Retriever} and retrieval is all a RAG caller needs failover for;
 * only {@code KbStore}'s indexing methods (which are not part of {@code Retriever}) are not
 * poolable this way. A {@code KbStore}-specific failover, if the indexing side ever needs one,
 * belongs in {@code ara-runtime} instead.
 */
package io.ara.adapters.resilience;
