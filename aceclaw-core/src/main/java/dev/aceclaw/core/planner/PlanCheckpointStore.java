package dev.aceclaw.core.planner;

import java.util.List;
import java.util.Optional;

/**
 * Persistence abstraction for plan checkpoints.
 * Implementations must guarantee atomic writes for crash safety.
 */
public interface PlanCheckpointStore {

    /**
     * Persists or updates a checkpoint. Must be atomic (write-tmp + rename).
     */
    void save(PlanCheckpoint checkpoint);

    /**
     * Loads a checkpoint by plan ID.
     */
    Optional<PlanCheckpoint> load(String planId);

    /**
     * Finds all resumable checkpoints for a given workspace hash.
     * Resumable statuses: ACTIVE, INTERRUPTED.
     */
    List<PlanCheckpoint> findResumable(String workspaceHash);

    /**
     * Finds all resumable checkpoints for a given session ID.
     */
    List<PlanCheckpoint> findBySession(String sessionId);

    /**
     * Marks a checkpoint as RESUMED (no longer active for routing).
     */
    void markResumed(String planId);

    /**
     * Marks a checkpoint as COMPLETED.
     */
    void markCompleted(String planId);

    /**
     * Marks a checkpoint as FAILED.
     */
    void markFailed(String planId);

    /**
     * Deletes checkpoints older than the given age in days.
     *
     * @return count of deleted checkpoints
     */
    int cleanup(int maxAgeDays);
}
