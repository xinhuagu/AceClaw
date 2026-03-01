package dev.aceclaw.core.planner;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Immutable snapshot of in-progress plan execution state.
 * Persisted after each step completion for crash recovery.
 *
 * @param planId                  the plan identifier
 * @param sessionId               session that created this checkpoint
 * @param workspaceHash           SHA-256 of the canonical workspace path (for cross-session matching)
 * @param originalGoal            the user's original prompt
 * @param plan                    full TaskPlan with updated step statuses
 * @param completedStepResults    results for each completed step (index-aligned with plan.steps)
 * @param lastCompletedStepIndex  zero-based index of the last completed step (-1 if none)
 * @param conversationSnapshot    serialized conversation history at checkpoint time (JSON per message)
 * @param status                  checkpoint lifecycle status
 * @param resumeHint              human-readable hint about what to do next
 * @param artifacts               list of file paths or identifiers produced so far
 * @param createdAt               when the checkpoint was first created
 * @param updatedAt               when the checkpoint was last updated
 */
public record PlanCheckpoint(
        String planId,
        String sessionId,
        String workspaceHash,
        String originalGoal,
        TaskPlan plan,
        List<StepResult> completedStepResults,
        int lastCompletedStepIndex,
        List<String> conversationSnapshot,
        CheckpointStatus status,
        String resumeHint,
        List<String> artifacts,
        Instant createdAt,
        Instant updatedAt
) {

    public PlanCheckpoint {
        Objects.requireNonNull(planId, "planId");
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(workspaceHash, "workspaceHash");
        Objects.requireNonNull(originalGoal, "originalGoal");
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(status, "status");
        completedStepResults = completedStepResults != null
                ? List.copyOf(completedStepResults) : List.of();
        conversationSnapshot = conversationSnapshot != null
                ? List.copyOf(conversationSnapshot) : List.of();
        artifacts = artifacts != null ? List.copyOf(artifacts) : List.of();
    }

    /**
     * Returns the next step index to execute (lastCompletedStepIndex + 1).
     */
    public int nextStepIndex() {
        return lastCompletedStepIndex + 1;
    }

    /**
     * Returns true if there are remaining steps to execute.
     */
    public boolean hasRemainingSteps() {
        return nextStepIndex() < plan.steps().size();
    }

    /**
     * Returns the remaining steps that have not yet been executed.
     */
    public List<PlannedStep> remainingSteps() {
        int next = nextStepIndex();
        if (next >= plan.steps().size()) {
            return List.of();
        }
        return plan.steps().subList(next, plan.steps().size());
    }

    /**
     * Returns a copy with updated fields after a step completion.
     */
    public PlanCheckpoint withStepCompleted(
            int stepIndex,
            StepResult result,
            TaskPlan updatedPlan,
            List<String> updatedConversation,
            String hint,
            List<String> newArtifacts) {
        var newResults = new ArrayList<>(completedStepResults);
        newResults.add(result);
        var mergedArtifacts = new ArrayList<>(artifacts);
        if (newArtifacts != null) {
            mergedArtifacts.addAll(newArtifacts);
        }
        return new PlanCheckpoint(
                planId, sessionId, workspaceHash, originalGoal, updatedPlan,
                newResults, stepIndex, updatedConversation,
                status, hint, mergedArtifacts,
                createdAt, Instant.now());
    }

    /**
     * Returns a copy with a new status.
     */
    public PlanCheckpoint withStatus(CheckpointStatus newStatus) {
        return new PlanCheckpoint(
                planId, sessionId, workspaceHash, originalGoal, plan,
                completedStepResults, lastCompletedStepIndex, conversationSnapshot,
                newStatus, resumeHint, artifacts,
                createdAt, Instant.now());
    }

    /**
     * Checkpoint lifecycle status.
     */
    public enum CheckpointStatus {
        /** Plan is currently executing, checkpoint updated incrementally. */
        ACTIVE,
        /** Plan execution was interrupted (crash, disconnect, cancel). */
        INTERRUPTED,
        /** Plan completed successfully -- kept for audit, not resumable. */
        COMPLETED,
        /** Plan failed fatally -- kept for diagnostics, not resumable. */
        FAILED,
        /** User explicitly resumed from this checkpoint -- now superseded. */
        RESUMED
    }
}
