package dev.aceclaw.core.planner;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

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
}
