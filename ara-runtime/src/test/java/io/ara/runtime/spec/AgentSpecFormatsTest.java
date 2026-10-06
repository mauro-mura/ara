package io.ara.runtime.spec;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.ara.core.spec.AgentSpec;
import io.ara.core.spec.AgentSpecFormat;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The JSON format and the registry that picks formats: bytes in and out, the choice by
 * extension or by name, and the refusal of ambiguous registrations. Includes a stand-in
 * <em>binary</em> format, which is the reason the SPI uses streams and not readers.
 */
class AgentSpecFormatsTest {

    private final JsonAgentSpecFormat json = new JsonAgentSpecFormat();

    /** A format that is not text at all: proof that the interface does not force one. */
    private static class ReversedBytesFormat implements AgentSpecFormat {
        @Override public String id() { return "rev"; }
        @Override public Set<String> fileExtensions() { return Set.of(".rev"); }

        @Override
        public JsonNode read(InputStream in) throws IOException {
            byte[] bytes = in.readAllBytes();
            for (int i = 0; i < bytes.length / 2; i++) {
                byte swap = bytes[i];
                bytes[i] = bytes[bytes.length - 1 - i];
                bytes[bytes.length - 1 - i] = swap;
            }
            return new ObjectMapper().readTree(bytes);
        }

        @Override
        public void write(JsonNode tree, OutputStream out) throws IOException {
            byte[] bytes = tree.toString().getBytes(StandardCharsets.UTF_8);
            for (int i = bytes.length - 1; i >= 0; i--) {
                out.write(bytes[i]);
            }
        }
    }

    // ── JSON ───────────────────────────────────────────────────────────────────────────

    @Test
    void json_writesUtf8_prettyPrinted_withATrailingNewline() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        json.write(new ObjectMapper().readTree("{\"a\":\"è\"}"), out);

