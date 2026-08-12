package io.ara.runtime.wiring;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Default {@link ResourceRegistry}: the sole place in the whole ADR-039 mechanism that
 * contains reference-counting logic. Everything else (agents, sessions, {@code
 * WiringFactory}) only ever sees {@link Lease}s.
 *
 * <p>Resources are released through a caller-supplied {@code closer} rather than
 * requiring {@code R extends AutoCloseable}: not every resource this mechanism manages
 * has a real teardown (an {@code LlmClient} typically doesn't; an {@code McpClient}
 * does). Use {@link #forCloseable} when {@code R} does implement {@link AutoCloseable}.
 *
 * <p>Concurrency: each id has its own monitor ({@code Entry.lock}), so {@code pin}/{@code
 * publish} on different ids never contend, and {@code pin} vs {@code publish} on the
 * <em>same</em> id are mutually exclusive — the invariant ADR-039 calls out as "l'unico
 * invariante di concorrenza del sistema".
 */
public final class DefaultResourceRegistry<S, R> implements ResourceRegistry<S, R>, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(DefaultResourceRegistry.class);

    private final Function<S, R> factory;
    private final Consumer<R> closer;
    private final ScheduledExecutorService scheduler;
    private final boolean ownsScheduler;
    private final ConcurrentHashMap<String, Entry<R>> entries = new ConcurrentHashMap<>();

    /** Creates a registry with its own single-thread virtual-thread scheduler for {@link DrainPolicy.Forced} deadlines. */
    public DefaultResourceRegistry(Function<S, R> factory, Consumer<R> closer) {
        this(factory, closer, Executors.newSingleThreadScheduledExecutor(
                Thread.ofVirtual().name("resource-registry-drain").factory()), true);
    }

    /** Creates a registry using a caller-supplied, caller-owned scheduler (not shut down by {@link #close()}). */
    public DefaultResourceRegistry(Function<S, R> factory, Consumer<R> closer, ScheduledExecutorService scheduler) {
        this(factory, closer, scheduler, false);
    }

    private DefaultResourceRegistry(Function<S, R> factory, Consumer<R> closer, ScheduledExecutorService scheduler, boolean ownsScheduler) {
        this.factory       = Objects.requireNonNull(factory, "factory must not be null");
        this.closer        = Objects.requireNonNull(closer,  "closer must not be null");
        this.scheduler      = Objects.requireNonNull(scheduler, "scheduler must not be null");
        this.ownsScheduler = ownsScheduler;
    }

    /** Convenience for the common case where {@code R} does implement {@link AutoCloseable}. */
    public static <S, R extends AutoCloseable> DefaultResourceRegistry<S, R> forCloseable(Function<S, R> factory) {
        return new DefaultResourceRegistry<>(factory, DefaultResourceRegistry::closeAutoCloseable);
    }

    private static <R extends AutoCloseable> void closeAutoCloseable(R resource) {
        try {
            resource.close();
        } catch (Exception e) {
            throw new ResourceCloseException(e);
        }
    }

    private static final class ResourceCloseException extends RuntimeException {
        private static final long serialVersionUID = 5868881508654425403L;

		ResourceCloseException(Throwable cause) { super(cause); }
    }

    @Override
    public Lease<R> pin(String id) {
        Objects.requireNonNull(id, "id must not be null");
        Entry<R> entry = entries.computeIfAbsent(id, k -> new Entry<>());
        synchronized (entry.lock) {
            if (entry.currentVersion == 0) {
                throw new IllegalStateException("No version has ever been published for id '" + id + "'");
            }
            return acquireLocked(entry, entry.currentVersion);
        }
    }

    @Override
    public Lease<R> pin(String id, long version) {
        Objects.requireNonNull(id, "id must not be null");
        Entry<R> entry = entries.get(id);
        if (entry == null) {
            throw new IllegalStateException("Unknown resource id '" + id + "'");
        }
        synchronized (entry.lock) {
            if (!entry.versions.containsKey(version)) {
                throw new IllegalStateException(
                        "Version " + version + " of '" + id + "' is not available "
                        + "(never existed, or already fully closed)");
            }
            return acquireLocked(entry, version);
        }
    }

    @Override
    public Lease<R> pinOrCreate(String id, Supplier<S> specIfAbsent) {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(specIfAbsent, "specIfAbsent must not be null");
        Entry<R> entry = entries.computeIfAbsent(id, k -> new Entry<>());
        synchronized (entry.lock) {
            if (entry.currentVersion == 0) {
                publishLocked(entry, specIfAbsent.get(), DrainPolicy.GRACEFUL);
            }
            return acquireLocked(entry, entry.currentVersion);
        }
    }

    @Override
    public long publish(String id, S spec) {
        return publish(id, spec, DrainPolicy.GRACEFUL);
    }

    @Override
    public long publish(String id, S spec, DrainPolicy policy) {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(spec, "spec must not be null");
        Objects.requireNonNull(policy, "policy must not be null");
        Entry<R> entry = entries.computeIfAbsent(id, k -> new Entry<>());
        synchronized (entry.lock) {
            return publishLocked(entry, spec, policy);
        }
    }

    /**
     * Installs {@code resource} directly as version 1 for {@code id}, without invoking
     * {@code factory} — for seeding a registry with resources built outside its control
     * (e.g. an {@code LlmClient} the caller already constructed). {@code id} must not
     * already have a published version.
     */
    public void seed(String id, R resource) {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(resource, "resource must not be null");
        Entry<R> entry = entries.computeIfAbsent(id, k -> new Entry<>());
        synchronized (entry.lock) {
            if (entry.currentVersion != 0) {
                throw new IllegalStateException("id '" + id + "' already has a published version; use publish() instead");
            }
            long version = ++entry.versionSeq;
            entry.versions.put(version, new VersionState<>(resource));
            entry.currentVersion = version;
        }
    }

    /** Caller must hold {@code entry.lock}. */
    private long publishLocked(Entry<R> entry, S spec, DrainPolicy policy) {
        R resource = factory.apply(spec);
        long newVersion = ++entry.versionSeq;
        entry.versions.put(newVersion, new VersionState<>(resource));

        long oldVersion = entry.currentVersion;
        entry.currentVersion = newVersion;

        if (oldVersion != 0) {
            retire(entry, oldVersion, policy);
        }
        return newVersion;
    }

    /** Caller must hold {@code entry.lock}. */
    private void retire(Entry<R> entry, long version, DrainPolicy policy) {
        VersionState<R> vs = entry.versions.get(version);
        if (vs == null || vs.retired) return;
        vs.retired = true;
        if (vs.refCount == 0) {
            entry.versions.remove(version);
            closeQuietly(vs.resource);
            return;
        }
        if (policy instanceof DrainPolicy.Forced forced) {
            scheduleForcedDrain(entry, vs, forced.deadline());
        }
    }

    private void scheduleForcedDrain(Entry<R> entry, VersionState<R> vs, Instant deadline) {
        long delayMs = deadline == null ? 0 : Math.max(0, deadline.toEpochMilli() - System.currentTimeMillis());
        scheduler.schedule(() -> {
            List<Lease<R>> toEvict;
            synchronized (entry.lock) {
                toEvict = List.copyOf(vs.activeLeases);
            }
            toEvict.forEach(Lease::evict);
        }, delayMs, TimeUnit.MILLISECONDS);
    }

    /** Caller must hold {@code entry.lock}. */
    private Lease<R> acquireLocked(Entry<R> entry, long version) {
        VersionState<R> vs = entry.versions.get(version);
        if (vs == null) {
            throw new IllegalStateException("Version " + version + " is not available");
        }
        Lease<R>[] selfRef = newLeaseHolder();
        Runnable onRelease = () -> release(entry, version, selfRef);
        Lease<R> lease = new Lease<>(vs.resource, onRelease);
        selfRef[0] = lease;
        vs.refCount++;
        vs.activeLeases.add(lease);
        return lease;
    }

    @SuppressWarnings("unchecked")
    private Lease<R>[] newLeaseHolder() {
        return (Lease<R>[]) new Lease<?>[1];
    }

    private void release(Entry<R> entry, long version, Lease<R>[] selfRef) {
        synchronized (entry.lock) {
            VersionState<R> vs = entry.versions.get(version);
            if (vs == null) return;   // already fully closed
            vs.activeLeases.remove(selfRef[0]);
            vs.refCount--;
            if (vs.retired && vs.refCount == 0) {
                entry.versions.remove(version);
                closeQuietly(vs.resource);
            }
        }
    }

    private void closeQuietly(R resource) {
        try {
            closer.accept(resource);
        } catch (RuntimeException e) {
            log.warn("Error closing retired resource in DefaultResourceRegistry", e);
        }
    }

    /** Shuts down the internal scheduler, if this registry created its own. Does not close leased resources. */
    @Override
    public void close() {
        if (ownsScheduler) {
            scheduler.shutdownNow();
        }
    }

    private static final class Entry<R> {
        final Object lock = new Object();
        long currentVersion = 0;   // 0 = nothing published yet
        long versionSeq = 0;
        final Map<Long, VersionState<R>> versions = new HashMap<>();
    }

    private static final class VersionState<R> {
        final R resource;
        int refCount;
        boolean retired;
        final List<Lease<R>> activeLeases = new ArrayList<>();
        VersionState(R resource) { this.resource = resource; }
    }
}
