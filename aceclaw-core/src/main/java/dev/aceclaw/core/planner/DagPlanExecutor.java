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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.StructuredTaskScope;

/**
 * DAG-based parallel plan executor.
 *
 * <p>Steps declare dependencies via {@link PlannedStep#dependsOn()}. Independent steps
 * run concurrently via {@link StructuredTaskScope}, while dependent steps wait for
 * prerequisites to complete.
 *
 * <p>Algorithm:
 * <ol>
 *   <li>Find "ready" steps: status PENDING and all dependencies COMPLETED/SKIPPED</li>
 *   <li>If 1 ready step: execute on the primary loop (conversation continuity)</li>
 *   <li>If 2+ ready steps: fork each with {@code loopFactory.create()}</li>
 *   <li>Collect results, update plan state, repeat</li>
 *   <li>On failure: mark dependent steps SKIPPED, continue independent branches</li>
 * </ol>
 */
public final class DagPlanExecutor implements PlanExecutor {

    private static final Logger log = LoggerFactory.getLogger(DagPlanExecutor.class);

    private final PlanEventListener listener;
    private final AgentLoopFactory loopFactory;

    public DagPlanExecutor(PlanEventListener listener, AgentLoopFactory loopFactory) {
        this.listener = listener;
        this.loopFactory = Objects.requireNonNull(loopFactory, "loopFactory");
    }

    @Override
    public PlanExecutionResult execute(
            TaskPlan plan,
            StreamingAgentLoop primaryLoop,
            List<Message> conversationHistory,
            StreamEventHandler handler,
            CancellationToken cancellationToken) throws LlmException {

        long planStart = System.currentTimeMillis();
        var allMessages = new ArrayList<>(
                conversationHistory != null ? conversationHistory : Collections.<Message>emptyList());
        var stepResults = new ConcurrentHashMap<String, StepResult>();
        var mutablePlan = plan.withStatus(new PlanStatus.Executing(0, plan.steps().size()));
        boolean allSuccess = true;
        boolean wasCancelled = false;

        while (true) {
            // Check cancellation
            if (cancellationToken != null && cancellationToken.isCancelled()) {
                log.info("DAG plan execution cancelled");
                wasCancelled = true;
                break;
            }

            var readySteps = mutablePlan.readySteps();
            if (readySteps.isEmpty()) {
                // No more ready steps — either all done or all remaining are blocked
                break;
            }

            if (readySteps.size() == 1) {
                // Single ready step — execute on primary loop for conversation continuity
                var step = readySteps.getFirst();
                var stepIndex = indexOfStep(mutablePlan, step.stepId());

                var result = executeSingleStep(
                        step, stepIndex, mutablePlan, stepResults, primaryLoop,
                        allMessages, handler, cancellationToken);
                stepResults.put(step.stepId(), result);

                if (result.success()) {
                    mutablePlan = mutablePlan.withStepStatus(step.stepId(), StepStatus.COMPLETED)
                            .withStatus(new PlanStatus.Executing(countCompleted(stepResults), plan.steps().size()));
                } else {
                    mutablePlan = markStepFailed(mutablePlan, step, result, stepResults);
                    if (!hasRemainingWork(mutablePlan)) {
                        allSuccess = false;
                        break;
                    }
                    allSuccess = false;
                }
            } else {
                // Multiple ready steps — execute in parallel
                var batchResults = executeParallelBatch(
                        readySteps, mutablePlan, stepResults, handler, cancellationToken);

                for (var entry : batchResults.entrySet()) {
                    var stepId = entry.getKey();
                    var result = entry.getValue();
                    stepResults.put(stepId, result);

                    if (result.success()) {
                        mutablePlan = mutablePlan.withStepStatus(stepId, StepStatus.COMPLETED);
                    } else {
                        var step = findStep(mutablePlan, stepId);
                        mutablePlan = markStepFailed(mutablePlan, step, result, stepResults);
                        allSuccess = false;
                    }
                }
                mutablePlan = mutablePlan.withStatus(
                        new PlanStatus.Executing(countCompleted(stepResults), plan.steps().size()));

                if (!allSuccess && !hasRemainingWork(mutablePlan)) {
                    break;
                }
            }
        }

        long totalDuration = System.currentTimeMillis() - planStart;
        int totalTokens = stepResults.values().stream().mapToInt(StepResult::tokensUsed).sum();

        // Build ordered result list aligned with plan step order
        var orderedResults = new ArrayList<StepResult>();
        for (var step : plan.steps()) {
            var result = stepResults.get(step.stepId());
            if (result != null) {
                orderedResults.add(result);
            }
        }

        if (wasCancelled) {
            mutablePlan = mutablePlan.withStatus(
                    new PlanStatus.Failed("Cancelled by user", null));
            allSuccess = false;
        } else if (allSuccess && !orderedResults.isEmpty()) {
            mutablePlan = mutablePlan.withStatus(
                    new PlanStatus.Completed(Duration.ofMillis(totalDuration)));
        } else if (!allSuccess) {
            // Find first failed step for the error message
            var failedStep = mutablePlan.steps().stream()
                    .filter(s -> s.status() == StepStatus.FAILED)
                    .findFirst();
            var failedId = failedStep.map(PlannedStep::stepId).orElse(null);
            var failedResult = failedStep.map(s -> stepResults.get(s.stepId())).orElse(null);
            var reason = failedResult != null && failedResult.error() != null
                    ? failedResult.error() : "Step failed";
            mutablePlan = mutablePlan.withStatus(new PlanStatus.Failed(reason, failedId));
        }

        if (listener != null) {
            listener.onPlanCompleted(mutablePlan, allSuccess, totalDuration);
        }

        log.info("DAG plan execution finished: success={}, steps={}/{}, duration={}ms, tokens={}",
                allSuccess, orderedResults.size(), plan.steps().size(), totalDuration, totalTokens);

        return new PlanExecutionResult(mutablePlan, orderedResults, totalDuration, allSuccess, totalTokens);
    }

