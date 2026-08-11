package io.ara.runtime.contract;

import io.ara.core.agent.processor.ProcessingResult;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Unit tests for {@link JsonRuleValidator}. */
class JsonRuleValidatorTest {

    @Test
    void passesWhenAntecedentDoesNotHold() {
        var rule = JsonRuleValidator
                .when("verdetto", JsonRuleValidator.equalTo("VIETATO"))
                .then("clausola_verbatim", JsonRuleValidator.notBlank());

        // verdetto != VIETATO → la regola non si applica, anche se clausola_verbatim è vuota
        var result = rule.process("""
                {"verdetto":"CONSENTITO","clausola_verbatim":""}""");

        assertInstanceOf(ProcessingResult.Pass.class, result);
    }

    @Test
    void passesWhenAntecedentAndConsequenceBothHold() {
        var rule = JsonRuleValidator
                .when("verdetto", JsonRuleValidator.equalTo("VIETATO"))
                .then("clausola_verbatim", JsonRuleValidator.notBlank());

        var result = rule.process("""
                {"verdetto":"VIETATO","clausola_verbatim":"USB vietate."}""");

        assertInstanceOf(ProcessingResult.Pass.class, result);
    }

    @Test
    void rejectsWhenAntecedentHoldsButConsequenceDoesNot() {
        var rule = JsonRuleValidator
                .when("verdetto", JsonRuleValidator.equalTo("VIETATO"))
                .then("clausola_verbatim", JsonRuleValidator.notBlank());

        var result = rule.process("""
                {"verdetto":"VIETATO","clausola_verbatim":""}""");

        assertInstanceOf(ProcessingResult.Reject.class, result);
        assertTrue(((ProcessingResult.Reject) result).reason().contains("clausola_verbatim"));
    }

    @Test
    void rejectsWhenConsequenceFieldIsMissingEntirely() {
        var rule = JsonRuleValidator
                .when("verdetto", JsonRuleValidator.equalTo("CONDIZIONATO"))
                .then("condizioni", JsonRuleValidator.nonEmptyArray());

        var result = rule.process("""
                {"verdetto":"CONDIZIONATO"}""");

        assertInstanceOf(ProcessingResult.Reject.class, result);
    }

    @Test
    void nonEmptyArray_passesWithElements() {
        var rule = JsonRuleValidator
                .when("verdetto", JsonRuleValidator.equalTo("CONDIZIONATO"))
                .then("condizioni", JsonRuleValidator.nonEmptyArray());

        var result = rule.process("""
                {"verdetto":"CONDIZIONATO","condizioni":["VPN aziendale","autorizzazione IT"]}""");

        assertInstanceOf(ProcessingResult.Pass.class, result);
    }

    @Test
    void nonEmptyArray_rejectsWhenEmpty() {
        var rule = JsonRuleValidator
                .when("verdetto", JsonRuleValidator.equalTo("CONDIZIONATO"))
                .then("condizioni", JsonRuleValidator.nonEmptyArray());

        var result = rule.process("""
                {"verdetto":"CONDIZIONATO","condizioni":[]}""");

        assertInstanceOf(ProcessingResult.Reject.class, result);
    }

    @Test
    void oneOf_asConsequence() {
        var rule = JsonRuleValidator
                .when("score", JsonRuleValidator.matches("^0\\.9\\d*$|^1(\\.0+)?$"))
                .then("status", JsonRuleValidator.oneOf("APPROVED", "REJECTED"));

        var passResult = rule.process("""
                {"score":0.95,"status":"APPROVED"}""");
        assertInstanceOf(ProcessingResult.Pass.class, passResult);

        var rejectResult = rule.process("""
                {"score":0.95,"status":"PENDING"}""");
        assertInstanceOf(ProcessingResult.Reject.class, rejectResult);
    }

    @Test
    void absentAndPresent_conditions() {
        var rule = JsonRuleValidator
                .when("verdetto", JsonRuleValidator.equalTo("NON_COPERTO"))
                .then("fonte", JsonRuleValidator.absent());

        assertInstanceOf(ProcessingResult.Pass.class, rule.process("""
                {"verdetto":"NON_COPERTO"}"""));

        var rule2 = JsonRuleValidator
                .when("verdetto", JsonRuleValidator.equalTo("VIETATO"))
                .then("fonte", JsonRuleValidator.present());

        assertInstanceOf(ProcessingResult.Reject.class, rule2.process("""
                {"verdetto":"VIETATO"}"""));
    }

    @Test
    void supportsDotPathOnBothSides() {
        var rule = JsonRuleValidator
                .when("result.verdetto", JsonRuleValidator.equalTo("VIETATO"))
                .then("result.clausola_verbatim", JsonRuleValidator.notBlank());

        var result = rule.process("""
                {"result":{"verdetto":"VIETATO","clausola_verbatim":""}}""");

        assertInstanceOf(ProcessingResult.Reject.class, result);
    }

    @Test
    void rejectsWhenPayloadIsNotValidJson() {
        var rule = JsonRuleValidator
                .when("verdetto", JsonRuleValidator.equalTo("VIETATO"))
                .then("clausola_verbatim", JsonRuleValidator.notBlank());

        assertInstanceOf(ProcessingResult.Reject.class, rule.process("not json"));
    }
}
