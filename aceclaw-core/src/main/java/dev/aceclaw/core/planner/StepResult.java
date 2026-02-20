package dev.aceclaw.core.planner;

/**
 * Result of executing a single plan step.
 *
 * @param success    whether the step completed successfully
 * @param output     text output produced during this step
 * @param error      error message if the step failed (null on success)
 * @param durationMs wall-clock time spent on this step
 * @param tokensUsed total tokens consumed by this step
 */
public record StepResult(boolean success, String output, String error, long durationMs, int tokensUsed) {}
