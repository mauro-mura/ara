package io.ara.runtime.contract;

import io.ara.core.agent.processor.ProcessingResult;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Unit tests for {@link JsonFieldValueValidator}. */
class JsonFieldValueValidatorTest {

    // ── oneOf ────────────────────────────────────────────────────────────────

    @Test
    void oneOf_passesWhenValueIsAllowed() {
        var validator = JsonFieldValueValidator.oneOf("status", "APPROVED", "REJECTED");

        var result = validator.process("""
                {"status":"APPROVED"}""");

        assertInstanceOf(ProcessingResult.Pass.class, result);
    }

    @Test
    void oneOf_rejectsWhenValueIsNotAllowed() {
        var validator = JsonFieldValueValidator.oneOf("status", "APPROVED", "REJECTED");

        var result = validator.process("""
                {"status":"PENDING"}""");

        assertInstanceOf(ProcessingResult.Reject.class, result);
        assertTrue(((ProcessingResult.Reject) result).reason().contains("PENDING"));
    }

    @Test
    void oneOf_supportsDotPathIntoNestedField() {
        var validator = JsonFieldValueValidator.oneOf("result.status", "OK", "FAIL");

        var result = validator.process("""
                {"result":{"status":"OK"}}""");

        assertInstanceOf(ProcessingResult.Pass.class, result);
    }

    @Test
    void oneOf_rejectsWhenAllowedValuesEmpty() {
        assertThrows(IllegalArgumentException.class, () -> JsonFieldValueValidator.oneOf("status"));
    }

    // ── inRange ──────────────────────────────────────────────────────────────

    @Test
    void inRange_passesWhenValueWithinBounds() {
        var validator = JsonFieldValueValidator.inRange("score", 0.0, 1.0);

        var result = validator.process("""
                {"score":0.75}""");

        assertInstanceOf(ProcessingResult.Pass.class, result);
    }

    @Test
    void inRange_passesAtInclusiveBoundaries() {
        var validator = JsonFieldValueValidator.inRange("score", 0.0, 1.0);

        assertInstanceOf(ProcessingResult.Pass.class, validator.process("""
                {"score":0}"""));
        assertInstanceOf(ProcessingResult.Pass.class, validator.process("""
                {"score":1}"""));
    }

    @Test
    void inRange_rejectsWhenValueOutOfBounds() {
        var validator = JsonFieldValueValidator.inRange("score", 0.0, 1.0);

        var result = validator.process("""
                {"score":1.5}""");

        assertInstanceOf(ProcessingResult.Reject.class, result);
        assertTrue(((ProcessingResult.Reject) result).reason().contains("1.5"));
    }

    @Test
    void inRange_rejectsWhenFieldIsNotNumeric() {
        var validator = JsonFieldValueValidator.inRange("score", 0.0, 1.0);

        var result = validator.process("""
                {"score":"high"}""");

        assertInstanceOf(ProcessingResult.Reject.class, result);
    }

    @Test
    void inRange_rejectsWhenMinGreaterThanMax() {
        assertThrows(IllegalArgumentException.class,
                () -> JsonFieldValueValidator.inRange("score", 1.0, 0.0));
    }

    // ── shared behaviour ─────────────────────────────────────────────────────

    @Test
    void rejectsWhenFieldMissing() {
        var validator = JsonFieldValueValidator.oneOf("status", "OK");

        var result = validator.process("""
                {"other":"value"}""");

        assertInstanceOf(ProcessingResult.Reject.class, result);
        assertTrue(((ProcessingResult.Reject) result).reason().contains("status"));
    }

    @Test
    void rejectsWhenPayloadIsNotValidJson() {
        var validator = JsonFieldValueValidator.oneOf("status", "OK");

        var result = validator.process("not json");

        assertInstanceOf(ProcessingResult.Reject.class, result);
    }
}
