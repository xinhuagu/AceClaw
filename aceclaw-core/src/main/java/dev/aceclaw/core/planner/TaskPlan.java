package dev.aceclaw.core.planner;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * An immutable task plan consisting of sequential steps to achieve a goal.
 *
 * @param planId       unique identifier for this plan
 * @param originalGoal the user's original prompt that triggered planning
 * @param steps        ordered list of steps to execute
 * @param status       current lifecycle status of the plan
 * @param createdAt    when this plan was created
 */
public record TaskPlan(
        String planId,
        String originalGoal,
        List<PlannedStep> steps,
        PlanStatus status,
        Instant createdAt
) {

    public TaskPlan {
        steps = List.copyOf(steps);
    }

    /**
     * Returns a copy with the given plan status.
     */
    public TaskPlan withStatus(PlanStatus newStatus) {
        return new TaskPlan(planId, originalGoal, steps, newStatus, createdAt);
    }

    /**
     * Returns a copy with a specific step's status updated.
     */
    public TaskPlan withStepStatus(String stepId, StepStatus newStatus) {
        Objects.requireNonNull(stepId, "stepId");
        var updatedSteps = new ArrayList<PlannedStep>();
        for (var step : steps) {
            if (step.stepId().equals(stepId)) {
                updatedSteps.add(step.withStatus(newStatus));
            } else {
                updatedSteps.add(step);
            }
        }
        return new TaskPlan(planId, originalGoal, updatedSteps, status, createdAt);
    }

    /**
     * Returns the number of completed steps.
     */
    public int completedSteps() {
        return (int) steps.stream()
                .filter(s -> s.status() == StepStatus.COMPLETED)
                .count();
    }

    /**
     * Returns whether all steps have been completed.
     */
    public boolean isComplete() {
        return steps.stream().allMatch(s -> s.status() == StepStatus.COMPLETED
                || s.status() == StepStatus.SKIPPED);
    }

    /**
     * Returns all steps whose dependencies are satisfied and status is PENDING.
     * These steps are ready to execute (either sequentially or in parallel).
     */
    public List<PlannedStep> readySteps() {
        var completedIds = steps.stream()
                .filter(s -> s.status() == StepStatus.COMPLETED || s.status() == StepStatus.SKIPPED)
                .map(PlannedStep::stepId)
                .collect(Collectors.toSet());
        return steps.stream()
                .filter(s -> s.status() == StepStatus.PENDING)
                .filter(s -> completedIds.containsAll(s.dependsOn()))
                .toList();
    }

    /**
     * Returns true if the plan has steps that can benefit from parallel execution.
     * Simulates topological execution layers and checks if any layer has 2+ steps.
     */
    public boolean hasParallelizableSteps() {
        if (steps.size() <= 1) return false;

        // Simulate layer-by-layer execution to check for any parallel frontier
        var completedIds = new java.util.HashSet<String>();
        var remaining = new java.util.HashSet<String>();
        for (var s : steps) remaining.add(s.stepId());

        while (!remaining.isEmpty()) {
            var ready = steps.stream()
                    .filter(s -> remaining.contains(s.stepId()))
                    .filter(s -> completedIds.containsAll(s.dependsOn()))
                    .toList();
            if (ready.isEmpty()) break; // all remaining are blocked (cycle)
            if (ready.size() >= 2) return true;
            for (var s : ready) {
                completedIds.add(s.stepId());
                remaining.remove(s.stepId());
            }
        }
        return false;
    }
}
