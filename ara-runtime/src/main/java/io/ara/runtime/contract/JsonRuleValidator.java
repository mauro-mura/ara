package io.ara.runtime.contract;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.ara.core.agent.processor.InputProcessor;
import io.ara.core.agent.processor.OutputProcessor;
import io.ara.core.agent.processor.ProcessingResult;

import java.util.Arrays;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Validates a conditional ({@code if / then}) rule across two fields of a JSON payload.
 *
 * <p>Fields are addressed with the same dot-path notation as {@link JsonFieldExtractor}
 * and {@link JsonFieldValueValidator}. The rule is vacuously satisfied when the
 * antecedent ({@code when}) does not hold — only when it holds is the consequence
 * ({@code then}) checked. Can be used as both an {@link InputProcessor} and an
 * {@link OutputProcessor}; each instance encodes exactly one rule, composed like any
 * other processor via {@code AgentContract.builder().addOutputProcessor(...)}.
 *
 * <pre>{@code
 * // se il verdetto è VIETATO, la clausola citata non può essere vuota
 * JsonRuleValidator.when("verdetto", JsonRuleValidator.equalTo("VIETATO"))
 *                   .then("clausola_verbatim", JsonRuleValidator.notBlank());
 *
 * // se il verdetto è CONDIZIONATO, devono esserci condizioni elencate
 * JsonRuleValidator.when("verdetto", JsonRuleValidator.equalTo("CONDIZIONATO"))
 *                   .then("condizioni", JsonRuleValidator.nonEmptyArray());
 *
 * // se lo score supera la soglia, lo stato non può restare PENDING
 * JsonRuleValidator.when("score", JsonRuleValidator.matches("0\\.9\\d*|1(\\.0+)?"))
 *                   .then("status", JsonRuleValidator.oneOf("APPROVED", "REJECTED"));
 * }</pre>
 */
public final class JsonRuleValidator implements InputProcessor, OutputProcessor {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Predicate over a JSON field node; {@code node} is {@code null} when the field is absent or JSON null. */
    @FunctionalInterface
    public interface FieldCondition {
        boolean test(JsonNode node);

        /** Human-readable description used in rejection messages. Defaults to {@code toString()}. */
        default String describe() { return toString(); }
    }

    private final String         ifField;
    private final FieldCondition ifCondition;
    private final String         thenField;
    private final FieldCondition thenCondition;

    private JsonRuleValidator(String ifField, FieldCondition ifCondition,
                               String thenField, FieldCondition thenCondition) {
        this.ifField       = ifField;
        this.ifCondition   = ifCondition;
        this.thenField     = thenField;
        this.thenCondition = thenCondition;
    }

    /** Starts a rule: {@code when(field, condition).then(field, condition)}. */
    public static WhenClause when(String field, FieldCondition condition) {
        Objects.requireNonNull(field, "field must not be null");
        Objects.requireNonNull(condition, "condition must not be null");
        return new WhenClause(field, condition);
    }

    /** Intermediate builder step returned by {@link #when(String, FieldCondition)}. */
    public static final class WhenClause {
        private final String         ifField;
        private final FieldCondition ifCondition;

        private WhenClause(String ifField, FieldCondition ifCondition) {
            this.ifField     = ifField;
            this.ifCondition = ifCondition;
        }

        /** Completes the rule: rejects the payload if the antecedent holds but the consequence does not. */
        public JsonRuleValidator then(String field, FieldCondition condition) {
            Objects.requireNonNull(field, "field must not be null");
            Objects.requireNonNull(condition, "condition must not be null");
            return new JsonRuleValidator(ifField, ifCondition, field, condition);
        }
    }

    // ── Reusable field conditions ────────────────────────────────────────────

    public static FieldCondition equalTo(String value) {
        return named(node -> node != null && node.asText().equals(value), "equal to '" + value + "'");
    }

    public static FieldCondition oneOf(String... values) {
        Set<String> allowed = Set.copyOf(Arrays.asList(values));
        return named(node -> node != null && allowed.contains(node.asText()), "one of " + allowed);
    }

    public static FieldCondition notBlank() {
        return named(node -> node != null && !node.asText("").isBlank(), "not blank");
    }

    public static FieldCondition blank() {
        return named(node -> node == null || node.asText("").isBlank(), "blank");
    }

    public static FieldCondition matches(String regex) {
        Pattern pattern = Pattern.compile(regex);
        return named(node -> node != null && pattern.matcher(node.asText("")).find(),
                "matching /" + regex + "/");
    }

    public static FieldCondition present() {
        return named(Objects::nonNull, "present");
    }

    public static FieldCondition absent() {
        return named(Objects::isNull, "absent");
    }

    public static FieldCondition nonEmptyArray() {
        return named(node -> node != null && node.isArray() && !node.isEmpty(), "a non-empty array");
    }

    private static FieldCondition named(FieldCondition delegate, String description) {
        return new FieldCondition() {
            @Override public boolean test(JsonNode node) { return delegate.test(node); }
            @Override public String describe()           { return description; }
        };
    }

    // ── InputProcessor / OutputProcessor ─────────────────────────────────────

    @Override
    public ProcessingResult process(String payload) {
        JsonNode root;
        try {
            root = MAPPER.readTree(payload);
        } catch (Exception e) {
            return ProcessingResult.reject("Payload is not valid JSON: " + e.getMessage());
        }

        JsonNode ifNode = resolve(root, ifField);
        if (!ifCondition.test(ifNode)) {
            return ProcessingResult.pass(payload); // antecedent false: rule does not apply
        }

        JsonNode thenNode = resolve(root, thenField);
        if (!thenCondition.test(thenNode)) {
            return ProcessingResult.reject(
                    "Rule violated: when '%s' is %s, '%s' must be %s"
                            .formatted(ifField, ifCondition.describe(), thenField, thenCondition.describe()));
        }
        return ProcessingResult.pass(payload);
    }

    private static JsonNode resolve(JsonNode root, String fieldPath) {
        JsonNode node = root;
        for (String part : fieldPath.split("\\.")) {
            if (node == null || node.isMissingNode()) return null;
            node = node.get(part);
        }
        return (node == null || node.isNull() || node.isMissingNode()) ? null : node;
    }
}
