package io.ara.core.agent;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * The kind of an {@link ExecutionStep}.
 *
 * <p>Each constant carries the lowercase snake_case token ({@link #wireValue()}) that was
 * the raw {@code String} this type replaces — {@link ExecutionStep#type()} is serialised
 * out to persistence and to any external consumer of an execution trace, so the wire
 * format is a compatibility contract independent of how the JVM-side type is spelled.
 * {@link #wireValue()} is annotated {@code @JsonValue} (Jackson is already a compile
 * dependency of {@code ara-core} — see {@code io.ara.core.tool.ToolCallNormalizer}) so
 * default Jackson (de)serialisation reproduces exactly the {@code String} values the
 * previous {@code ExecutionStep.type} field held, not the Java constant names.
 */
public enum StepType {

    THOUGHT("thought"),
    TOOL_CALL("tool_call"),
    OBSERVATION("observation"),
    FINAL_ANSWER("final_answer"),
    /**
     * A conversational utterance directed at the user that does not end the task the way
     * {@link #FINAL_ANSWER} does — the ReSpAct (Reasoning + Speaking + Acting) action type
     * from Verma et al., 2024 (arXiv:2411.00927). Produced by {@code ReSpActStrategy}.
     */
    SPEAK("speak"),
    /**
     * An in-loop self-correction: a short critique the strategy generated after noticing
     * a stalled or failing trajectory, injected into the same ongoing working memory
     * without resetting anything else — distinct from {@code ReflexionStrategy}'s
     * whole-episode-restart critique, which is not a step within the trace at all (the
     * episode it critiques has already ended). Produced by {@code ReflActStrategy}.
     */
    REFLECTION("reflection");

    private final String wireValue;

    StepType(String wireValue) {
        this.wireValue = wireValue;
    }

    @JsonValue
    public String wireValue() {
        return wireValue;
    }

    /** Inverse of {@link #wireValue()} — accepts exactly the tokens the enum constants declare. */
    @JsonCreator
    public static StepType fromWireValue(String value) {
        for (StepType type : values()) {
            if (type.wireValue.equals(value)) return type;
        }
        throw new IllegalArgumentException("Unknown ExecutionStep type: " + value);
    }

    /** Same as {@link #wireValue()} — so code that formats a step with {@code %s} keeps rendering the wire token. */
    @Override
    public String toString() {
        return wireValue;
    }
}
