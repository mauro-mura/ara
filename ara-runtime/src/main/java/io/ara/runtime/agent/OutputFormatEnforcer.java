package io.ara.runtime.agent;

import io.ara.core.agent.processor.SchemaProvider;

import java.util.Objects;

/**
 * Package-private. Appends JSON format instructions to the system prompt
 * based on the schema declared in {@code AgentContract.outputSchema()}.
 *
 * <p>Used internally by {@link ContractEnforcingAgent} — not part of the public API.
 * Developers declare structured output via {@code AgentContract.Builder.outputSchema()}.
 *
 * <p>Stateless: exposed as a static function rather than a one-field object, since the
 * schema was only ever a constructor parameter forwarded straight to the single method.
 */
final class OutputFormatEnforcer {

    private static final String INSTRUCTION_PREFIX =
            "\n\nIMPORTANT: Respond ONLY with a single valid JSON object matching this schema:\n";

    private static final String INSTRUCTION_SUFFIX =
            "\nDo not include any other text, markdown, or explanation.";

    private OutputFormatEnforcer() {}

    /**
     * Returns {@code systemPrompt} with JSON format instructions appended.
     *
     * @param systemPrompt the prompt to extend; {@code null} is treated as empty
     * @param schema       the schema to instruct against; must not be {@code null}
     */
    static String apply(String systemPrompt, SchemaProvider schema) {
        Objects.requireNonNull(schema, "schema must not be null");
        return (systemPrompt == null ? "" : systemPrompt)
                + INSTRUCTION_PREFIX
                + schema.jsonSchema().strip()
                + INSTRUCTION_SUFFIX;
    }
}
