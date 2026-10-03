package io.ara.core.agent;

import io.ara.core.agent.processor.InputProcessor;
import io.ara.core.agent.processor.MediaValidator;
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
 * {@code outputSchema} changed the canonical constructor. Code using the
 * {@link Builder} requires no changes (new fields default to empty / {@code null}).
 * Direct {@code new AgentContract(...)} calls must be updated.
 *
 * <p>{@code mediaValidators} was added the same way, but with a 4-arg overload retaining the
 * previous shape, so this time direct positional calls keep compiling.
 *
 * <p>{@code inputSchema} (ADR-0077 D1) is the accessor symmetric to {@code outputSchema}:
 * the input schema an agent (or a {@code task_class} whose handoff axis has been promoted,
 * ADR-0055 D1) declares it expects, readable once without duplication. It adds
 * <em>no new enforcement</em> — {@code ContractEnforcer} already rejects a failed
 * {@code inputProcessors()} before {@code execute()} — it only closes the asymmetry where
 * the declared output shape was discoverable and the input one was not. The 5-arg
 * constructor keeps every prior {@code new AgentContract(...)} call compiling with
 * {@code inputSchema = null}.
 *
 * <p>{@code outputRepairAttempts} is how many times a rejected answer is sent back to the
 * agent for correction before the rejection becomes the task's failure. It defaults to
 * {@code 0}: an {@code outputProcessors()} rejection fails the task straight away, exactly
 * as before the field existed. With {@code n > 0}, {@code ContractEnforcer} re-executes the
 * agent up to {@code n} more times, each time with the original input, the rejected answer
 * and the rejection reason, so the model can fix what the validator named. Only an output
 * <em>rejection</em> is repaired — an input rejection, a media rejection and a failure of the
 * agent itself are not, since sending the same request again would not change them. Each
 * attempt is a full {@code execute()}: its tokens and cost are added to the response, and
 * a streaming caller sees the tokens of every attempt. The 6-arg constructor keeps every
 * prior {@code new AgentContract(...)} call compiling with {@code outputRepairAttempts = 0}.
 */
public record AgentContract(
        List<InputProcessor>  inputProcessors,
        List<MediaValidator>  mediaValidators,
        List<PromptShaper>    promptShapers,
        List<OutputProcessor> outputProcessors,
        SchemaProvider        outputSchema,
        SchemaProvider        inputSchema,
        int                   outputRepairAttempts
) {

    public AgentContract {
        inputProcessors  = List.copyOf(Objects.requireNonNullElse(inputProcessors,  List.of()));
        mediaValidators  = List.copyOf(Objects.requireNonNullElse(mediaValidators,  List.of()));
        promptShapers    = List.copyOf(Objects.requireNonNullElse(promptShapers,    List.of()));
        outputProcessors = List.copyOf(Objects.requireNonNullElse(outputProcessors, List.of()));
        // outputSchema / inputSchema may be null
        if (outputRepairAttempts < 0) {
            throw new IllegalArgumentException("outputRepairAttempts must be >= 0, was " + outputRepairAttempts);
        }
    }

    /**
     * 6-arg constructor (the shape before {@code outputRepairAttempts}), kept so every
     * existing direct {@code new AgentContract(...)} call keeps compiling, with repair off.
     */
    public AgentContract(List<InputProcessor> inputProcessors, List<MediaValidator> mediaValidators,
                         List<PromptShaper> promptShapers, List<OutputProcessor> outputProcessors,
                         SchemaProvider outputSchema, SchemaProvider inputSchema) {
        this(inputProcessors, mediaValidators, promptShapers, outputProcessors, outputSchema, inputSchema, 0);
    }

    /**
     * Text-only 4-arg constructor, kept so that adding {@code mediaValidators} leaves every
     * existing direct {@code new AgentContract(...)} call compiling and behaving identically.
     */
    public AgentContract(List<InputProcessor> inputProcessors, List<PromptShaper> promptShapers,
                         List<OutputProcessor> outputProcessors, SchemaProvider outputSchema) {
        this(inputProcessors, List.of(), promptShapers, outputProcessors, outputSchema, null);
    }

    /**
     * 5-arg constructor (the shape before {@code inputSchema}, ADR-0077 D1), kept so every
     * existing direct {@code new AgentContract(...)} call keeps compiling unchanged.
     */
    public AgentContract(List<InputProcessor> inputProcessors, List<MediaValidator> mediaValidators,
                         List<PromptShaper> promptShapers, List<OutputProcessor> outputProcessors,
                         SchemaProvider outputSchema) {
        this(inputProcessors, mediaValidators, promptShapers, outputProcessors, outputSchema, null);
    }

    public static AgentContract empty() {
        return new AgentContract(List.of(), List.of(), List.of(), List.of(), null, null, 0);
    }

    public boolean isEmpty() {
        return inputProcessors.isEmpty()
                && mediaValidators.isEmpty()
                && promptShapers.isEmpty()
                && outputProcessors.isEmpty()
                && outputSchema == null
                && inputSchema == null;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {

        private final List<InputProcessor>  inputs   = new ArrayList<>();
        private final List<MediaValidator>  media    = new ArrayList<>();
        private final List<PromptShaper>    shapers  = new ArrayList<>();
        private final List<OutputProcessor> outputs  = new ArrayList<>();
        private SchemaProvider              schema   = null;
        private SchemaProvider              inSchema = null;
        private int                         repairs  = 0;

        private Builder() {}

        public Builder addInputProcessor(InputProcessor processor) {
            inputs.add(Objects.requireNonNull(processor, "processor must not be null"));
            return this;
        }

        /**
         * Adds a check applied to the task's attachments before execution. Rejection stops the
         * task — see {@link MediaValidator} for why there is no transforming variant.
         */
        public Builder addMediaValidator(MediaValidator validator) {
            media.add(Objects.requireNonNull(validator, "validator must not be null"));
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

        /**
         * The input schema this contract declares (ADR-0077 D1) — symmetric to
         * {@link #outputSchema}. Pass the same {@link SchemaProvider} instance to
         * {@link #addInputProcessor} for it to be enforced; on its own this only makes the
         * declared shape readable.
         */
        public Builder inputSchema(SchemaProvider schemaProvider) {
            this.inSchema = Objects.requireNonNull(schemaProvider, "schemaProvider must not be null");
            return this;
        }

        public Builder addOutputProcessor(OutputProcessor processor) {
            outputs.add(Objects.requireNonNull(processor, "processor must not be null"));
            return this;
        }

        /**
         * How many times a rejected answer is sent back for correction before the rejection
         * fails the task — {@code 0} (the default) disables repair. See the class javadoc.
         *
         * @throws IllegalArgumentException if {@code attempts} is negative
         */
        public Builder outputRepairAttempts(int attempts) {
            if (attempts < 0) {
                throw new IllegalArgumentException("outputRepairAttempts must be >= 0, was " + attempts);
            }
            this.repairs = attempts;
            return this;
        }

        public AgentContract build() {
            return new AgentContract(inputs, media, shapers, outputs, schema, inSchema, repairs);
        }
    }
}
