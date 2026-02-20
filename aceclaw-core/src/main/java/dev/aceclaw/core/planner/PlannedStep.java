package dev.aceclaw.core.planner;

import java.util.List;

/**
 * A single step in a task plan.
 *
 * @param stepId           unique identifier for this step
 * @param name             short human-readable name (e.g. "Research auth patterns")
 * @param description      detailed description of what this step should accomplish
 * @param requiredTools    list of tool names this step is expected to use
 * @param fallbackApproach alternative approach if the primary approach fails (may be null)
 * @param status           current execution status
 */
public record PlannedStep(
        String stepId,
        String name,
        String description,
        List<String> requiredTools,
        String fallbackApproach,
        StepStatus status
) {

    public PlannedStep {
        requiredTools = requiredTools != null ? List.copyOf(requiredTools) : List.of();
    }

    /**
     * Returns a copy with the given status.
     */
    public PlannedStep withStatus(StepStatus newStatus) {
        return new PlannedStep(stepId, name, description, requiredTools, fallbackApproach, newStatus);
    }
}
