package io.ara.runtime.wiring;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DefaultResourceRegistryTest {

    /** Trivial AutoCloseable resource for asserting build/close counts. */
    private static final class CountingResource implements AutoCloseable {
        final String spec;
        final AtomicInteger closeCount = new AtomicInteger();
        CountingResource(String spec) { this.spec = spec; }
        boolean isClosed() { return closeCount.get() > 0; }
        @Override public void close() { closeCount.incrementAndGet(); }
    }

    private DefaultResourceRegistry<String, CountingResource> newRegistry(AtomicInteger buildCount) {
        return DefaultResourceRegistry.forCloseable(spec -> {
            buildCount.incrementAndGet();
            return new CountingResource(spec);
        });
    }

    @Test
    void pin_beforeAnyPublish_throws() {
        var registry = newRegistry(new AtomicInteger());
        assertThrows(IllegalStateException.class, () -> registry.pin("never-published"));
    }

    @Test
    void pin_unknownExplicitVersion_throws() {
        var registry = newRegistry(new AtomicInteger());
        registry.publish("id", "spec-v1");
        assertThrows(IllegalStateException.class, () -> registry.pin("id", 999));
    }

    @Test
    void pinOrCreate_buildsLazilyExactlyOnceEvenUnderConcurrentCallers() throws Exception {
        AtomicInteger builds = new AtomicInteger();
        var registry = newRegistry(builds);

        int threads = 32;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Lease<CountingResource>> leases = new CopyOnWriteArrayList<>();

        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    leases.add(registry.pinOrCreate("anon", () -> "anon-spec"));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));

        assertEquals(1, builds.get(), "factory must be invoked exactly once for a never-seen id");
        assertEquals(threads, leases.size());
        CountingResource resource = leases.get(0).get();
        for (Lease<CountingResource> lease : leases) {
            assertEquals(resource, lease.get(), "all callers must share the same resource instance");
        }
    }

    @Test
    void publish_retiresOldVersion_closedOnlyAfterLastLeaseReleased() {
        var registry = newRegistry(new AtomicInteger());
        registry.publish("id", "v1");
        Lease<CountingResource> leaseV1 = registry.pin("id");
        CountingResource v1Resource = leaseV1.get();

        registry.publish("id", "v2");   // retires v1; v1 still has an active lease
        assertFalse(v1Resource.isClosed(), "retired version must stay open while a lease is held");

        leaseV1.close();
        assertTrue(v1Resource.isClosed(), "retired version closes once its last lease releases");
    }

    @Test
    void publish_currentVersionIsNeverClosedByReleasingUnrelatedLeases() {
        var registry = newRegistry(new AtomicInteger());
        registry.publish("id", "v1");
        Lease<CountingResource> firstPin = registry.pin("id");
        firstPin.close();   // ref count of the (still current) version drops to zero

        Lease<CountingResource> secondPin = registry.pin("id");
        assertFalse(secondPin.get().isClosed(), "current version must never be closed just because ref count hit zero");
    }

    @Test
    void pin_explicitVersion_resolvesRetiredButStillLeasedVersion() {
        var registry = newRegistry(new AtomicInteger());
        long v1 = registry.publish("id", "v1");
        Lease<CountingResource> keepAlive = registry.pin("id", v1);   // keep v1's ref count above zero
        registry.publish("id", "v2");

        Lease<CountingResource> explicitV1 = registry.pin("id", v1);
        assertEquals(keepAlive.get(), explicitV1.get());
        assertFalse(explicitV1.get().isClosed());
    }

    @Test
    void twoAgentsSameModelId_shareTheSameTransportInstance() {
        var registry = newRegistry(new AtomicInteger());
        registry.publish("modelId", "gpt-4o");
        Lease<CountingResource> leaseA = registry.pin("modelId");
        Lease<CountingResource> leaseB = registry.pin("modelId");
        assertEquals(leaseA.get(), leaseB.get());
    }

    @Test
    void forcedDrainWithNullDeadline_evictsActiveLeasesImmediately() throws Exception {
        var registry = newRegistry(new AtomicInteger());
        registry.publish("id", "v1");
        Lease<CountingResource> leaseV1 = registry.pin("id");
        AtomicInteger evictions = new AtomicInteger();
        leaseV1.bindEvictionTarget(evictions::incrementAndGet);

        registry.publish("id", "v2", new DrainPolicy.Forced(null));

        // scheduled on a virtual-thread executor — give it a moment to run.
        awaitTrue(() -> evictions.get() == 1, 2000);
        assertFalse(leaseV1.get().isClosed(), "evict() must never itself close the resource — only cancels the holder");
    }

    @Test
    void forcedDrainWithDeadline_doesNotEvictLeasesOnTheNewCurrentVersion() throws Exception {
        var registry = newRegistry(new AtomicInteger());
        registry.publish("id", "v1");
        Lease<CountingResource> leaseV1 = registry.pin("id");
        AtomicInteger evictionsV1 = new AtomicInteger();
        leaseV1.bindEvictionTarget(evictionsV1::incrementAndGet);

        registry.publish("id", "v2", new DrainPolicy.Forced(Instant.now().plusMillis(50)));
        Lease<CountingResource> leaseV2 = registry.pin("id");
        AtomicInteger evictionsV2 = new AtomicInteger();
        leaseV2.bindEvictionTarget(evictionsV2::incrementAndGet);

        awaitTrue(() -> evictionsV1.get() == 1, 2000);
        assertEquals(0, evictionsV2.get(), "new current version's leases must never be evicted by an old drain");
    }

    @Test
    void gracefulPublish_neverEvictsAnyLease() throws Exception {
        var registry = newRegistry(new AtomicInteger());
        registry.publish("id", "v1");
        Lease<CountingResource> leaseV1 = registry.pin("id");
        AtomicInteger evictions = new AtomicInteger();
        leaseV1.bindEvictionTarget(evictions::incrementAndGet);

        registry.publish("id", "v2");   // GRACEFUL default

        Thread.sleep(100);
        assertEquals(0, evictions.get());
    }

    @Test
    void concurrentPinAndPublish_neverHandsOutLeaseOnAlreadyClosedResource() throws Exception {
        var registry = newRegistry(new AtomicInteger());
        registry.publish("id", "v0");

        int iterations = 2_000;
        ExecutorService pool = Executors.newFixedThreadPool(4);
        AtomicInteger useAfterCloseErrors = new AtomicInteger();
        CountDownLatch done = new CountDownLatch(2);

        pool.submit(() -> {
            try {
                for (int i = 0; i < iterations; i++) {
                    Lease<CountingResource> lease = registry.pin("id");
                    if (lease.get().isClosed()) {
                        useAfterCloseErrors.incrementAndGet();
                    }
                    lease.close();
                }
            } finally {
                done.countDown();
            }
        });
        pool.submit(() -> {
            try {
                for (int i = 0; i < iterations; i++) {
                    registry.publish("id", "v" + (i + 1));
                }
            } finally {
                done.countDown();
            }
        });

        assertTrue(done.await(30, TimeUnit.SECONDS));
        pool.shutdown();
        assertEquals(0, useAfterCloseErrors.get());
    }

    private static void awaitTrue(java.util.function.BooleanSupplier condition, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) return;
            Thread.sleep(10);
        }
        assertTrue(condition.getAsBoolean(), "condition not met within " + timeoutMs + "ms");
    }
}
