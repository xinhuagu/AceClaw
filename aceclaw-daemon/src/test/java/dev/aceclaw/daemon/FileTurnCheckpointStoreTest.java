package dev.aceclaw.daemon;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.aceclaw.core.planner.PlanCheckpoint;
import dev.aceclaw.core.planner.TurnCheckpoint;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class FileTurnCheckpointStoreTest {

    @TempDir
    Path tempDir;
    private FileTurnCheckpointStore store;

    @BeforeEach
    void setUp() {
        store = new FileTurnCheckpointStore(tempDir, new ObjectMapper());
    }

    private static TurnCheckpoint sample(String turnId, String sessionId,
                                          String wsHash, int iterations) {
        return new TurnCheckpoint(
                turnId, sessionId, wsHash, "fix the auth bug",
                List.of(
                        "{\"role\":\"user\",\"blocks\":[{\"type\":\"text\",\"text\":\"hi\"}]}",
                        "{\"role\":\"assistant\",\"blocks\":[{\"type\":\"text\",\"text\":\"ok\"}]}"),
                iterations, "tool-use-" + iterations, List.of("a.txt"),
                PlanCheckpoint.CheckpointStatus.ACTIVE,
                Instant.now(), Instant.now());
    }

    @Test
    void saveAndLoad_roundTrip() {
        var cp = sample("turn-1", "session-1", "ws-abc", 2);
        store.save(cp);

        var loaded = store.load("turn-1");
        assertTrue(loaded.isPresent());
        var l = loaded.get();
        assertEquals("turn-1", l.turnId());
        assertEquals("session-1", l.sessionId());
        assertEquals("ws-abc", l.workspaceHash());
        assertEquals("fix the auth bug", l.originalPrompt());
        assertEquals(2, l.completedIterations());
        assertEquals("tool-use-2", l.lastToolUseId());
        assertEquals(2, l.conversationSnapshot().size());
        assertEquals(1, l.artifacts().size());
        assertEquals(PlanCheckpoint.CheckpointStatus.ACTIVE, l.status());
    }

    @Test
    void atomicWrite_noTempFileLeftover() {
        var cp = sample("turn-1", "session-1", "ws-abc", 1);
        store.save(cp);

        try (var files = Files.list(tempDir)) {
            var names = files.map(p -> p.getFileName().toString()).toList();
            assertEquals(1, names.size());
            assertEquals("turn-1.turn.json", names.getFirst());
        } catch (IOException e) {
            fail("Failed to list dir: " + e.getMessage());
        }
    }

    @Test
    void compactJson_noPrettyPrint() throws IOException {
        // Turn checkpoints write often; the issue specifies compact JSON.
        var cp = sample("turn-1", "session-1", "ws-abc", 1);
        store.save(cp);
        var json = Files.readString(tempDir.resolve("turn-1.turn.json"));
        // No pretty-print indentation
        assertFalse(json.contains("\n  "), "Expected compact JSON but got:\n" + json);
    }

    @Test
    void load_nonExistent_returnsEmpty() {
        assertTrue(store.load("nonexistent").isEmpty());
    }

    @Test
    void load_corruptFile_returnsEmpty() throws IOException {
        Files.writeString(tempDir.resolve("turn-corrupt.turn.json"), "{not valid json");
        assertTrue(store.load("turn-corrupt").isEmpty());
    }

    @Test
    void findResumable_filtersByWorkspaceAndStatus() {
        store.save(sample("t1", "s1", "ws-a", 1));
        store.save(sample("t2", "s2", "ws-a", 1));
        store.save(sample("t3", "s3", "ws-b", 1));

        // Mark t2 as completed -> not resumable
        store.markCompleted("t2");

        var resumable = store.findResumable("ws-a");
        assertEquals(1, resumable.size());
        assertEquals("t1", resumable.getFirst().turnId());
    }

    @Test
    void findBySession_filtersToActiveOnly() {
        store.save(sample("t1", "s1", "ws-a", 1));
        store.save(sample("t2", "s1", "ws-a", 1));
        store.markFailed("t1");

        var bySession = store.findBySession("s1");
        assertEquals(1, bySession.size());
        assertEquals("t2", bySession.getFirst().turnId());
    }

    @Test
    void markCompleted_updatesStatus() {
        store.save(sample("t1", "s1", "ws-a", 1));
        store.markCompleted("t1");

        var loaded = store.load("t1");
        assertTrue(loaded.isPresent());
        assertEquals(PlanCheckpoint.CheckpointStatus.COMPLETED, loaded.get().status());
    }

    @Test
    void markFailed_updatesStatus() {
        store.save(sample("t1", "s1", "ws-a", 1));
        store.markFailed("t1");

        assertEquals(PlanCheckpoint.CheckpointStatus.FAILED,
                store.load("t1").orElseThrow().status());
    }

    @Test
    void markResumed_updatesStatus() {
        store.save(sample("t1", "s1", "ws-a", 1));
        store.markResumed("t1");

        assertEquals(PlanCheckpoint.CheckpointStatus.RESUMED,
                store.load("t1").orElseThrow().status());
    }

    @Test
    void delete_removesFile() {
        store.save(sample("t1", "s1", "ws-a", 1));
        assertTrue(store.load("t1").isPresent());

        store.delete("t1");
        assertTrue(store.load("t1").isEmpty());
    }

    @Test
    void delete_nonExistent_noThrow() {
        assertDoesNotThrow(() -> store.delete("nonexistent"));
    }

    @Test
    void cleanup_deletesCorruptFiles() throws IOException {
        Files.writeString(tempDir.resolve("turn-corrupt.turn.json"), "{garbage");
        store.save(sample("t-good", "s1", "ws-a", 1));

        int deleted = store.cleanup(7);
        assertEquals(1, deleted);
        // Good file survives
        assertTrue(store.load("t-good").isPresent());
    }
}
