package dev.aceclaw.core.agent;

import dev.aceclaw.core.llm.ContentBlock;
import dev.aceclaw.core.llm.Message;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TranscriptStoreTest {

    @TempDir
    Path tempDir;

    private TranscriptStore store;

    @BeforeEach
    void setUp() {
        store = new TranscriptStore(tempDir);
    }

    @Test
    void saveAndLoad() throws Exception {
        var messages = List.<Message>of(
                Message.user("Find auth files"),
                Message.assistant("I found AuthService.java")
        );
        var transcript = new SubAgentTranscript(
                "task-001", "explore", "Find auth files",
                messages, Instant.now().minusSeconds(10), Instant.now(), "I found AuthService.java");

        store.save("session-abc", transcript);

        // File should exist
        Path file = tempDir.resolve("session-abc/task-001.jsonl");
        assertThat(file).exists();
        assertThat(Files.readString(file)).contains("task-001");

        // Load by task ID
        var loaded = store.load("task-001");
        assertThat(loaded).isPresent();
        assertThat(loaded.get().taskId()).isEqualTo("task-001");
        assertThat(loaded.get().agentType()).isEqualTo("explore");
        assertThat(loaded.get().prompt()).isEqualTo("Find auth files");
        assertThat(loaded.get().resultText()).isEqualTo("I found AuthService.java");
        assertThat(loaded.get().messages()).hasSize(2);
    }

    @Test
    void loadNonExistentReturnsEmpty() {
        var result = store.load("nonexistent-task");
        assertThat(result).isEmpty();
    }

    @Test
    void loadFromEmptyBaseDir() {
        // baseDir doesn't exist as a directory
        var emptyStore = new TranscriptStore(tempDir.resolve("nonexistent"));
        var result = emptyStore.load("any-task");
        assertThat(result).isEmpty();
    }

    @Test
    void cleanupRemovesOldFiles() throws Exception {
        var transcript = new SubAgentTranscript(
                "old-task", "explore", "old task",
                List.of(), Instant.now().minus(Duration.ofDays(10)),
                Instant.now().minus(Duration.ofDays(10)), "old result");

        store.save("old-session", transcript);

        // Backdate the file's modification time
        Path file = tempDir.resolve("old-session/old-task.jsonl");
        assertThat(file).exists();
        Files.setLastModifiedTime(file,
                java.nio.file.attribute.FileTime.from(Instant.now().minus(Duration.ofDays(10))));

        int deleted = store.cleanup(Duration.ofDays(7));
        assertThat(deleted).isEqualTo(1);
        assertThat(file).doesNotExist();
    }

    @Test
    void cleanupKeepsRecentFiles() throws Exception {
        var transcript = new SubAgentTranscript(
                "recent-task", "general", "recent task",
                List.of(), Instant.now().minusSeconds(60), Instant.now(), "recent result");

        store.save("recent-session", transcript);

        int deleted = store.cleanup(Duration.ofDays(7));
        assertThat(deleted).isEqualTo(0);
        assertThat(tempDir.resolve("recent-session/recent-task.jsonl")).exists();
    }

    @Test
    void cleanupOnEmptyDirReturnsZero() {
        var emptyStore = new TranscriptStore(tempDir.resolve("nonexistent"));
        int deleted = emptyStore.cleanup();
        assertThat(deleted).isEqualTo(0);
    }

    @Test
    void saveMultipleTranscriptsSameSession() throws Exception {
        var t1 = new SubAgentTranscript(
                "task-a", "explore", "prompt a",
                List.of(), Instant.now(), Instant.now(), "result a");
        var t2 = new SubAgentTranscript(
                "task-b", "general", "prompt b",
                List.of(), Instant.now(), Instant.now(), "result b");

        store.save("session-1", t1);
        store.save("session-1", t2);

        assertThat(store.load("task-a")).isPresent();
        assertThat(store.load("task-b")).isPresent();
    }

    @Test
    void transcriptWithToolUseMessages() throws Exception {
        var messages = List.<Message>of(
                Message.user("Write a test"),
                new Message.AssistantMessage(List.of(
                        new ContentBlock.Text("I'll write the file"),
                        new ContentBlock.ToolUse("toolu_001", "write_file",
                                "{\"file_path\":\"test.java\",\"content\":\"class Test {}\"}")
                )),
                Message.toolResult("toolu_001", "File written successfully", false),
                Message.assistant("Done writing the test file.")
        );

        var transcript = new SubAgentTranscript(
                "task-tool", "general", "Write a test",
                messages, Instant.now().minusSeconds(5), Instant.now(), "Done writing the test file.");

        store.save("session-tool", transcript);

        var loaded = store.load("task-tool");
        assertThat(loaded).isPresent();
        assertThat(loaded.get().messages()).hasSize(4);
    }
}
