package dev.aceclaw.core.planner;

/**
 * Execution status of a single plan step.
 */
public enum StepStatus {
    PENDING,
    IN_PROGRESS,
    COMPLETED,
    FAILED,
    SKIPPED
}
