package io.ara.core.agent.processor;

import java.util.List;
import java.util.Objects;

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
 *
 * <p>A {@link Reject} always carries a human-readable {@code reason}. A processor that can
 * say <em>where</em> the payload is wrong — a schema validator — also lists the individual
 * {@link Issue}s, so a caller that is not reading English prose (an HTTP client, another
 * program) gets them as data rather than by parsing the reason. {@code issues} is empty
 * for every processor that has only a sentence to offer, which is all of them but one.
 */
public sealed interface ProcessingResult
        permits ProcessingResult.Pass, ProcessingResult.Reject {

    record Pass(String value) implements ProcessingResult {}

    /**
     * A rejection.
     *
     * @param reason human-readable summary of why the payload was rejected
     * @param issues the individual problems behind it, possibly empty; never {@code null}
     */
    record Reject(String reason, List<Issue> issues) implements ProcessingResult {

        public Reject {
            issues = List.copyOf(Objects.requireNonNullElse(issues, List.of()));
        }

        /** A rejection with a reason and no itemised issues — the shape before {@code issues}. */
        public Reject(String reason) {
            this(reason, List.of());
        }
    }

    /**
     * One problem found in a payload.
     *
     * @param path    where in the payload, in JSON Path notation ({@code $.items[2].price});
     *                {@code "$"} for the payload as a whole
     * @param message what is wrong there
     */
    record Issue(String path, String message) {

        public Issue {
            Objects.requireNonNull(path, "path must not be null");
            Objects.requireNonNull(message, "message must not be null");
        }
    }

    static ProcessingResult pass(String value)    { return new Pass(value); }
    static ProcessingResult reject(String reason) { return new Reject(reason); }

    static ProcessingResult reject(String reason, List<Issue> issues) {
        return new Reject(reason, issues);
    }
}
