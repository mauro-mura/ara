package io.ara.runtime.wiring;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class VersionedTest {

    @Test
    void current_returnsInitialValueUntilSwapped() {
        Versioned<String> v = new Versioned<>("a");
        assertEquals("a", v.current());
    }

    @Test
    void swap_publishesNewValue() {
        Versioned<String> v = new Versioned<>("a");
        String result = v.swap(ignored -> "b");
        assertEquals("b", result);
        assertEquals("b", v.current());
    }

    @Test
    void constructor_rejectsNullInitial() {
        assertThrows(NullPointerException.class, () -> new Versioned<Object>(null));
    }

    @Test
    void swap_isAtomicUnderConcurrentIncrement() throws Exception {
        Versioned<Integer> counter = new Versioned<>(0);
        int threads = 16;
        int incrementsPerThread = 500;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger errors = new AtomicInteger();

        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    for (int j = 0; j < incrementsPerThread; j++) {
                        counter.swap(n -> n + 1);
                    }
                } catch (Exception e) {
                    errors.incrementAndGet();
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertEquals(true, pool.awaitTermination(10, TimeUnit.SECONDS));

        assertEquals(0, errors.get());
        assertEquals(threads * incrementsPerThread, counter.current());
    }
}
