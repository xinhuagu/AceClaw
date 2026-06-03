package dev.aceclaw.daemon;

import dev.aceclaw.core.planner.PlanCheckpoint;
import dev.aceclaw.core.planner.TurnCheckpoint;
import dev.aceclaw.core.planner.TurnCheckpointStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class DebouncingTurnCheckpointSinkTest {

    private RecordingStore store;
    private DebouncingTurnCheckpointSink sink;

    @BeforeEach
    void setUp() {
        store = new RecordingStore();
        // Use a generous debounce so tests can deterministically force/skip flushes.
        sink = new DebouncingTurnCheckpointSink(store, Duration.ofSeconds(10));
    }

    private static TurnCheckpoint cp(String turnId, int iter) {
        return new TurnCheckpoint(
                turnId, "session-1", "ws-1", "fix it",
                List.of(), iter, null, List.of(),
                PlanCheckpoint.CheckpointStatus.ACTIVE,
                Instant.now(), Instant.now());
    }

    @Test
    void firstIteration_flushesImmediately() {
        sink.recordIteration(cp("t1", 1));
        assertEquals(1, store.savedCount());
    }

    @Test
    void debounce_skipsBurstWrites_butKeepsLatestForFinalFlush() {
        sink.recordIteration(cp("t1", 1));
        sink.recordIteration(cp("t1", 2));
        sink.recordIteration(cp("t1", 3));

        // Only the first write hits the store directly (debounce window not elapsed).
        assertEquals(1, store.savedCount());
        // But onTurnEnded should flush the latest cached state.
        sink.onTurnEnded("t1", PlanCheckpoint.CheckpointStatus.COMPLETED);
        // Final flush + markCompleted = 2 more saves (one save, one mark-write)
        assertEquals(3, store.savedCount());
        // And the latest snapshot reflects iteration=3 with COMPLETED status applied last.
        var last = store.load("t1").orElseThrow();
        assertEquals(PlanCheckpoint.CheckpointStatus.COMPLETED, last.status());
        assertEquals(3, last.completedIterations());
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
    void onTurnEnded_withoutPriorRecord_noThrow() {
        // No recordIteration ever called for this turn -> nothing to flush.
        assertDoesNotThrow(() ->
                sink.onTurnEnded("ghost", PlanCheckpoint.CheckpointStatus.FAILED));
    }

    @Test
    void separateTurns_doNotShareState() {
        sink.recordIteration(cp("t1", 1));
        sink.recordIteration(cp("t2", 1));
        // Both should flush as "first write" — independent debounce state per turn.
        assertEquals(2, store.savedCount());
    }

    // -- Minimal in-memory store to count interactions ----------------------

    private static final class RecordingStore implements TurnCheckpointStore {
        private final Map<String, TurnCheckpoint> entries = new HashMap<>();
        private final List<TurnCheckpoint> savedHistory = new ArrayList<>();
        private final AtomicInteger deletes = new AtomicInteger();

        int savedCount() { return savedHistory.size(); }

        @Override public void save(TurnCheckpoint checkpoint) {
            entries.put(checkpoint.turnId(), checkpoint);
            savedHistory.add(checkpoint);
        }
        @Override public Optional<TurnCheckpoint> load(String turnId) {
            return Optional.ofNullable(entries.get(turnId));
        }
        @Override public List<TurnCheckpoint> findResumable(String workspaceHash) {
            return entries.values().stream()
                    .filter(c -> workspaceHash.equals(c.workspaceHash())).toList();
        }
        @Override public List<TurnCheckpoint> findBySession(String sessionId) {
            return entries.values().stream()
                    .filter(c -> sessionId.equals(c.sessionId())).toList();
        }
        @Override public void markResumed(String turnId) { updateStatus(turnId, PlanCheckpoint.CheckpointStatus.RESUMED); }
        @Override public void markCompleted(String turnId) { updateStatus(turnId, PlanCheckpoint.CheckpointStatus.COMPLETED); }
        @Override public void markFailed(String turnId) { updateStatus(turnId, PlanCheckpoint.CheckpointStatus.FAILED); }
        @Override public void delete(String turnId) {
            entries.remove(turnId);
            deletes.incrementAndGet();
        }
        @Override public int cleanup(int maxAgeDays) { return 0; }

        private void updateStatus(String turnId, PlanCheckpoint.CheckpointStatus status) {
            var cp = entries.get(turnId);
            if (cp != null) {
                var updated = cp.withStatus(status);
                entries.put(turnId, updated);
                savedHistory.add(updated);
            }
        }
    }
}
