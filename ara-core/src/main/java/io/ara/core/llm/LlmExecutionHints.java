package io.ara.core.llm;

import java.util.List;

/**
 * Per-invocation LLM execution parameters.
 *
 * <p>Carries parameters that vary from one call to the next from the caller down to the
 * strategy. Read by the strategy to build the {@link LlmCallContext}.
 *
 * <p>Not part of the data contract ({@link io.ara.core.agent.AgentContract}): it concerns
 * <em>how</em> the model is called, not <em>what shape</em> the output must have.
 * The channel is {@link io.ara.core.agent.AgentTask#hints()}.
 */
public record LlmExecutionHints(
        String       outputJsonSchema,
        String       outputSchemaName,
        boolean      strictSchema,
        Double       temperatureOverride,
        List<String> stopSequences,
        Integer      seed,
        String       llmProviderOverride
) {
    public static LlmExecutionHints empty() {
        return new LlmExecutionHints(null, null, false, null, List.of(), null, null);
    }

    public static LlmExecutionHints forSchema(String jsonSchema, String name, boolean strict) {
        return new LlmExecutionHints(jsonSchema, name, strict, null, List.of(), null, null);
    }

    public static LlmExecutionHints forSchema(String jsonSchema) {
        return forSchema(jsonSchema, "output", false);
    }

    public static LlmExecutionHints forTemperature(double temperature) {
        return new LlmExecutionHints(null, null, false, temperature, List.of(), null, null);
    }

    public static LlmExecutionHints forProvider(String providerId) {
        return new LlmExecutionHints(null, null, false, null, List.of(), null, providerId);
    }

    public static LlmExecutionHints forSeed(int seed) {
        return new LlmExecutionHints(null, null, false, null, List.of(), seed, null);
    }

    public boolean hasOutputSchema()     { return outputJsonSchema != null; }
    public boolean hasStopSequences()    { return stopSequences != null && !stopSequences.isEmpty(); }
    public boolean hasSeed()             { return seed != null; }
    public boolean hasProviderOverride() { return llmProviderOverride != null; }

    public LlmExecutionHints withOutputSchema(String schema, String name, boolean strict) {
        return new LlmExecutionHints(schema, name, strict,
                temperatureOverride, stopSequences, seed, llmProviderOverride);
    }

    public LlmExecutionHints withTemperature(double temperature) {
        return new LlmExecutionHints(outputJsonSchema, outputSchemaName, strictSchema,
                temperature, stopSequences, seed, llmProviderOverride);
    }

    public LlmExecutionHints withStopSequences(List<String> stops) {
        return new LlmExecutionHints(outputJsonSchema, outputSchemaName, strictSchema,
                temperatureOverride, stops, seed, llmProviderOverride);
    }

    public LlmExecutionHints withSeed(int seed) {
        return new LlmExecutionHints(outputJsonSchema, outputSchemaName, strictSchema,
                temperatureOverride, stopSequences, seed, llmProviderOverride);
    }

    public LlmExecutionHints withProvider(String providerId) {
        return new LlmExecutionHints(outputJsonSchema, outputSchemaName, strictSchema,
                temperatureOverride, stopSequences, seed, providerId);
    }
}
