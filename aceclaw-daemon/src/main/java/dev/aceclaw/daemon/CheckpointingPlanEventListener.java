package dev.aceclaw.daemon;

import dev.aceclaw.core.planner.PlanCheckpoint;
import dev.aceclaw.core.planner.PlanCheckpointStore;
import dev.aceclaw.core.planner.PlannedStep;
import dev.aceclaw.core.planner.SequentialPlanExecutor;
import dev.aceclaw.core.planner.StepResult;
import dev.aceclaw.core.planner.StepStatus;
import dev.aceclaw.core.planner.TaskPlan;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * {@link SequentialPlanExecutor.PlanEventListener} wrapper that, in addition
 * to forwarding events to a delegate, persists a {@link PlanCheckpoint} to
 * disk after each step completion / replan / final outcome. Lets a future
 * daemon restart resume from the last successful step instead of replaying
 * the whole plan.
 *
 * <p>Extracted from {@code StreamingAgentHandler} (originally a static inner
 * class). Kept package-private to preserve the original encapsulation —
 * only the handler constructs these.
 */
final class CheckpointingPlanEventListener implements SequentialPlanExecutor.PlanEventListener {

    private static final Logger log = LoggerFactory.getLogger(CheckpointingPlanEventListener.class);

    private final SequentialPlanExecutor.PlanEventListener delegate;
    private final PlanCheckpointStore store;
    private volatile PlanCheckpoint currentCheckpoint;
    private final AgentSession session;
    private final int stepIndexOffset;

    CheckpointingPlanEventListener(
            SequentialPlanExecutor.PlanEventListener delegate,
            PlanCheckpointStore store,
            PlanCheckpoint initialCheckpoint,
            AgentSession session,
            int stepIndexOffset) {
        this.delegate = delegate;
        this.store = store;
        this.currentCheckpoint = initialCheckpoint;
        this.session = session;
        this.stepIndexOffset = stepIndexOffset;
    }

    @Override
    public void onStepStarted(PlannedStep step, int stepIndex, int totalSteps) {
        delegate.onStepStarted(step, stepIndex, totalSteps);
    }

    @Override
    public void onStepCompleted(PlannedStep step, int stepIndex, StepResult result) {
        delegate.onStepCompleted(step, stepIndex, result);

        // Apply offset: stepIndex from executor is 0-based relative to the
        // (possibly partial) plan. For resumed plans, offset maps it back to
        // the absolute index in the original full plan.
        int absoluteIndex = stepIndex + stepIndexOffset;

        // Update and persist checkpoint
        var updatedPlan = currentCheckpoint.plan()
                .withStepStatus(step.stepId(),
                        result.success() ? StepStatus.COMPLETED : StepStatus.FAILED);

        // Serialize current conversation state
        var conversationJson = serializeSessionMessages(session);

        String hint = result.success()
                ? "Step " + (absoluteIndex + 1) + " completed successfully"
                : "Step " + (absoluteIndex + 1) + " failed: "
                        + (result.error() != null ? result.error() : "unknown");

        currentCheckpoint = currentCheckpoint.withStepCompleted(
                absoluteIndex, result, updatedPlan, conversationJson, hint, List.of());

        try {
            store.save(currentCheckpoint);
        } catch (Exception e) {
            log.warn("Failed to persist plan checkpoint after step {}: {}",
                    stepIndex + 1, e.getMessage());
        }
    }

    @Override
    public void onPlanCompleted(TaskPlan plan, boolean success, long totalDurationMs) {
        delegate.onPlanCompleted(plan, success, totalDurationMs);

        currentCheckpoint = currentCheckpoint.withStatus(
                success ? PlanCheckpoint.CheckpointStatus.COMPLETED
                        : PlanCheckpoint.CheckpointStatus.FAILED);
        try {
            store.save(currentCheckpoint);
        } catch (Exception e) {
            log.warn("Failed to persist final plan checkpoint: {}", e.getMessage());
        }
    }

    @Override
    public void onPlanReplanned(TaskPlan oldPlan, TaskPlan newPlan, int attempt, String rationale) {
        delegate.onPlanReplanned(oldPlan, newPlan, attempt, rationale);

        // Update checkpoint with the new plan
        currentCheckpoint = new PlanCheckpoint(
                currentCheckpoint.planId(), currentCheckpoint.sessionId(),
                currentCheckpoint.workspaceHash(), currentCheckpoint.originalGoal(),
                newPlan, currentCheckpoint.completedStepResults(),
                currentCheckpoint.lastCompletedStepIndex(),
                serializeSessionMessages(session),
                currentCheckpoint.status(),
                "Replanned (attempt " + attempt + "): " + rationale,
                currentCheckpoint.artifacts(),
                currentCheckpoint.createdAt(), Instant.now());
        try {
            store.save(currentCheckpoint);
        } catch (Exception e) {
            log.warn("Failed to persist plan checkpoint after replan: {}", e.getMessage());
        }
    }

    @Override
    public void onPlanEscalated(TaskPlan plan, String reason) {
        delegate.onPlanEscalated(plan, reason);

        try {
            store.markFailed(currentCheckpoint.planId());
        } catch (Exception e) {
            log.warn("Failed to mark checkpoint as failed after escalation: {}", e.getMessage());
        }
    }

    @Override
    public void onStepFallback(PlannedStep step, int stepIndex,
                               String fallbackApproach, int attempt) {
        delegate.onStepFallback(step, stepIndex, fallbackApproach, attempt);
    }

    private static List<String> serializeSessionMessages(AgentSession session) {
        // Simple serialization: role:content for each message
        var result = new ArrayList<String>();
        var messages = session.messages();
        if (messages == null) return result;
        for (var msg : messages) {
            switch (msg) {
                case AgentSession.ConversationMessage.User u ->
                        result.add("{\"role\":\"user\",\"content\":" + escapeJson(u.content()) + "}");
                case AgentSession.ConversationMessage.Assistant a ->
                        result.add("{\"role\":\"assistant\",\"content\":" + escapeJson(a.content()) + "}");
                case AgentSession.ConversationMessage.System s ->
                        result.add("{\"role\":\"system\",\"content\":" + escapeJson(s.content()) + "}");
            }
        }
        return result;
    }

    private static String escapeJson(String text) {
        if (text == null) return "null";
        return "\"" + text.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t") + "\"";
    }
}
