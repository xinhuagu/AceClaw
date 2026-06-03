package dev.aceclaw.daemon;

import dev.aceclaw.core.planner.PlanCheckpoint;
import dev.aceclaw.core.planner.TurnCheckpoint;
import dev.aceclaw.core.planner.TurnCheckpointStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class FileTurnCheckpointSinkTest {

    private RecordingStore store;
    private FileTurnCheckpointSink sink;

    @BeforeEach
    void setUp() {
        store = new RecordingStore();
        sink = new FileTurnCheckpointSink(store);
    }

    private static TurnCheckpoint cp(String turnId, int iter) {
        return new TurnCheckpoint(
                turnId, "session-1", "ws-1", "fix it",
                List.of(), iter, null, List.of(),
                PlanCheckpoint.CheckpointStatus.ACTIVE,
                Instant.now(), Instant.now());
    }

    @Test
    void recordIteration_flushesEveryWrite() {
        // Crash-resume contract: every iteration must be on disk before the next
        // one starts — debounce was dropped exactly because trailing flushes
        // could lose the most recent iteration.
        sink.recordIteration(cp("t1", 1));
        sink.recordIteration(cp("t1", 2));
        sink.recordIteration(cp("t1", 3));

        assertEquals(3, store.saves.get());
        assertEquals(3, store.load("t1").orElseThrow().completedIterations());
    }

    @Test
    void onTurnCompleted_deletesFile() {
        sink.recordIteration(cp("t1", 1));
        assertTrue(store.load("t1").isPresent());

        sink.onTurnCompleted("t1");
        assertTrue(store.load("t1").isEmpty());
        assertEquals(1, store.deletes.get());
    }

    @Test
    void onTurnEnded_failedMarksStatus() {
        sink.recordIteration(cp("t1", 1));
        sink.onTurnEnded("t1", PlanCheckpoint.CheckpointStatus.FAILED);

        assertEquals(PlanCheckpoint.CheckpointStatus.FAILED,
                store.load("t1").orElseThrow().status());
    }

    @Test
    void onTurnEnded_interruptedMarksStatus() {
        // Regression test for PR #516 review finding — INTERRUPTED was previously
        // a no-op, leaving cancelled turns as ACTIVE on disk and indistinguishable
        // from "still in flight" for resume routing.
        sink.recordIteration(cp("t1", 1));
        sink.onTurnEnded("t1", PlanCheckpoint.CheckpointStatus.INTERRUPTED);

        assertEquals(PlanCheckpoint.CheckpointStatus.INTERRUPTED,
                store.load("t1").orElseThrow().status());
    }

    @Test
    void onTurnEnded_completedMarksStatus() {
        sink.recordIteration(cp("t1", 1));
        sink.onTurnEnded("t1", PlanCheckpoint.CheckpointStatus.COMPLETED);

        assertEquals(PlanCheckpoint.CheckpointStatus.COMPLETED,
                store.load("t1").orElseThrow().status());
    }

    @Test
    void onTurnEnded_withoutPriorRecord_noThrow() {
        // mark* on a non-existent turn must not blow up — the underlying store's
        // updateStatus is a no-op if the file is missing.
        assertDoesNotThrow(() ->
                sink.onTurnEnded("ghost", PlanCheckpoint.CheckpointStatus.FAILED));
    }

    @Test
    void recordIteration_storeFailureSwallowed() {
        // The sink must never propagate persistence failures up into the agent
        // loop — a corrupt disk shouldn't crash the user's turn.
        store.failNextSave = true;
        assertDoesNotThrow(() -> sink.recordIteration(cp("t1", 1)));
    }

    // -- Minimal in-memory store for interaction counting ------------------

    private static final class RecordingStore implements TurnCheckpointStore {
        private final Map<String, TurnCheckpoint> entries = new HashMap<>();
        private final AtomicInteger saves = new AtomicInteger();
        private final AtomicInteger deletes = new AtomicInteger();
        boolean failNextSave;

        @Override public void save(TurnCheckpoint checkpoint) {
            if (failNextSave) {
                failNextSave = false;
                throw new RuntimeException("simulated disk failure");
            }
            entries.put(checkpoint.turnId(), checkpoint);
            saves.incrementAndGet();
        }
        @Override public Optional<TurnCheckpoint> load(String turnId) {
            return Optional.ofNullable(entries.get(turnId));
        }
        @Override public List<TurnCheckpoint> findResumable(String workspaceHash) {
            return new ArrayList<>(entries.values());
        }
        @Override public List<TurnCheckpoint> findBySession(String sessionId) {
            return new ArrayList<>(entries.values());
        }
        @Override public void markResumed(String turnId) { updateStatus(turnId, PlanCheckpoint.CheckpointStatus.RESUMED); }
        @Override public void markCompleted(String turnId) { updateStatus(turnId, PlanCheckpoint.CheckpointStatus.COMPLETED); }
        @Override public void markFailed(String turnId) { updateStatus(turnId, PlanCheckpoint.CheckpointStatus.FAILED); }
        @Override public void markInterrupted(String turnId) { updateStatus(turnId, PlanCheckpoint.CheckpointStatus.INTERRUPTED); }
        @Override public void delete(String turnId) {
            entries.remove(turnId);
            deletes.incrementAndGet();
        }
        @Override public int cleanup(int maxAgeDays) { return 0; }

        private void updateStatus(String turnId, PlanCheckpoint.CheckpointStatus status) {
            var cp = entries.get(turnId);
            if (cp != null) {
                entries.put(turnId, cp.withStatus(status));
            }
        }
    }
}
