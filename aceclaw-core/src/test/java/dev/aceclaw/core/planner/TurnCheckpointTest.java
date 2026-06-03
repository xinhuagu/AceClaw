package dev.aceclaw.core.planner;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TurnCheckpointTest {

    private static TurnCheckpoint sample() {
        return new TurnCheckpoint(
                "turn-1", "session-1", "ws-hash", "fix the bug",
                List.of("{\"role\":\"user\",\"blocks\":[{\"type\":\"text\",\"text\":\"hi\"}]}"),
                0, null, List.of(),
                PlanCheckpoint.CheckpointStatus.ACTIVE,
                Instant.now(), Instant.now());
    }

    @Test
    void nullGuards_inConstructor() {
        assertThrows(NullPointerException.class, () ->
                new TurnCheckpoint(null, "s", "ws", "p", List.of(), 0, null, List.of(),
                        PlanCheckpoint.CheckpointStatus.ACTIVE, Instant.now(), Instant.now()));
        assertThrows(NullPointerException.class, () ->
                new TurnCheckpoint("t", null, "ws", "p", List.of(), 0, null, List.of(),
                        PlanCheckpoint.CheckpointStatus.ACTIVE, Instant.now(), Instant.now()));
        assertThrows(NullPointerException.class, () ->
                new TurnCheckpoint("t", "s", null, "p", List.of(), 0, null, List.of(),
                        PlanCheckpoint.CheckpointStatus.ACTIVE, Instant.now(), Instant.now()));
        assertThrows(NullPointerException.class, () ->
                new TurnCheckpoint("t", "s", "ws", null, List.of(), 0, null, List.of(),
                        PlanCheckpoint.CheckpointStatus.ACTIVE, Instant.now(), Instant.now()));
        assertThrows(NullPointerException.class, () ->
                new TurnCheckpoint("t", "s", "ws", "p", List.of(), 0, null, List.of(),
                        null, Instant.now(), Instant.now()));
    }

    @Test
    void nullLists_defaultToEmpty() {
        var cp = new TurnCheckpoint(
                "t", "s", "ws", "p", null, 0, null, null,
                PlanCheckpoint.CheckpointStatus.ACTIVE, Instant.now(), Instant.now());
        assertNotNull(cp.conversationSnapshot());
        assertTrue(cp.conversationSnapshot().isEmpty());
        assertNotNull(cp.artifacts());
        assertTrue(cp.artifacts().isEmpty());
    }

    @Test
    void listsAreImmutableCopies() {
        var mutableConv = new ArrayList<String>();
        mutableConv.add("a");
        var cp = new TurnCheckpoint(
                "t", "s", "ws", "p", mutableConv, 0, null, List.of(),
                PlanCheckpoint.CheckpointStatus.ACTIVE, Instant.now(), Instant.now());
        mutableConv.add("b");
        assertEquals(1, cp.conversationSnapshot().size());
    }

    @Test
    void withIterationCompleted_bumpsCounterAndReplacesConversation() {
        var cp = sample();
        var newConv = List.of(
                "{\"role\":\"user\",\"blocks\":[]}",
                "{\"role\":\"assistant\",\"blocks\":[]}");
        var updated = cp.withIterationCompleted(3, "tool-use-id-x", newConv, List.of("a.txt"));

        assertEquals(3, updated.completedIterations());
        assertEquals("tool-use-id-x", updated.lastToolUseId());
        assertEquals(2, updated.conversationSnapshot().size());
        assertEquals(1, updated.artifacts().size());
        assertEquals("a.txt", updated.artifacts().getFirst());
        // Original unchanged
        assertEquals(0, cp.completedIterations());
        assertTrue(cp.artifacts().isEmpty());
    }

    @Test
    void withIterationCompleted_mergesArtifacts() {
        var cp = new TurnCheckpoint(
                "t", "s", "ws", "p", List.of(), 0, null, List.of("existing.txt"),
                PlanCheckpoint.CheckpointStatus.ACTIVE, Instant.now(), Instant.now());
        var updated = cp.withIterationCompleted(1, null, List.of(), List.of("new.txt"));

        assertEquals(2, updated.artifacts().size());
        assertEquals("existing.txt", updated.artifacts().get(0));
        assertEquals("new.txt", updated.artifacts().get(1));
    }

    @Test
    void withIterationCompleted_acceptsNullNewArtifacts() {
        var cp = sample();
        var updated = cp.withIterationCompleted(1, null, List.of(), null);
        assertTrue(updated.artifacts().isEmpty());
    }

    @Test
    void withStatus_returnsNewInstanceWithUpdatedTimestamp() throws InterruptedException {
        var cp = sample();
        var originalUpdatedAt = cp.updatedAt();
        Thread.sleep(2); // ensure clock advances
        var updated = cp.withStatus(PlanCheckpoint.CheckpointStatus.COMPLETED);

        assertEquals(PlanCheckpoint.CheckpointStatus.COMPLETED, updated.status());
        assertEquals(PlanCheckpoint.CheckpointStatus.ACTIVE, cp.status());
        assertTrue(updated.updatedAt().isAfter(originalUpdatedAt)
                || updated.updatedAt().equals(originalUpdatedAt));
    }

    @Test
    void withStatus_nullThrows() {
        assertThrows(NullPointerException.class, () -> sample().withStatus(null));
    }
}
