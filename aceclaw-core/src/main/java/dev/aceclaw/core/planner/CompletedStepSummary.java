package dev.aceclaw.core.planner;

import java.util.Objects;

/**
 * Lightweight summary of a completed plan step, used as context for replanning.
 *
 * @param stepName      human-readable step name
 * @param description   step description
 * @param success       whether the step completed successfully
 * @param outputSummary truncated output or error message (max 200 chars)
 */
public record CompletedStepSummary(
        String stepName,
        String description,
        boolean success,
        String outputSummary
) {

    private static final int MAX_OUTPUT_SUMMARY_CHARS = 200;

    public CompletedStepSummary {
        Objects.requireNonNull(stepName, "stepName");
        if (outputSummary != null && outputSummary.length() > MAX_OUTPUT_SUMMARY_CHARS) {
            outputSummary = outputSummary.substring(0, MAX_OUTPUT_SUMMARY_CHARS - 3) + "...";
        }
    }

    /**
     * Creates a summary from a planned step and its execution result.
     */
    static CompletedStepSummary from(PlannedStep step, StepResult result) {
        Objects.requireNonNull(step, "step");
        Objects.requireNonNull(result, "result");
        return new CompletedStepSummary(
                step.name(),
                step.description(),
                result.success(),
                result.success() ? result.output() : result.error());
    }
}
