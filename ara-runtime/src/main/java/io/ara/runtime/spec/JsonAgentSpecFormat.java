package io.ara.runtime.spec;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.util.DefaultIndenter;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.core.util.Separators;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.fasterxml.jackson.databind.json.JsonMapper;
import io.ara.core.spec.AgentSpecFormat;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Set;

/**
 * The JSON {@link AgentSpecFormat}: UTF-8, pretty-printed, one trailing newline.
 *
 * <p>Two parser settings are about not trusting a hand-edited file. A repeated key is an
 * error (JSON parsers usually keep the last one, so a duplicated {@code "tools"} would
 * silently win), and bytes after the document are an error (a stray second object would
 * otherwise be ignored). Both streams are left open: closing is the caller's, since the
 * caller opened them.
 *
 * <p>Thread-safe: the mapper and writer are immutable after construction.
 */
public final class JsonAgentSpecFormat implements AgentSpecFormat {

    private static final JsonMapper MAPPER = JsonMapper.builder()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .disable(JsonParser.Feature.AUTO_CLOSE_SOURCE)
            .build();

    // "key": value, as people write JSON (Jackson's default puts a space before the colon), and an
    // explicit "\n": the default indenter uses the platform's line separator, which would make
    // the same tree produce different bytes on Windows and break the byte-identical export.
    private static final DefaultPrettyPrinter PRINTER = new DefaultPrettyPrinter()
            .withSeparators(Separators.createDefaultInstance()
                    .withObjectFieldValueSpacing(Separators.Spacing.AFTER))
            .withObjectIndenter(new DefaultIndenter("  ", "\n"));

    private static final ObjectWriter WRITER = MAPPER
            .writer(PRINTER)
            .without(JsonGenerator.Feature.AUTO_CLOSE_TARGET);

    @Override
    public String id() {
        return "json";
    }

    @Override
    public Set<String> fileExtensions() {
        return Set.of(".json");
    }

    @Override
    public JsonNode read(InputStream in) throws IOException {
        return MAPPER.readTree(in);
    }

    @Override
    public void write(JsonNode tree, OutputStream out) throws IOException {
        WRITER.writeValue(out, tree);
        out.write('\n');
        out.flush();
    }
}
