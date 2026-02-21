package dev.aceclaw.core.planner;

import dev.aceclaw.core.agent.CancellationToken;
import dev.aceclaw.core.agent.StreamingAgentLoop;
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
    }

    private final PlanEventListener listener;

    public SequentialPlanExecutor() {
        this(null);
    }

    public SequentialPlanExecutor(PlanEventListener listener) {
        this.listener = listener;
    }

    @Override
    public PlanExecutionResult execute(
            TaskPlan plan,
            StreamingAgentLoop agentLoop,
            List<Message> conversationHistory,
            StreamEventHandler handler,
            CancellationToken cancellationToken) throws LlmException {

        long planStart = System.currentTimeMillis();
        var allMessages = new ArrayList<>(
                conversationHistory != null ? conversationHistory : Collections.<Message>emptyList());
        var stepResults = new ArrayList<StepResult>();
        var mutablePlan = plan.withStatus(new PlanStatus.Executing(0, plan.steps().size()));
        boolean allSuccess = true;
        boolean wasCancelled = false;

        for (int i = 0; i < plan.steps().size(); i++) {
            // Check cancellation between steps
            if (cancellationToken != null && cancellationToken.isCancelled()) {
                log.info("Plan execution cancelled before step {}/{}", i + 1, plan.steps().size());
                wasCancelled = true;
                break;
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

                mutablePlan = mutablePlan.withStepStatus(step.stepId(), StepStatus.FAILED)
                        .withStatus(new PlanStatus.Failed(e.getMessage(), step.stepId()));

                if (listener != null) {
                    listener.onStepCompleted(step, i, failResult);
                }

                allSuccess = false;
                break;
            }
        }

        long totalDuration = System.currentTimeMillis() - planStart;
        int totalTokens = stepResults.stream().mapToInt(StepResult::tokensUsed).sum();

        if (wasCancelled) {
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
}
