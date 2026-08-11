package io.ara.runtime.pipeline;

import io.ara.core.agent.AgentResponse;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * The final output of an {@link AgentPipeline} run.
 *
 * @param success       true if the pipeline completed without contract violations or failures
 * @param finalOutput   the output string produced by the last step
 * @param lastResponse  the full {@link AgentResponse} from the last executed step
 * @param stepsExecuted names of the steps executed in execution order
 * @param failureReason non-null when {@code success == false}
 * @param elapsed       total wall-clock duration of the pipeline run
 */
public record PipelineResult(
        boolean       success,
        String        finalOutput,
        AgentResponse lastResponse,
        List<String>  stepsExecuted,
        String        failureReason,
        Instant       completedAt,
        Duration      elapsed
) {

    public PipelineResult {
        Objects.requireNonNull(stepsExecuted, "stepsExecuted must not be null");
        stepsExecuted = List.copyOf(stepsExecuted);
    }

    public static PipelineResult success(
            String finalOutput,
            AgentResponse lastResponse,
            List<String> steps,
            Duration elapsed
    ) {
        return new PipelineResult(true, finalOutput, lastResponse, steps, null, Instant.now(), elapsed);
    }

    public static PipelineResult failure(
            String reason,
            AgentResponse lastResponse,
            List<String> steps,
            Duration elapsed
    ) {
        return new PipelineResult(false,
                lastResponse != null ? lastResponse.content() : "",
                lastResponse, steps, reason, Instant.now(), elapsed);
    }
}
