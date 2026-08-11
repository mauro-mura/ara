package io.ara.core.agent.processor;

/**
 * Implemented by components that own a JSON Schema declaration.
 *
 * <p>Allows {@link io.ara.core.agent.AgentContract#outputSchema()} consumers (e.g.
 * {@code OutputFormatEnforcer}) to read the schema from the same object used
 * for output validation — the schema is declared once and never duplicated.
 *
 * <pre>{@code
 * JsonSchemaValidator validator = JsonSchemaValidator.forOutput(personSchema);
 *
 * AgentContract.builder()
 *     .outputSchema(validator)         // reads schema via SchemaProvider
 *     .addOutputProcessor(validator)   // same instance — zero duplication
 *     .build();
 * }</pre>
 */
public interface SchemaProvider {

    /** Returns the raw JSON Schema string declared by this component. */
    String jsonSchema();
}
