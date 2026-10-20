package io.ara.runtime.wiring;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DefaultResourceRegistryPublishListenerTest {

    private static DefaultResourceRegistry<String, AutoCloseable> newRegistry() {
        return DefaultResourceRegistry.forCloseable(spec -> () -> { });
    }

    @Test
    void forcedPublish_notifiesListenerAsForcedRetirement() {
        var registry = newRegistry();
        List<PublishEvent> seen = new CopyOnWriteArrayList<>();
        registry.addPublishListener(seen::add);

        registry.publish("llm", "v1");
        registry.publish("llm", "v2", new DrainPolicy.Forced(null));

        assertEquals(2, seen.size());
        assertFalse(seen.get(0).isForcedRetirement(), "first publish retires nothing");
        assertEquals(0, seen.get(0).retiredVersion());
        assertTrue(seen.get(1).isForcedRetirement());
        assertEquals("llm", seen.get(1).id());
        assertEquals(2, seen.get(1).version());
        assertEquals(1, seen.get(1).retiredVersion());
    }

    @Test
    void gracefulPublish_isNotAForcedRetirement() {
        var registry = newRegistry();
        List<PublishEvent> seen = new CopyOnWriteArrayList<>();
        registry.addPublishListener(seen::add);

        registry.publish("llm", "v1");
        registry.publish("llm", "v2");

        assertFalse(seen.get(1).isForcedRetirement());
    }

    @Test
    void firstPublishUnderForced_retiresNothing() {
        var registry = newRegistry();
        List<PublishEvent> seen = new CopyOnWriteArrayList<>();
        registry.addPublishListener(seen::add);

        registry.publish("llm", "v1", new DrainPolicy.Forced(null));

        assertFalse(seen.get(0).isForcedRetirement());
    }

    @Test
    void throwingListener_doesNotBreakPublishNorOtherListeners() {
        var registry = newRegistry();
        List<PublishEvent> seen = new CopyOnWriteArrayList<>();
        registry.addPublishListener(e -> { throw new IllegalStateException("boom"); });
        registry.addPublishListener(seen::add);

        assertEquals(1, registry.publish("llm", "v1"));
        assertEquals(1, seen.size());
    }

    @Test
    void listenerMayCallBackIntoTheRegistry() {
        var registry = newRegistry();
        registry.publish("llm", "v1");
        registry.addPublishListener(e -> registry.pin(e.id()).close());

        registry.publish("llm", "v2", new DrainPolicy.Forced(null));
    }

    @Test
    void defaultInterfaceMethod_rejectsRegistriesThatCannotObserve() {
        ResourceRegistry<String, String> bare = new ResourceRegistry<>() {
            public Lease<String> pin(String id) { return null; }
            public Lease<String> pin(String id, long version) { return null; }
            public Lease<String> pinOrCreate(String id, java.util.function.Supplier<String> s) { return null; }
            public long publish(String id, String spec) { return 0; }
            public long publish(String id, String spec, DrainPolicy policy) { return 0; }
        };
        assertThrows(UnsupportedOperationException.class, () -> bare.addPublishListener(e -> { }));
    }
}
