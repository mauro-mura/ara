package io.ara.runtime.wiring;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LeaseTest {

    @Test
    void close_runsOnReleaseExactlyOnce() {
        AtomicInteger releases = new AtomicInteger();
        Lease<String> lease = new Lease<>("resource", releases::incrementAndGet);

        lease.close();
        lease.close();
        lease.close();

        assertEquals(1, releases.get());
    }

    @Test
    void evict_runsBoundActionExactlyOnce() {
        AtomicInteger evictions = new AtomicInteger();
        Lease<String> lease = new Lease<>("resource", () -> { });
        lease.bindEvictionTarget(evictions::incrementAndGet);

        lease.evict();
        lease.evict();

        assertEquals(1, evictions.get());
    }

    @Test
    void evict_withoutBoundTarget_isNoOp() {
        Lease<String> lease = new Lease<>("resource", () -> { });
        lease.evict();   // must not throw
    }

    @Test
    void closeAndEvict_areIndependent() {
        AtomicInteger releases = new AtomicInteger();
        AtomicInteger evictions = new AtomicInteger();
        Lease<String> lease = new Lease<>("resource", releases::incrementAndGet, evictions::incrementAndGet);

        lease.evict();
        lease.close();
        lease.evict();
        lease.close();

        assertEquals(1, releases.get());
        assertEquals(1, evictions.get());
    }

    @Test
    void get_returnsTheLeasedResource() {
        Lease<String> lease = new Lease<>("resource", () -> { });
        assertEquals("resource", lease.get());
    }
}