    private StepResult executeSingleStep(
            PlannedStep step, int stepIndex, TaskPlan plan,
            Map<String, StepResult> previousResults,
            StreamingAgentLoop loop, List<Message> messages,
            StreamEventHandler handler, CancellationToken cancellationToken) throws LlmException {

        log.info("Executing plan step {}/{}: {}", stepIndex + 1, plan.steps().size(), step.name());

        if (listener != null) {
            listener.onStepStarted(step, stepIndex, plan.steps().size());
        }

        long stepStart = System.currentTimeMillis();
        String stepPrompt = buildStepPrompt(step, stepIndex, plan, previousResults);

        try {
            var turn = loop.runTurn(stepPrompt, messages, handler, cancellationToken);
            messages.addAll(turn.newMessages());

            var usage = turn.totalUsage();
            var result = new StepResult(
                    true, turn.text(), null,
                    System.currentTimeMillis() - stepStart,
                    usage.inputTokens(), usage.outputTokens());

            if (listener != null) {
                listener.onStepCompleted(step, stepIndex, result);
            }

            log.info("Step {}/{} completed: {} ({}ms, {} tokens)",
                    stepIndex + 1, plan.steps().size(), step.name(),
                    result.durationMs(), result.tokensUsed());
            return result;

        } catch (LlmException e) {
            log.warn("Step {}/{} failed: {} - {}", stepIndex + 1, plan.steps().size(),
                    step.name(), e.getMessage());

            // Attempt fallback
            if (step.fallbackApproach() != null) {
                log.info("Attempting fallback for step {}: {}", stepIndex + 1, step.fallbackApproach());
                try {
                    String fallbackPrompt = buildFallbackPrompt(step, e.getMessage());
                    var fallbackTurn = loop.runTurn(fallbackPrompt, messages, handler, cancellationToken);
                    messages.addAll(fallbackTurn.newMessages());

                    var fbUsage = fallbackTurn.totalUsage();
                    var fallbackResult = new StepResult(
                            true, fallbackTurn.text(), null,
                            System.currentTimeMillis() - stepStart,
                            fbUsage.inputTokens(), fbUsage.outputTokens());

                    if (listener != null) {
                        listener.onStepCompleted(step, stepIndex, fallbackResult);
                    }

                    log.info("Fallback succeeded for step {}/{}", stepIndex + 1, plan.steps().size());
                    return fallbackResult;

                } catch (LlmException fallbackEx) {
                    log.warn("Fallback also failed for step {}: {}", stepIndex + 1, fallbackEx.getMessage());
                }
            }

            var failResult = new StepResult(
                    false, null, e.getMessage(),
                    System.currentTimeMillis() - stepStart, 0, 0);

            if (listener != null) {
                listener.onStepCompleted(step, stepIndex, failResult);
            }
            return failResult;
        }
    }

