package dev.aceclaw.core.planner;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Immutable snapshot of in-progress ReAct turn state for non-plan
 * (single-prompt) execution. Sibling to {@link PlanCheckpoint}, written
 * after each completed iteration of {@code StreamingAgentLoop}.
 *
 * <p>Reuses {@link PlanCheckpoint.CheckpointStatus} for lifecycle semantics:
 * ACTIVE while the turn is in flight, COMPLETED on normal end, FAILED on
 * fatal error, INTERRUPTED on cancel/crash recovery, RESUMED once a later
 * turn has picked up from this checkpoint.
 */
public record TurnCheckpoint(
        String turnId,
        String sessionId,
        String workspaceHash,
        String originalPrompt,
        List<String> conversationSnapshot,
        int completedIterations,
        String lastToolUseId,
        List<String> artifacts,
        PlanCheckpoint.CheckpointStatus status,
        Instant createdAt,
        Instant updatedAt
) {

    public TurnCheckpoint {
        Objects.requireNonNull(turnId, "turnId");
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(workspaceHash, "workspaceHash");
        Objects.requireNonNull(originalPrompt, "originalPrompt");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        conversationSnapshot = conversationSnapshot != null
                ? List.copyOf(conversationSnapshot) : List.of();
        artifacts = artifacts != null ? List.copyOf(artifacts) : List.of();
    }

    /**
     * Returns a copy advanced by one completed iteration. The new snapshot
     * replaces the conversation list, bumps {@code completedIterations},
     * records the last tool-use id (for dedup hints on resume), and merges
     * any new artifacts.
     */
    public TurnCheckpoint withIterationCompleted(
            int iterationsCompleted,
            String lastToolUseId,
            List<String> conversation,
            List<String> newArtifacts) {
        var mergedArtifacts = new ArrayList<>(artifacts);
        if (newArtifacts != null) {
            mergedArtifacts.addAll(newArtifacts);
        }
        return new TurnCheckpoint(
                turnId,
                sessionId,
                workspaceHash,
                originalPrompt,
                conversation,
                iterationsCompleted,
                lastToolUseId,
                mergedArtifacts,
                status,
                createdAt,
                Instant.now());
    }

    /**
     * Returns a copy with a new lifecycle status. {@code updatedAt} is
     * advanced to {@link Instant#now()}.
     */
    public TurnCheckpoint withStatus(PlanCheckpoint.CheckpointStatus newStatus) {
        Objects.requireNonNull(newStatus, "newStatus");
        return new TurnCheckpoint(
                turnId,
                sessionId,
                workspaceHash,
                originalPrompt,
                conversationSnapshot,
                completedIterations,
                lastToolUseId,
                artifacts,
                newStatus,
                createdAt,
                Instant.now());
    }
}
