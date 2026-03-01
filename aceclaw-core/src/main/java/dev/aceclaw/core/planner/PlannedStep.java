package dev.aceclaw.core.planner;

import java.util.List;
import java.util.Set;

/**
 * A single step in a task plan.
 *
 * @param stepId           unique identifier for this step
 * @param name             short human-readable name (e.g. "Research auth patterns")
 * @param description      detailed description of what this step should accomplish
 * @param requiredTools    list of tool names this step is expected to use
 * @param fallbackApproach alternative approach if the primary approach fails (may be null)
 * @param dependsOn        step IDs this step depends on (must complete before this step can start)
 * @param status           current execution status
 */
public record PlannedStep(
        String stepId,
        String name,
        String description,
        List<String> requiredTools,
        String fallbackApproach,
        Set<String> dependsOn,
        StepStatus status
) {

    public PlannedStep {
        requiredTools = requiredTools != null ? List.copyOf(requiredTools) : List.of();
        dependsOn = dependsOn != null ? Set.copyOf(dependsOn) : Set.of();
        if (status == null) {
            status = StepStatus.PENDING;
        }
    }

    /**
     * Returns a copy with the given status.
     */
    public PlannedStep withStatus(StepStatus newStatus) {
        return new PlannedStep(stepId, name, description, requiredTools, fallbackApproach, dependsOn, newStatus);
    }

    /**
     * Returns true if this step has no dependencies (root step).
     */
    public boolean isRoot() {
        return dependsOn.isEmpty();
    }
}