        String text = out.toString(StandardCharsets.UTF_8);
        assertTrue(text.contains("\n"), "pretty-printed");
        assertTrue(text.endsWith("\n"));
        assertTrue(text.contains("è"), "UTF-8, not escaped or mangled");
        assertTrue(text.contains("\"a\": "), "colon after the key, not before it");
        assertFalse(text.contains("\r"), "the line separator is \\n on every platform");
    }

    @Test
    void json_sameTreeSameBytes() throws Exception {
        JsonNode tree = AgentSpecDocument.encode(AgentSpecDocumentTest.fullSpec());
        ByteArrayOutputStream first = new ByteArrayOutputStream();
        ByteArrayOutputStream second = new ByteArrayOutputStream();

        json.write(tree, first);
        json.write(AgentSpecDocument.encode(AgentSpecDocument.decode(tree)), second);

        assertArrayEquals(first.toByteArray(), second.toByteArray());
    }

    @Test
    void json_aDuplicateKey_isAnError_notALastOneWins() {
        byte[] bytes = "{\"a\":1,\"a\":2}".getBytes(StandardCharsets.UTF_8);

        assertThrows(IOException.class, () -> json.read(new ByteArrayInputStream(bytes)));
    }

    @Test
    void json_bytesAfterTheDocument_areAnError() {
        byte[] bytes = "{\"a\":1} {\"b\":2}".getBytes(StandardCharsets.UTF_8);

        assertThrows(IOException.class, () -> json.read(new ByteArrayInputStream(bytes)));
    }

    @Test
    void json_doesNotCloseTheCallersStreams() throws Exception {
        AtomicBoolean inClosed = new AtomicBoolean();
        AtomicBoolean outClosed = new AtomicBoolean();
        InputStream in = new ByteArrayInputStream("{}".getBytes(StandardCharsets.UTF_8)) {
            @Override public void close() throws IOException { inClosed.set(true); super.close(); }
        };
        OutputStream out = new ByteArrayOutputStream() {
            @Override public void close() throws IOException { outClosed.set(true); super.close(); }
        };

        json.read(in);
        json.write(new ObjectMapper().createObjectNode(), out);

        assertFalse(inClosed.get());
        assertFalse(outClosed.get());
    }

    // ── registry ───────────────────────────────────────────────────────────────────────

    @Test
    void defaults_knowJson_byIdAndByExtension_caseInsensitively() {
        AgentSpecFormats formats = AgentSpecFormats.defaults();

        assertEquals("json", formats.byId("json").id());
        assertEquals("json", formats.forFileName("agent.json").id());
        assertEquals("json", formats.forFileName("AGENT.JSON").id());
    }

    @Test
    void anUnknownFormat_namesTheRegisteredOnes() {
        AgentSpecFormats formats = AgentSpecFormats.defaults();

        assertTrue(assertThrows(IllegalArgumentException.class, () -> formats.byId("yaml"))
                .getMessage().contains("json"));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> formats.forFileName("agent.yaml"))
                .getMessage().contains(".json"));
        assertThrows(IllegalArgumentException.class, () -> formats.forFileName("noextension"));
    }

    @Test
    void with_returnsANewRegistry_andRefusesAmbiguity() {
        AgentSpecFormats base = AgentSpecFormats.defaults();
        AgentSpecFormats extended = base.with(new ReversedBytesFormat());

        assertEquals("rev", extended.forFileName("a.rev").id());
        assertThrows(IllegalArgumentException.class, () -> base.forFileName("a.rev"), "the original is untouched");
        assertThrows(IllegalArgumentException.class, () -> extended.with(new ReversedBytesFormat()));
        assertThrows(IllegalArgumentException.class, () -> extended.with(new ReversedBytesFormat() {
            @Override public String id() { return "other"; }
        }), "same extension, different id");
    }

    // ── files and streams ──────────────────────────────────────────────────────────────

    @Test
    void writeThenRead_aFile_returnsTheSameConfig(@TempDir Path directory) throws Exception {
        AgentSpec spec = AgentSpecDocumentTest.fullSpec();
        Path file = directory.resolve("triage.json");

        AgentSpecFormats.defaults().write(spec, file);
        AgentSpec back = AgentSpecFormats.defaults().read(file);

        assertEquals(spec.config(), back.config());
        assertTrue(Files.readString(file).startsWith("{"));
    }

    @Test
    void aBinaryFormat_roundTripsThroughTheSameRegistry(@TempDir Path directory) throws Exception {
        AgentSpecFormats formats = AgentSpecFormats.defaults().with(new ReversedBytesFormat());
        AgentSpec spec = AgentSpecDocumentTest.fullSpec();
        Path file = directory.resolve("triage.rev");

        formats.write(spec, file);

        assertFalse(Files.readString(file).startsWith("{"), "the bytes really are not JSON");
        assertEquals(spec.config(), formats.read(file).config());
    }

    @Test
    void readAndWrite_byFormatId_leaveTheStreamsToTheCaller() throws Exception {
        AgentSpecFormats formats = AgentSpecFormats.defaults();
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        formats.write("json", AgentSpecDocumentTest.fullSpec(), out);
        AgentSpec back = formats.read("json", new ByteArrayInputStream(out.toByteArray()));

        assertEquals(AgentSpecDocumentTest.fullSpec().config().agentType(), back.config().agentType());
        assertSame(formats.byId("json"), formats.byId("json"));
    }

    @Test
    void read_aFileWithABadDocument_failsWithThePathNotAnIoError(@TempDir Path directory) throws Exception {
        Path file = directory.resolve("bad.json");
        Files.writeString(file, "{\"schemaVersion\":1,\"agent\":{\"type\":\"a\"},\"nope\":1}");

        AgentSpecDocumentException error = assertThrows(AgentSpecDocumentException.class,
                () -> AgentSpecFormats.defaults().read(file));

        assertEquals("nope", error.path());
    }

    @Test
    void read_aFileThatIsNotJson_isAnIoError(@TempDir Path directory) throws Exception {
        Path file = directory.resolve("broken.json");
        Files.writeString(file, "{not json");

        assertThrows(IOException.class, () -> AgentSpecFormats.defaults().read(file));
    }
}
