package dev.aceclaw.core.agent;

import dev.aceclaw.core.planner.PlanCheckpoint;
import dev.aceclaw.core.planner.TurnCheckpoint;

/**
 * Sink the agent loop hands per-iteration turn snapshots to. Implementations
 * own debouncing and the underlying {@code TurnCheckpointStore} — the loop
 * just calls these three methods at the right moments.
 *
 * <p>Lives in {@code aceclaw-core} because {@link StreamingAgentLoop}
 * depends on it; the file-backed implementation lives in
 * {@code aceclaw-daemon} alongside {@code FileTurnCheckpointStore}.
 */
public interface TurnCheckpointSink {

    /**
     * Called after each iteration of the ReAct loop has fully materialized
     * (LLM response + tool calls + tool results all in the conversation).
     * Implementations may debounce intermediate writes; the only guarantee
     * the caller makes is that {@code checkpoint} reflects the latest state.
     */
    void recordIteration(TurnCheckpoint checkpoint);

    /**
     * Called when the turn ends with {@code stopReason=END_TURN}.
     * Implementations should delete the on-disk checkpoint — a successful
     * turn must not leave an orphan file (acceptance criterion).
     */
    void onTurnCompleted(String turnId);

    /**
     * Called when the turn ends abnormally (cancelled, error, exceeded
     * iterations, hit MAX_TOKENS/STOP_SEQUENCE). Implementations flush the
     * latest snapshot with {@code finalStatus} so it's available to
     * {@code ResumeRouter} (for INTERRUPTED) or kept for audit (FAILED /
     * COMPLETED-but-truncated).
     */
    void onTurnEnded(String turnId, PlanCheckpoint.CheckpointStatus finalStatus);

    /** No-op sink. Used as the default when no checkpoint writer is wired. */
    TurnCheckpointSink NOOP = new TurnCheckpointSink() {
        @Override public void recordIteration(TurnCheckpoint checkpoint) {}
        @Override public void onTurnCompleted(String turnId) {}
        @Override public void onTurnEnded(String turnId, PlanCheckpoint.CheckpointStatus finalStatus) {}
    };
}
