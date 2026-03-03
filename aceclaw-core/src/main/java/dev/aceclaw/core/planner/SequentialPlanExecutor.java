package dev.aceclaw.core.planner;

import dev.aceclaw.core.agent.CancellationToken;
import dev.aceclaw.core.agent.StreamingAgentLoop;
import dev.aceclaw.core.agent.WatchdogTimer;
import dev.aceclaw.core.llm.LlmException;
import dev.aceclaw.core.llm.Message;
import dev.aceclaw.core.llm.StreamEventHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Executes plan steps sequentially through the agent loop.
 *
 * <p>For each step:
 * <ol>
 *   <li>Build a focused prompt from the step description and prior context</li>
 *   <li>Run {@link StreamingAgentLoop#runTurn(String, List, StreamEventHandler, CancellationToken)}</li>
 *   <li>Evaluate the result</li>
 *   <li>On failure: attempt fallback once (if the step has a fallback approach)</li>
 * </ol>
 *
 * <p>Conversation history flows between steps so each step has context from previous ones.
 * Supports cancellation between steps via {@link CancellationToken}.
 */
public final class SequentialPlanExecutor implements PlanExecutor {

    private static final Logger log = LoggerFactory.getLogger(SequentialPlanExecutor.class);

    /**
     * Callback for plan execution events (step started, step completed, etc.).
     */
    public interface PlanEventListener {
        void onStepStarted(PlannedStep step, int stepIndex, int totalSteps);
        void onStepCompleted(PlannedStep step, int stepIndex, StepResult result);
        void onPlanCompleted(TaskPlan plan, boolean success, long totalDurationMs);

        /** Called when the plan is revised after a step failure. */
        default void onPlanReplanned(TaskPlan oldPlan, TaskPlan newPlan, int replanAttempt, String rationale) {}

        /** Called when replanning determines recovery is impossible. */
        default void onPlanEscalated(TaskPlan plan, String reason) {}
    }

    private final PlanEventListener listener;
    private final AdaptiveReplanner replanner;
    private final WatchdogTimer watchdog;
    private final Duration perStepWallTime;
    private final Duration totalPlanWallTime;

    public SequentialPlanExecutor() {
        this(null, null);
    }

    public SequentialPlanExecutor(PlanEventListener listener) {
        this(listener, null);
    }

    public SequentialPlanExecutor(PlanEventListener listener, AdaptiveReplanner replanner) {
        this(listener, replanner, null, null, null);
    }

    /**
     * Creates an executor with watchdog-based budget enforcement for multi-step plans.
     *
     * @param listener        optional event listener for plan execution events
     * @param replanner       optional adaptive replanner for step failure recovery
     * @param watchdog        optional watchdog timer to reset per-step wall-clock budgets
     * @param perStepWallTime optional per-step wall-clock budget (resets watchdog before each step)
     * @param totalPlanWallTime optional total plan wall-clock budget
     */
    public SequentialPlanExecutor(PlanEventListener listener, AdaptiveReplanner replanner,
                                  WatchdogTimer watchdog, Duration perStepWallTime,
                                  Duration totalPlanWallTime) {
        if (perStepWallTime != null && perStepWallTime.isNegative()) {
            throw new IllegalArgumentException("perStepWallTime must be non-negative");
        }
        if (totalPlanWallTime != null && totalPlanWallTime.isNegative()) {
            throw new IllegalArgumentException("totalPlanWallTime must be non-negative");
        }
        this.listener = listener;
        this.replanner = replanner;
        this.watchdog = watchdog;
        this.perStepWallTime = perStepWallTime;
        this.totalPlanWallTime = totalPlanWallTime;
    }

    @Override
    public PlanExecutionResult execute(
            TaskPlan plan,
            StreamingAgentLoop agentLoop,
            List<Message> conversationHistory,
            StreamEventHandler handler,
            CancellationToken cancellationToken) throws LlmException {

        if (watchdog != null && cancellationToken == null) {
            throw new IllegalArgumentException(
                    "cancellationToken is required when watchdog budgeting is enabled");
        }

        long planStart = System.currentTimeMillis();
        var allMessages = new ArrayList<>(
                conversationHistory != null ? conversationHistory : Collections.<Message>emptyList());
        var stepResults = new ArrayList<StepResult>();
        var mutablePlan = plan.withStatus(new PlanStatus.Executing(0, plan.steps().size()));
        boolean allSuccess = true;
        boolean wasCancelled = false;
        // Global replan budget for the entire plan execution (not per-step).
        // This prevents runaway replanning across multiple failing steps.
        int replanAttempt = 0;

        stepLoop:
        for (int i = 0; i < plan.steps().size(); i++) {
            // Check cancellation between steps.
            // Distinguish watchdog exhaustion from genuine user cancellation.
            if (cancellationToken != null && cancellationToken.isCancelled()) {
                if (watchdog != null && watchdog.isExhausted()) {
                    log.info("Plan cancelled by watchdog ({}) before step {}/{}",
                            watchdog.exhaustionReason(), i + 1, plan.steps().size());
                    mutablePlan = mutablePlan.withStatus(
                            new PlanStatus.Failed(watchdog.exhaustionReason(), null));
                    allSuccess = false;
                    break stepLoop;
                }
                log.info("Plan execution cancelled before step {}/{}", i + 1, plan.steps().size());
                wasCancelled = true;
                break;
            }

            // Enforce total plan wall-clock budget
            if (totalPlanWallTime != null) {
                long elapsed = System.currentTimeMillis() - planStart;
                if (elapsed >= totalPlanWallTime.toMillis()) {
                    log.info("Total plan time budget exhausted ({} ms >= {} ms) before step {}/{}",
                            elapsed, totalPlanWallTime.toMillis(), i + 1, plan.steps().size());
                    mutablePlan = mutablePlan.withStatus(
                            new PlanStatus.Failed("plan_time_budget", null));
                    allSuccess = false;
                    break stepLoop;
                }
            }

            // Reset per-step wall-clock budget on the shared watchdog timer.
            // Cap to remaining total-plan budget so the watchdog fires at the true
            // overall deadline rather than letting a step overrun the total budget.
            if (watchdog != null && perStepWallTime != null) {
                Duration effectiveStepWall = perStepWallTime;
                if (totalPlanWallTime != null) {
                    long remainingMs = totalPlanWallTime.toMillis()
                            - (System.currentTimeMillis() - planStart);
                    if (remainingMs > 0) {
                        effectiveStepWall = Duration.ofMillis(
                                Math.min(perStepWallTime.toMillis(), remainingMs));
                    } else {
                        // Deadline crossed between the pre-step check and here;
                        // use minimal duration so watchdog fires immediately.
                        effectiveStepWall = Duration.ofMillis(1);
                    }
                }
                watchdog.resetWallClock(effectiveStepWall);
            }

            var step = plan.steps().get(i);
            log.info("Executing plan step {}/{}: {}", i + 1, plan.steps().size(), step.name());

            if (listener != null) {
                listener.onStepStarted(step, i, plan.steps().size());
            }

            long stepStart = System.currentTimeMillis();
            String stepPrompt = buildStepPrompt(step, i, plan, stepResults);

            try {
                var turn = agentLoop.runTurn(stepPrompt, allMessages, handler, cancellationToken);
                allMessages.addAll(turn.newMessages());

                var usage = turn.totalUsage();
                var result = new StepResult(
                        true,
                        turn.text(),
                        null,
                        System.currentTimeMillis() - stepStart,
                        usage.inputTokens(),
                        usage.outputTokens());
                stepResults.add(result);

                mutablePlan = mutablePlan.withStepStatus(step.stepId(), StepStatus.COMPLETED)
                        .withStatus(new PlanStatus.Executing(i + 1, plan.steps().size()));

                if (listener != null) {
                    listener.onStepCompleted(step, i, result);
                }

                log.info("Step {}/{} completed: {} ({}ms, {} tokens)",
                        i + 1, plan.steps().size(), step.name(),
                        result.durationMs(), result.tokensUsed());

            } catch (LlmException e) {
                // If the failure was caused by budget/watchdog cancellation,
                // skip fallback and replan — no point in extra LLM work.
                if (cancellationToken != null && cancellationToken.isCancelled()) {
                    String reason = (watchdog != null && watchdog.isExhausted())
                            ? watchdog.exhaustionReason() : "Cancelled by user";
                    log.info("Step {}/{} cancelled mid-execution ({})", i + 1,
                            plan.steps().size(), reason);
                    var cancelResult = new StepResult(false, null, reason,
                            System.currentTimeMillis() - stepStart, 0, 0);
                    stepResults.add(cancelResult);
                    mutablePlan = mutablePlan.withStepStatus(step.stepId(), StepStatus.FAILED)
                            .withStatus(new PlanStatus.Failed(reason, step.stepId()));
                    if (listener != null) {
                        listener.onStepCompleted(step, i, cancelResult);
                    }
                    allSuccess = false;
                    break stepLoop;
                }

                log.warn("Step {}/{} failed: {} - {}", i + 1, plan.steps().size(),
                        step.name(), e.getMessage());

                // Attempt fallback if available
                if (step.fallbackApproach() != null) {
                    log.info("Attempting fallback for step {}: {}", i + 1, step.fallbackApproach());

                    try {
                        String fallbackPrompt = buildFallbackPrompt(step, e.getMessage());
                        var fallbackTurn = agentLoop.runTurn(
                                fallbackPrompt, allMessages, handler, cancellationToken);
                        allMessages.addAll(fallbackTurn.newMessages());

                        var fbUsage = fallbackTurn.totalUsage();
                        var fallbackResult = new StepResult(
                                true,
                                fallbackTurn.text(),
                                null,
                                System.currentTimeMillis() - stepStart,
                                fbUsage.inputTokens(),
                                fbUsage.outputTokens());
                        stepResults.add(fallbackResult);

                        mutablePlan = mutablePlan.withStepStatus(step.stepId(), StepStatus.COMPLETED)
                                .withStatus(new PlanStatus.Executing(i + 1, plan.steps().size()));

                        if (listener != null) {
                            listener.onStepCompleted(step, i, fallbackResult);
                        }

                        log.info("Fallback succeeded for step {}/{}", i + 1, plan.steps().size());
                        continue;

                    } catch (LlmException fallbackEx) {
                        log.warn("Fallback also failed for step {}: {}",
                                i + 1, fallbackEx.getMessage());
                    }
                }

                // Step failed (no fallback or fallback failed)
                var failResult = new StepResult(
                        false,
                        null,
                        e.getMessage(),
                        System.currentTimeMillis() - stepStart,
                        0, 0);
                stepResults.add(failResult);

                mutablePlan = mutablePlan.withStepStatus(step.stepId(), StepStatus.FAILED);

                if (listener != null) {
                    listener.onStepCompleted(step, i, failResult);
                }

                // --- ADAPTIVE REPLAN ---
                if (replanner != null) {
                    replanAttempt++;
                    var completedSummaries = buildCompletedSummaries(plan, stepResults, i);
                    var remaining = (i + 1 < plan.steps().size())
                            ? plan.steps().subList(i + 1, plan.steps().size())
                            : List.<PlannedStep>of();
                    var trigger = new ReplanTrigger(plan.originalGoal(), step, i,
                            e.getMessage(), completedSummaries, remaining, replanAttempt);

                    try {
                        var replanResult = replanner.replan(trigger);
                        switch (replanResult) {
                            case ReplanResult.Revised revised -> {
                                var oldPlan = mutablePlan;
                                // Build new plan: completed steps + revised steps
                                var newSteps = new ArrayList<PlannedStep>();
                                for (int j = 0; j <= i; j++) {
                                    newSteps.add(plan.steps().get(j));
                                }
                                newSteps.addAll(revised.revisedSteps());
                                plan = new TaskPlan(plan.planId(), plan.originalGoal(),
                                        newSteps,
                                        new PlanStatus.Executing(i + 1, newSteps.size()),
                                        plan.createdAt());
                                mutablePlan = plan;
                                if (listener != null) {
                                    listener.onPlanReplanned(oldPlan, plan,
                                            replanAttempt, revised.rationale());
                                }
                                log.info("Plan replanned (attempt {}): {} new steps",
                                        replanAttempt, revised.revisedSteps().size());
                                // Continue the for-loop with updated plan
                                continue;
                            }
                            case ReplanResult.Escalated escalated -> {
                                log.info("Replan escalated: {}", escalated.reason());
                                if (listener != null) {
                                    listener.onPlanEscalated(mutablePlan, escalated.reason());
                                }
                                mutablePlan = mutablePlan.withStatus(
                                        new PlanStatus.Failed(escalated.reason(), step.stepId()));
                                allSuccess = false;
                                break stepLoop;
                            }
                        }
                    } catch (LlmException replanEx) {
                        log.warn("Replan LLM call failed: {}", replanEx.getMessage());
                        mutablePlan = mutablePlan.withStatus(
                                new PlanStatus.Failed(e.getMessage(), step.stepId()));
                        allSuccess = false;
                        break stepLoop;
                    }
                } else {
                    // No replanner -- original behavior: mark failed and break
                    mutablePlan = mutablePlan.withStatus(
                            new PlanStatus.Failed(e.getMessage(), step.stepId()));
                    allSuccess = false;
                    break stepLoop;
                }
            }
        }

        long totalDuration = System.currentTimeMillis() - planStart;
        int totalTokens = stepResults.stream().mapToInt(StepResult::tokensUsed).sum();

        // Post-loop: check if total plan budget was exceeded during the last step
        if (!wasCancelled && allSuccess && totalPlanWallTime != null) {
            if (totalDuration >= totalPlanWallTime.toMillis()) {
                log.info("Total plan time budget exceeded after final step ({} ms >= {} ms)",
                        totalDuration, totalPlanWallTime.toMillis());
                mutablePlan = mutablePlan.withStatus(
                        new PlanStatus.Failed("plan_time_budget", null));
                allSuccess = false;
            }
        }

        if (wasCancelled) {
            // wasCancelled is only set when the token was cancelled by someone
            // other than the watchdog (watchdog exhaustion is handled in-loop).
            mutablePlan = mutablePlan.withStatus(
                    new PlanStatus.Failed("Cancelled by user", null));
            allSuccess = false;
        } else if (allSuccess && !stepResults.isEmpty()) {
            mutablePlan = mutablePlan.withStatus(
                    new PlanStatus.Completed(Duration.ofMillis(totalDuration)));
        }

        if (listener != null) {
            listener.onPlanCompleted(mutablePlan, allSuccess, totalDuration);
        }

        log.info("Plan execution finished: success={}, steps={}/{}, duration={}ms, tokens={}",
                allSuccess, stepResults.size(), plan.steps().size(), totalDuration, totalTokens);

        return new PlanExecutionResult(mutablePlan, stepResults, totalDuration, allSuccess, totalTokens);
    }

    /**
     * Builds the prompt for a specific step, including context from previous steps.
     */
    static String buildStepPrompt(PlannedStep step, int stepIndex, TaskPlan plan,
                                   List<StepResult> previousResults) {
        var sb = new StringBuilder();
        sb.append("You are executing step ").append(stepIndex + 1)
                .append(" of ").append(plan.steps().size())
                .append(" in a plan to: ").append(plan.originalGoal()).append("\n\n");

        sb.append("## Current Step: ").append(step.name()).append("\n");
        sb.append(step.description()).append("\n\n");

        if (!previousResults.isEmpty()) {
            sb.append("## Previous Steps Completed:\n");
            for (int i = 0; i < previousResults.size(); i++) {
                var prev = previousResults.get(i);
                var prevStep = plan.steps().get(i);
                sb.append("- Step ").append(i + 1).append(": ").append(prevStep.name());
                if (prev.success()) {
                    String output = prev.output();
                    String summary = output != null && output.length() > 100
                            ? output.substring(0, 100) + "..."
                            : (output != null ? output : "done");
                    sb.append(" - ").append(summary);
                } else {
                    sb.append(" - FAILED: ").append(
                            prev.error() != null ? prev.error() : "Unknown error");
                }
                sb.append("\n");
            }
            sb.append("\n");
        }

        sb.append("Focus on this step only. When done, summarize what you accomplished.");
        return sb.toString();
    }

    private static String buildFallbackPrompt(PlannedStep step, String error) {
        return """
                The previous attempt at step "%s" failed with: %s

                Please try an alternative approach: %s

                Focus on completing this step. When done, summarize what you accomplished.
                """.formatted(step.name(), error,
                step.fallbackApproach() != null ? step.fallbackApproach() : "try a different method");
    }

    /**
     * Builds summaries of all completed steps up to (and including) the given index.
     */
    private static List<CompletedStepSummary> buildCompletedSummaries(
            TaskPlan plan, List<StepResult> stepResults, int upToIndex) {
        var summaries = new ArrayList<CompletedStepSummary>();
        int limit = Math.min(upToIndex + 1, Math.min(stepResults.size(), plan.steps().size()));
        for (int j = 0; j < limit; j++) {
            summaries.add(CompletedStepSummary.from(plan.steps().get(j), stepResults.get(j)));
        }
        return summaries;
    }
}
