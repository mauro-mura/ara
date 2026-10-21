package io.ara.runtime.spec;

import com.fasterxml.jackson.databind.JsonNode;
import io.ara.core.spec.DocumentFormat;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The generic registry: it serves formats that are not agent formats (a schedules-only format),
 * and the agent registry built on it still hands back {@code AgentSpecFormat}s.
 */
class DocumentFormatsTest {

    /** A format that knows nothing about agents: only {@link DocumentFormat}. */
    private static class SchedulesOnly implements DocumentFormat {
        @Override public String id() { return "sched"; }
        @Override public Set<String> fileExtensions() { return Set.of(".sched"); }
        @Override public JsonNode read(InputStream in) { throw new UnsupportedOperationException(); }
        @Override public void write(JsonNode tree, OutputStream out) { throw new UnsupportedOperationException(); }
    }

    @Test
    void defaultsKnowJsonByIdAndByExtension() {
        DocumentFormats formats = DocumentFormats.defaults();

        assertSame(formats.byId("json"), formats.forFileName("schedules-20261021.JSON"));
    }

    @Test
    void servesAFormatThatIsNotAnAgentFormat() {
        SchedulesOnly format = new SchedulesOnly();

        DocumentFormats formats = DocumentFormats.defaults().with(format);

        assertSame(format, formats.byId("sched"));
        assertSame(format, formats.forFileName("backup.sched"));
    }

    @Test
    void withDoesNotChangeTheRegistryItWasCalledOn() {
        DocumentFormats base = DocumentFormats.defaults();

        base.with(new SchedulesOnly());

        assertThrows(IllegalArgumentException.class, () -> base.byId("sched"));
    }

    @Test
    void refusesAnIdOrExtensionAlreadyTaken() {
        DocumentFormats formats = DocumentFormats.defaults();

        assertThrows(IllegalArgumentException.class, () -> formats.with(new JsonAgentSpecFormat()));
        assertThrows(IllegalArgumentException.class, () -> formats.with(new SchedulesOnly() {
            @Override public String id() { return "other"; }
            @Override public Set<String> fileExtensions() { return Set.of(".json"); }
        }));
    }

    @Test
    void unknownNamesListWhatIsRegistered() {
        DocumentFormats formats = DocumentFormats.defaults();

        assertTrue(assertThrows(IllegalArgumentException.class, () -> formats.byId("yaml"))
                .getMessage().contains("json"));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> formats.forFileName("noextension"))
                .getMessage().contains(".json"));
    }
}
