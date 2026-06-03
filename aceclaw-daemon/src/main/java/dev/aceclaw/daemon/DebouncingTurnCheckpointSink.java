package dev.aceclaw.daemon;

import dev.aceclaw.core.agent.TurnCheckpointSink;
import dev.aceclaw.core.planner.PlanCheckpoint;
import dev.aceclaw.core.planner.TurnCheckpoint;
import dev.aceclaw.core.planner.TurnCheckpointStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@link TurnCheckpointSink} adapter that funnels per-iteration writes into a
 * {@link TurnCheckpointStore} with a debounce window.
 *
 * <p>Debounce contract:
 * <ul>
 *   <li>First {@code recordIteration} per turn always flushes (a crash after
 *       iteration 1 must leave a parseable file).</li>
 *   <li>Subsequent {@code recordIteration} calls flush only if more than
 *       {@code debounceWindow} has elapsed since the last flush. The latest
 *       in-memory snapshot is always cached.</li>
 *   <li>{@code onTurnCompleted} / {@code onTurnEnded} always flush the cached
 *       snapshot before applying the terminal action (delete or status mark),
 *       so debounced intermediate states aren't lost on normal completion.</li>
 * </ul>
 *
 * <p>In practice ReAct iterations take >>500ms (LLM call + tool execution), so
 * the debounce mostly never fires; it exists as a guardrail against
 * pathological tight-loop turns rather than a steady-state optimization.
 */
public final class DebouncingTurnCheckpointSink implements TurnCheckpointSink {

    private static final Logger log = LoggerFactory.getLogger(DebouncingTurnCheckpointSink.class);

    private final TurnCheckpointStore store;
    private final Duration debounceWindow;
    private final ConcurrentHashMap<String, TurnState> state = new ConcurrentHashMap<>();

    public DebouncingTurnCheckpointSink(TurnCheckpointStore store, Duration debounceWindow) {
        this.store = Objects.requireNonNull(store, "store");
        this.debounceWindow = Objects.requireNonNull(debounceWindow, "debounceWindow");
    }

    /** Convenience constructor using the EPIC's 500ms debounce window. */
    public DebouncingTurnCheckpointSink(TurnCheckpointStore store) {
        this(store, Duration.ofMillis(500));
    }

    @Override
    public void recordIteration(TurnCheckpoint checkpoint) {
        Objects.requireNonNull(checkpoint, "checkpoint");
        var s = state.computeIfAbsent(checkpoint.turnId(), id -> new TurnState());
        synchronized (s) {
            s.latest = checkpoint;
            long now = System.nanoTime();
            // Flush on first write OR if the debounce window has elapsed
            // since the last flush.
            if (s.lastFlushNanos == 0L
                    || (now - s.lastFlushNanos) >= debounceWindow.toNanos()) {
                try {
                    store.save(checkpoint);
                    s.lastFlushNanos = now;
                } catch (Exception e) {
                    log.warn("Failed to persist turn checkpoint {} (iteration {}): {}",
                            checkpoint.turnId(), checkpoint.completedIterations(),
                            e.getMessage());
                }
            }
        }
    }

    @Override
    public void onTurnCompleted(String turnId) {
        Objects.requireNonNull(turnId, "turnId");
        state.remove(turnId);
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
        var s = state.remove(turnId);
        try {
            // Always flush the latest cached snapshot so debounced intermediate
            // states aren't lost. If we never received a recordIteration call
            // for this turn there's nothing to flush — markX would be a no-op
            // since the file doesn't exist yet.
            if (s != null && s.latest != null) {
                store.save(s.latest);
            }
            switch (finalStatus) {
                case COMPLETED -> store.markCompleted(turnId);
                case FAILED -> store.markFailed(turnId);
                case INTERRUPTED, ACTIVE -> {
                    // INTERRUPTED: leave file with ACTIVE/INTERRUPTED status so
                    // ResumeRouter (P1, #501) can pick it up. We don't have an
                    // explicit "mark interrupted" method since the recordIteration
                    // path already saves with ACTIVE; nothing more to do.
                }
                case RESUMED -> store.markResumed(turnId);
            }
        } catch (Exception e) {
            log.warn("Failed to flush turn checkpoint {} on end ({}): {}",
                    turnId, finalStatus, e.getMessage());
        }
    }

    private static final class TurnState {
        TurnCheckpoint latest;
        long lastFlushNanos;
    }
}
