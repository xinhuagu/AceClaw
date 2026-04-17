package dev.aceclaw.core.planner;

import dev.aceclaw.core.llm.RequestAttribution;

/**
 * Result of executing a single plan step.
 *
 * @param success          whether the step completed successfully
 * @param output           text output produced during this step
 * @param error            error message if the step failed (null on success)
 * @param durationMs       wall-clock time spent on this step
 * @param inputTokens      input tokens consumed by this step
 * @param outputTokens     output tokens consumed by this step
 * @param llmRequestCount  number of LLM requests sent while executing this step
 * @param requestAttribution breakdown of {@code llmRequestCount} by {@link dev.aceclaw.core.llm.RequestSource}
 */
public record StepResult(
        boolean success,
        String output,
        String error,
        long durationMs,
        int inputTokens,
        int outputTokens,
        int llmRequestCount,
        RequestAttribution requestAttribution
) {

    public StepResult {
        llmRequestCount = Math.max(0, llmRequestCount);
        requestAttribution = requestAttribution != null ? requestAttribution : RequestAttribution.empty();
    }

    public StepResult(boolean success, String output, String error,
                      long durationMs, int inputTokens, int outputTokens) {
        this(success, output, error, durationMs, inputTokens, outputTokens, 0,
                RequestAttribution.empty());
    }

    public StepResult(boolean success, String output, String error,
                      long durationMs, int inputTokens, int outputTokens,
                      int llmRequestCount) {
        this(success, output, error, durationMs, inputTokens, outputTokens, llmRequestCount,
                RequestAttribution.empty());
    }

    /**
     * Total tokens consumed by this step (input + output).
     */
    public int tokensUsed() {
        return inputTokens + outputTokens;
    }
}
