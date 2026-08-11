package io.ara.core.agent.processor;

/**
 * Result of an {@link InputProcessor} or {@link OutputProcessor}.
 *
 * <p>Sealed so callers are forced to handle both branches exhaustively via
 * {@code switch} — no accessor that throws on the wrong branch.
 *
 * <pre>{@code
 * switch (processor.process(input)) {
 *     case ProcessingResult.Pass pass     -> input = pass.value();
 *     case ProcessingResult.Reject reject -> return AgentResponse.failure(..., reject.reason());
 * }
 * }</pre>
 */
public sealed interface ProcessingResult
        permits ProcessingResult.Pass, ProcessingResult.Reject {

    record Pass(String value)    implements ProcessingResult {}
    record Reject(String reason) implements ProcessingResult {}

    static ProcessingResult pass(String value)    { return new Pass(value); }
    static ProcessingResult reject(String reason) { return new Reject(reason); }
}
