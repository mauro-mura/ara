package io.ara.runtime.contract;

import io.ara.core.agent.processor.ProcessingResult;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class JsonSchemaValidatorTest {

    private static final String ORDER_SCHEMA = """
            {
              "type": "object",
              "required": ["id", "status", "items"],
              "additionalProperties": false,
              "properties": {
                "id":     {"type": "string", "pattern": "^ORD-[0-9]+$"},
                "status": {"enum": ["OPEN", "SHIPPED", "CLOSED"]},
                "placed": {"type": "string", "format": "date"},
                "items": {
                  "type": "array",
                  "minItems": 1,
                  "items": {
                    "type": "object",
                    "required": ["sku", "price"],
                    "properties": {
                      "sku":   {"type": "string", "minLength": 3},
                      "price": {"type": "number", "minimum": 0}
                    }
                  }
                }
              }
            }""";

    private static final JsonSchemaValidator ORDER = JsonSchemaValidator.forOutput(ORDER_SCHEMA);

    private static String reject(JsonSchemaValidator v, String payload) {
        ProcessingResult r = v.process(payload);
        return assertInstanceOf(ProcessingResult.Reject.class, r, "expected a rejection").reason();
    }

    private static void pass(JsonSchemaValidator v, String payload) {
        assertInstanceOf(ProcessingResult.Pass.class, v.process(payload));
    }

    // ── forOutput: full schema ────────────────────────────────────────────────

    @Test
    void validPayload_passesUnchanged() {
        String payload = """
                {"id":"ORD-1","status":"OPEN","placed":"2026-10-03","items":[{"sku":"ABC","price":9.5}]}""";
        ProcessingResult r = ORDER.process(payload);
        assertEquals(payload, assertInstanceOf(ProcessingResult.Pass.class, r).value());
    }

    @Test
    void nestedTypeMismatch_isRejectedWithItsPath() {
        String reason = reject(ORDER, """
                {"id":"ORD-1","status":"OPEN","items":[{"sku":"ABC","price":1},{"sku":"DEF","price":"12"}]}""");
        assertTrue(reason.contains("$.items[1].price"), reason);
        assertTrue(reason.contains("number"), reason);
    }

    @Test
    void rejection_carriesIssuesAsData() {
        ProcessingResult.Reject r = assertInstanceOf(ProcessingResult.Reject.class, ORDER.process("""
                {"id":"x","status":"OPEN","items":[{"sku":"ABC","price":"12"}]}"""));

        assertEquals(2, r.issues().size());
        assertTrue(r.issues().stream().anyMatch(i -> i.path().equals("$.id")), r.issues().toString());
        assertTrue(r.issues().stream().anyMatch(i -> i.path().equals("$.items[0].price")), r.issues().toString());
        r.issues().forEach(i -> assertFalse(i.message().isBlank()));
    }

    @Test
    void issues_areCapped_butTheRejectionStillCountsThemAll() {
        StringBuilder items = new StringBuilder();
        int n = JsonSchemaValidator.MAX_ISSUES + 7;
        for (int i = 0; i < n; i++) {
            if (i > 0) items.append(',');
            items.append("{\"sku\":\"ABC\",\"price\":\"bad\"}");
        }
        ProcessingResult.Reject r = assertInstanceOf(ProcessingResult.Reject.class, ORDER.process(
                "{\"id\":\"ORD-1\",\"status\":\"OPEN\",\"items\":[" + items + "]}"));

        assertEquals(JsonSchemaValidator.MAX_ISSUES, r.issues().size());
        assertTrue(r.reason().startsWith("Schema violations (" + n + ")"), r.reason());
    }

    @Test
    void malformedJson_hasNoIssues() {
        ProcessingResult.Reject r = assertInstanceOf(ProcessingResult.Reject.class, ORDER.process("{nope"));
        assertTrue(r.issues().isEmpty());
    }

    @Test
    void enumViolation_isRejected() {
        String reason = reject(ORDER, """
                {"id":"ORD-1","status":"LOST","items":[{"sku":"ABC","price":1}]}""");
        assertTrue(reason.contains("$.status"), reason);
    }

    @Test
    void patternViolation_isRejected() {
        String reason = reject(ORDER, """
                {"id":"1234","status":"OPEN","items":[{"sku":"ABC","price":1}]}""");
        assertTrue(reason.contains("$.id"), reason);
    }

    @Test
    void additionalProperty_isRejected() {
        String reason = reject(ORDER, """
                {"id":"ORD-1","status":"OPEN","items":[{"sku":"ABC","price":1}],"note":"x"}""");
        assertTrue(reason.contains("note"), reason);
    }

    @Test
    void boundsViolations_areRejected() {
        reject(ORDER, """
                {"id":"ORD-1","status":"OPEN","items":[]}""");
        reject(ORDER, """
                {"id":"ORD-1","status":"OPEN","items":[{"sku":"AB","price":1}]}""");
        reject(ORDER, """
                {"id":"ORD-1","status":"OPEN","items":[{"sku":"ABC","price":-1}]}""");
    }

    @Test
    void format_isAsserted() {
        String reason = reject(ORDER, """
                {"id":"ORD-1","status":"OPEN","placed":"next tuesday","items":[{"sku":"ABC","price":1}]}""");
        assertTrue(reason.contains("$.placed"), reason);
    }

    @Test
    void missingRequired_isRejected() {
        String reason = reject(ORDER, """
                {"id":"ORD-1","items":[{"sku":"ABC","price":1}]}""");
        assertTrue(reason.contains("status"), reason);
    }

    @Test
    void everyViolation_isReported_inOneReason() {
        String reason = reject(ORDER, """
                {"id":"x","status":"LOST","items":[{"sku":"A","price":-1}]}""");
        assertTrue(reason.startsWith("Schema violations ("), reason);
        assertTrue(reason.contains("$.id") && reason.contains("$.status")
                && reason.contains("$.items[0].sku") && reason.contains("$.items[0].price"), reason);
    }

    @Test
    void violationsBeyondTheCap_areCountedNotListed() {
        StringBuilder items = new StringBuilder();
        int n = JsonSchemaValidator.MAX_REPORTED_ERRORS + 5;
        for (int i = 0; i < n; i++) {
            if (i > 0) items.append(',');
            items.append("{\"sku\":\"ABC\",\"price\":\"bad\"}");
        }
        String reason = reject(ORDER, "{\"id\":\"ORD-1\",\"status\":\"OPEN\",\"items\":[" + items + "]}");
        assertTrue(reason.contains("and 5 more"), reason);
        assertFalse(reason.contains("$.items[" + (n - 1) + "]"), reason);
    }

    @Test
    void messages_areEnglish_whateverTheDefaultLocale() {
        String reason = reject(ORDER, """
                {"id":"ORD-1","status":"OPEN","items":[{"sku":"ABC","price":"12"}]}""");
        assertTrue(reason.contains("expected"), reason);
    }

    @Test
    void malformedJson_isRejected() {
        assertTrue(reject(ORDER, "{not json").startsWith("Invalid JSON"));
        assertTrue(reject(ORDER, "").startsWith("Invalid JSON"));
    }

    @Test
    void declaredDraft_isHonoured() {
        // draft-04: exclusiveMinimum is a boolean modifier of minimum
        JsonSchemaValidator v = JsonSchemaValidator.forOutput("""
                {"$schema":"http://json-schema.org/draft-04/schema#",
                 "type":"object","properties":{"n":{"type":"number","minimum":0,"exclusiveMinimum":true}}}""");
        pass(v, "{\"n\":1}");
        reject(v, "{\"n\":0}");
    }

    @Test
    void localRef_isResolved() {
        JsonSchemaValidator v = JsonSchemaValidator.forOutput("""
                {"type":"object","properties":{"a":{"$ref":"#/$defs/positive"}},
                 "$defs":{"positive":{"type":"integer","minimum":1}}}""");
        pass(v, "{\"a\":3}");
        assertTrue(reject(v, "{\"a\":0}").contains("$.a"));
    }

    // ── forOutput: construction ───────────────────────────────────────────────

    @Test
    void schemaThatIsNotJson_failsAtConstruction() {
        assertThrows(IllegalArgumentException.class, () -> JsonSchemaValidator.forOutput("{oops"));
    }

    @Test
    void schemaThatIsNotAnObject_failsAtConstruction() {
        assertThrows(IllegalArgumentException.class, () -> JsonSchemaValidator.forOutput("[1,2]"));
    }

    @Test
    void forOutput_exposesTheRawSchema() {
        assertEquals(ORDER_SCHEMA, ORDER.jsonSchema());
    }

    // ── forInput: the same validator, named for the side it guards ─────────────

    @Test
    void forInput_validatesExactlyAsForOutputDoes() {
        JsonSchemaValidator in = JsonSchemaValidator.forInput(ORDER_SCHEMA);

        pass(in, """
                {"id":"ORD-1","status":"OPEN","items":[{"sku":"ABC","price":9.5}]}""");
        assertEquals(reject(ORDER, "{\"id\":\"x\",\"status\":\"LOST\",\"items\":[]}"),
                reject(in, "{\"id\":\"x\",\"status\":\"LOST\",\"items\":[]}"),
                "the same payload gets the same rejection from either name");
    }

    @Test
    void forInput_exposesItsSchema_forTheInputSchemaSlot() {
        io.ara.core.agent.AgentContract contract = io.ara.core.agent.AgentContract.builder()
                .inputSchema(JsonSchemaValidator.forInput(ORDER_SCHEMA)).build();

        assertEquals(ORDER_SCHEMA, contract.inputSchema().jsonSchema());
    }

    @Test
    void forInput_refusesABrokenSchemaAtConstruction_likeForOutput() {
        assertThrows(IllegalArgumentException.class, () -> JsonSchemaValidator.forInput("{oops"));
    }

    // ── jsonOnly / requiring: unchanged behaviour ─────────────────────────────

    @Test
    void jsonOnly_checksWellFormednessOnly() {
        JsonSchemaValidator v = JsonSchemaValidator.jsonOnly();
        pass(v, "{\"anything\":[1,\"two\"]}");
        reject(v, "nope");
        assertThrows(IllegalStateException.class, v::jsonSchema);
    }

    @Test
    void requiring_checksTopLevelFields() {
        JsonSchemaValidator v = JsonSchemaValidator.requiring("a", "b");
        pass(v, "{\"a\":1,\"b\":null}");
        assertEquals("Missing required fields: [b]", reject(v, "{\"a\":1}"));
    }
}
