package dev.aceclaw.daemon;

import dev.aceclaw.core.agent.TurnCheckpointSink;
import dev.aceclaw.core.planner.PlanCheckpoint;
import dev.aceclaw.core.planner.TurnCheckpoint;
import dev.aceclaw.core.planner.TurnCheckpointStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;

/**
 * {@link TurnCheckpointSink} adapter that funnels per-iteration writes directly
 * to a {@link TurnCheckpointStore}.
 *
 * <p>The earlier iteration of this class wrapped writes in a 500ms debounce
 * window (per the EPIC's spec). PR #516 review surfaced a correctness issue:
 * the last debounced iteration was only flushed by the <em>next</em> callback,
 * so a crash within the debounce window left iter N-1 on disk while iter N
 * had completed — violating the crash-resume contract for the very iteration
 * that mattered. In practice ReAct iterations are dominated by an LLM call
 * (1-3s) so the debounce window almost never fires anyway. Dropping it costs
 * nothing in steady state and removes the trailing-flush race.
 */
public final class FileTurnCheckpointSink implements TurnCheckpointSink {

    private static final Logger log = LoggerFactory.getLogger(FileTurnCheckpointSink.class);

    private final TurnCheckpointStore store;

    public FileTurnCheckpointSink(TurnCheckpointStore store) {
        this.store = Objects.requireNonNull(store, "store");
    }

    @Override
    public void recordIteration(TurnCheckpoint checkpoint) {
        Objects.requireNonNull(checkpoint, "checkpoint");
        try {
            store.save(checkpoint);
        } catch (Exception e) {
            log.warn("Failed to persist turn checkpoint {} (iteration {}): {}",
                    checkpoint.turnId(), checkpoint.completedIterations(),
                    e.getMessage());
        }
    }

    @Override
    public void onTurnCompleted(String turnId) {
        Objects.requireNonNull(turnId, "turnId");
        try {
            store.delete(turnId);
        } catch (Exception e) {
            log.warn("Failed to delete turn checkpoint {} on completion: {}",
                    turnId, e.getMessage());
        }
    }

    @Override
    public void onTurnEnded(String turnId, PlanCheckpoint.CheckpointStatus finalStatus) {
        Objects.requireNonNull(turnId, "turnId");
        Objects.requireNonNull(finalStatus, "finalStatus");
        try {
            switch (finalStatus) {
                case COMPLETED -> store.markCompleted(turnId);
                case FAILED -> store.markFailed(turnId);
                case INTERRUPTED -> store.markInterrupted(turnId);
                case ACTIVE -> {
                    // Defensive: callers should not pass ACTIVE as a terminal
                    // status. Leave the file as-is (recordIteration's last write
                    // already has ACTIVE) — log once for diagnostics.
                    log.debug("onTurnEnded called with ACTIVE status for turn {}", turnId);
                }
                case RESUMED -> store.markResumed(turnId);
            }
        } catch (Exception e) {
            log.warn("Failed to update terminal status for turn checkpoint {} ({}): {}",
                    turnId, finalStatus, e.getMessage());
        }
    }
}
