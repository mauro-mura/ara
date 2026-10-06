package io.ara.runtime.spec;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import io.ara.core.spec.AgentSpec;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The extension point for agents that live somewhere with a schema of its own (a database,
 * say): the adapter maps its columns onto an agent document and calls
 * {@link AgentSpecDocument#decode}. No interface is involved — this test is the proof that
 * the public codec is enough, and it fixes {@code decode(JsonNode)} as API a third party can
 * depend on.
 *
 * <p>The "database" is a list of rows with column names unrelated to ARA's.
 */
class ForeignSourceAdapterTest {

    /** A row as a foreign table might hold it: its own names, its own units. */
    private record AgentRow(String code, String title, String persona, String llm, double heat,
                            int stepBudget, String tools) {}

    /** The adapter: knows the foreign schema and nothing about {@code AgentConfig}. */
    private static ObjectNode toDocument(AgentRow row) {
        JsonNodeFactory json = JsonNodeFactory.instance;
        ObjectNode document = json.objectNode();
        document.put("schemaVersion", 1);
        ObjectNode agent = document.putObject("agent");
        agent.put("type", row.code());
        agent.put("name", row.title());
        agent.put("systemPrompt", row.persona());
        document.putObject("llm").putObject("primary").put("model", row.llm()).put("temperature", row.heat());
        ObjectNode execution = document.putObject("execution");
        execution.put("maxIterations", row.stepBudget());
        execution.putArray("tools").addAll(List.of(row.tools().split(",")).stream().map(json::textNode).toList());
        return document;
    }

    @Test
    void rowsFromAForeignSchema_becomeSpecs_throughThePublicCodec() {
        AgentRow row = new AgentRow("billing-triage", "Billing triage", "Sort billing requests.",
                "main", 0.1, 4, "search,calc");

        AgentSpec spec = AgentSpecDocument.decode(toDocument(row));

        assertEquals("billing-triage", spec.config().agentType());
        assertEquals("Billing triage", spec.config().name());
        assertEquals("main", spec.config().llmProvider());
        assertEquals(0.1, spec.config().temperature());
        assertEquals(4, spec.config().execution().maxIterations());
        assertEquals(List.of("search", "calc"), spec.config().execution().enabledTools());
    }

    @Test
    void aBadRow_isReportedByDocumentPath_soTheAdapterCanAddTheRowKey() {
        AgentRow bad = new AgentRow("billing-triage", "x", "y", "main", 0.1, 0, "search");

        AgentSpecDocumentException error = assertThrows(AgentSpecDocumentException.class,
                () -> AgentSpecDocument.decode(toDocument(bad)));

        // The adapter would wrap this with the row's key; the codec supplies the field.
        assertTrue(error.getMessage().contains("maxIterations"), error.getMessage());
    }

    @Test
    void importingTheSameRowTwice_givesTheSameHash() {
        AgentRow row = new AgentRow("billing-triage", "x", "y", "main", 0.1, 4, "search");

        AgentSpec first = AgentSpecDocument.decode(toDocument(row));
        AgentSpec second = AgentSpecDocument.decode(toDocument(row));

        assertEquals(first.specHash(), second.specHash());
        assertEquals(Map.of(), Map.<String, String>of(), "no lineage is imported: every spec is a fresh root");
        assertEquals(1, second.lineage().specVersion());
    }
}
