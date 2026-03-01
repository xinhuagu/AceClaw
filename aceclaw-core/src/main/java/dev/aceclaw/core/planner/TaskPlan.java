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
     * This is the case when any step has explicit dependencies or multiple root steps exist.
     */
    public boolean hasParallelizableSteps() {
        boolean hasDeps = steps.stream().anyMatch(s -> !s.dependsOn().isEmpty());
        long rootCount = steps.stream().filter(PlannedStep::isRoot).count();
        return hasDeps || rootCount > 1;
    }
}
