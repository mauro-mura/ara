package io.ara.core.agent;

import io.ara.core.agent.processor.InputProcessor;
import io.ara.core.agent.processor.OutputProcessor;
import io.ara.core.agent.processor.PromptShaper;
import io.ara.core.agent.processor.SchemaProvider;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Declares the I/O contract of an agent: ordered chains of deterministic processors
 * applied before and after every {@code execute()} call, optional prompt shapers
 * applied to the system prompt, and an optional structured output schema.
 *
 * <p>The contract travels with the agent — it is not part of {@link AgentConfig}
 * (which is declarative configuration) but an orthogonal structural interface.
 * Applied by {@code ContractEnforcingAgent},
 * a decorator registered in the {@code AgentRegistry}
 * in place of the raw {@code AgentInstance}, so any caller — direct Java or the
 * {@link io.ara.core.bus.MessageBus} — always passes through it.
 *
 * <p>Usage (ADR-012 + ADR-014):
 * <pre>{@code
 * JsonSchemaValidator validator = JsonSchemaValidator.forOutput(personSchema);
 *
 * AgentContract contract = AgentContract.builder()
 *     .addPromptShaper(PromptTemplate.withDefaults(Map.of("lang", "italiano")))
 *     .outputSchema(validator)                    // forces JSON output + synced with validation
 *     .addOutputProcessor(MarkdownFenceStripper.instance())
 *     .addOutputProcessor(validator)
 *     .build();
 * }</pre>
 *
 * <p><strong>Breaking change note</strong>: adding {@code promptShapers} and
 * {@code outputSchema} changes the canonical constructor. Code using the
 * {@link Builder} requires no changes (new fields default to empty / {@code null}).
 * Direct {@code new AgentContract(...)} calls must be updated.
 */
public record AgentContract(
        List<InputProcessor>  inputProcessors,
        List<PromptShaper>    promptShapers,
        List<OutputProcessor> outputProcessors,
        SchemaProvider        outputSchema
) {

    public AgentContract {
        inputProcessors  = List.copyOf(Objects.requireNonNullElse(inputProcessors,  List.of()));
        promptShapers    = List.copyOf(Objects.requireNonNullElse(promptShapers,    List.of()));
        outputProcessors = List.copyOf(Objects.requireNonNullElse(outputProcessors, List.of()));
        // outputSchema may be null
    }

    public static AgentContract empty() {
        return new AgentContract(List.of(), List.of(), List.of(), null);
    }

    public boolean isEmpty() {
        return inputProcessors.isEmpty()
                && promptShapers.isEmpty()
                && outputProcessors.isEmpty()
                && outputSchema == null;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {

        private final List<InputProcessor>  inputs   = new ArrayList<>();
        private final List<PromptShaper>    shapers  = new ArrayList<>();
        private final List<OutputProcessor> outputs  = new ArrayList<>();
        private SchemaProvider              schema   = null;

        private Builder() {}

        public Builder addInputProcessor(InputProcessor processor) {
            inputs.add(Objects.requireNonNull(processor, "processor must not be null"));
            return this;
        }

        public Builder addPromptShaper(PromptShaper shaper) {
            shapers.add(Objects.requireNonNull(shaper, "shaper must not be null"));
            return this;
        }

        public Builder outputSchema(SchemaProvider schemaProvider) {
            this.schema = Objects.requireNonNull(schemaProvider, "schemaProvider must not be null");
            return this;
        }

        public Builder addOutputProcessor(OutputProcessor processor) {
            outputs.add(Objects.requireNonNull(processor, "processor must not be null"));
            return this;
        }

        public AgentContract build() {
            return new AgentContract(inputs, shapers, outputs, schema);
        }
    }
}
