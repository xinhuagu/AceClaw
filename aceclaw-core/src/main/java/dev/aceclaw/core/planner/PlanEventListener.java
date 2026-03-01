package dev.aceclaw.core.planner;

/**
 * Callback for plan execution events (step started, step completed, etc.).
 *
 * <p>Shared by both {@link SequentialPlanExecutor} and DAG-based parallel executors.
 */
public interface PlanEventListener {
    void onStepStarted(PlannedStep step, int stepIndex, int totalSteps);
    void onStepCompleted(PlannedStep step, int stepIndex, StepResult result);
    void onPlanCompleted(TaskPlan plan, boolean success, long totalDurationMs);
}
