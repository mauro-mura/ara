package io.ara.runtime.stubs;

import io.ara.runtime.memory.AbstractMemoryManager;

/**
 * In-memory {@link io.ara.core.memory.MemoryManager} for tests and local development.
 * Backed by a plain {@code ArrayList} — no persistence, no vector store.
 */
public final class InMemoryMemoryManager extends AbstractMemoryManager {}
