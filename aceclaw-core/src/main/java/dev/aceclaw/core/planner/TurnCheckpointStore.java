package dev.aceclaw.core.planner;

import java.util.List;
import java.util.Optional;

/**
 * Persistence abstraction for ReAct turn checkpoints. Mirrors
 * {@link PlanCheckpointStore} for non-plan turns. Implementations must
 * guarantee atomic writes for crash safety.
 */
public interface TurnCheckpointStore {

    /**
     * Persists or updates a turn checkpoint. Must be atomic (write-tmp + rename).
     */
    void save(TurnCheckpoint checkpoint);

    /**
     * Loads a turn checkpoint by turn ID.
     */
    Optional<TurnCheckpoint> load(String turnId);

    /**
     * Finds all resumable turn checkpoints for a given workspace hash.
     * Resumable statuses: ACTIVE, INTERRUPTED.
     */
    List<TurnCheckpoint> findResumable(String workspaceHash);

    /**
     * Finds all resumable turn checkpoints for a given session ID.
     */
    List<TurnCheckpoint> findBySession(String sessionId);

    /**
     * Marks a checkpoint as RESUMED (no longer active for routing).
     */
    void markResumed(String turnId);

    /**
     * Marks a checkpoint as COMPLETED. Kept on disk for audit; not resumable.
     */
    void markCompleted(String turnId);

    /**
     * Marks a checkpoint as FAILED. Kept on disk for diagnostics; not resumable.
     */
    void markFailed(String turnId);

    /**
     * Deletes the checkpoint file outright. Used when a turn ends with
     * {@code stopReason=END_TURN} (the issue acceptance criterion requires
     * no orphan file after a successful turn).
     */
    void delete(String turnId);

    /**
     * Deletes checkpoints older than the given age in days.
     *
     * @return count of deleted checkpoints
     */
    int cleanup(int maxAgeDays);
}