    /**
     * Executes multiple steps in parallel using StructuredTaskScope.
     */
    private Map<String, StepResult> executeParallelBatch(
            List<PlannedStep> readySteps,
            TaskPlan plan,
            Map<String, StepResult> previousResults,
            StreamEventHandler handler,
            CancellationToken cancellationToken) throws LlmException {

        log.info("Executing {} steps in parallel: {}",
                readySteps.size(),
                readySteps.stream().map(PlannedStep::name).toList());

        var batchResults = new ConcurrentHashMap<String, StepResult>();

        try (var scope = new StructuredTaskScope.ShutdownOnFailure()) {
            for (var step : readySteps) {
                var stepLoop = loopFactory.create();
                int stepIndex = indexOfStep(plan, step.stepId());

                scope.fork(() -> {
                    var result = executeSingleStep(
                            step, stepIndex, plan, previousResults,
                            stepLoop, new ArrayList<>(), handler, cancellationToken);
                    batchResults.put(step.stepId(), result);
                    return result;
                });
            }

            scope.join();
            // Don't call throwIfFailed — we handle failures individually per step
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LlmException("Parallel execution interrupted", 0, e);
        }

        return batchResults;
    }

    /**
     * Marks a step as failed and all transitive dependents as SKIPPED.
     */
    private TaskPlan markStepFailed(
            TaskPlan plan, PlannedStep failedStep, StepResult failResult,
            Map<String, StepResult> stepResults) {

        var updated = plan.withStepStatus(failedStep.stepId(), StepStatus.FAILED);

        // Skip all transitive dependents
        var toSkip = findTransitiveDependents(plan, failedStep.stepId());
        for (var depId : toSkip) {
            updated = updated.withStepStatus(depId, StepStatus.SKIPPED);
            var depStep = findStep(updated, depId);
            var depIndex = indexOfStep(updated, depId);
            var skipResult = new StepResult(false, null,
                    "Skipped: dependency '" + failedStep.name() + "' failed",
                    0, 0, 0);
            stepResults.put(depId, skipResult);

            if (listener != null && depStep != null) {
                listener.onStepCompleted(depStep, depIndex, skipResult);
            }

            log.info("Skipping step '{}' because dependency '{}' failed",
                    depStep != null ? depStep.name() : depId, failedStep.name());
        }

        return updated;
    }

    /**
     * Finds all transitive dependents of a given step.
     */
    private static List<String> findTransitiveDependents(TaskPlan plan, String stepId) {
        var dependents = new ArrayList<String>();
        var toProcess = new ArrayList<String>();
        toProcess.add(stepId);

        while (!toProcess.isEmpty()) {
            var current = toProcess.removeFirst();
            for (var step : plan.steps()) {
                if (step.dependsOn().contains(current)
                        && !dependents.contains(step.stepId())
                        && !step.stepId().equals(stepId)) {
                    dependents.add(step.stepId());
                    toProcess.add(step.stepId());
                }
            }
        }
        return dependents;
    }

    private static boolean hasRemainingWork(TaskPlan plan) {
        return plan.steps().stream().anyMatch(s -> s.status() == StepStatus.PENDING);
    }

    private static int countCompleted(Map<String, StepResult> results) {
        return (int) results.values().stream().filter(StepResult::success).count();
    }

    private static int indexOfStep(TaskPlan plan, String stepId) {
        for (int i = 0; i < plan.steps().size(); i++) {
            if (plan.steps().get(i).stepId().equals(stepId)) {
                return i;
            }
        }
        return -1;
    }

    private static PlannedStep findStep(TaskPlan plan, String stepId) {
        return plan.steps().stream()
                .filter(s -> s.stepId().equals(stepId))
                .findFirst()
                .orElse(null);
    }

    /**
     * Builds the prompt for a step, including only its direct dependency results.
     */
    static String buildStepPrompt(PlannedStep step, int stepIndex, TaskPlan plan,
                                   Map<String, StepResult> previousResults) {
        var sb = new StringBuilder();
        sb.append("You are executing step ").append(stepIndex + 1)
                .append(" of ").append(plan.steps().size())
                .append(" in a plan to: ").append(plan.originalGoal()).append("\n\n");

        sb.append("## Current Step: ").append(step.name()).append("\n");
        sb.append(step.description()).append("\n\n");

        // Include only direct dependency results
        if (!step.dependsOn().isEmpty()) {
            sb.append("## Dependency Results:\n");
            for (var depId : step.dependsOn()) {
                var depStep = findStep(plan, depId);
                var depResult = previousResults.get(depId);
                if (depStep != null && depResult != null) {
                    sb.append("- ").append(depStep.name());
                    if (depResult.success()) {
                        String output = depResult.output();
                        String summary = output != null && output.length() > 200
                                ? output.substring(0, 200) + "..."
                                : (output != null ? output : "done");
                        sb.append(" - ").append(summary);
                    } else {
                        sb.append(" - FAILED: ").append(
                                depResult.error() != null ? depResult.error() : "Unknown error");
                    }
                    sb.append("\n");
                }
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
