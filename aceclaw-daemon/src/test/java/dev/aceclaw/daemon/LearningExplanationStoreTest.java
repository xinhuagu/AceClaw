package dev.aceclaw.daemon;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LearningExplanationStoreTest {

    @TempDir
    Path tempDir;

    @Test
    void appendAndReadRecentExplanations() throws Exception {
        var store = new LearningExplanationStore();
        store.append(tempDir, new LearningExplanation(
                Instant.parse("2026-03-13T10:15:30Z"),
                "memory_write",
                "memory",
                "memory-1",
                "session-1",
                "self-improvement",
                "Persisted a successful strategy.",
                List.of("strategy"),
                List.of(new LearningExplanation.EvidenceRef("insight", "SuccessInsight", "read -> edit"))));

        var recent = store.recent(tempDir, 10);

        assertThat(recent).hasSize(1);
        assertThat(recent.getFirst().actionType()).isEqualTo("memory_write");
        assertThat(recent.getFirst().evidence()).hasSize(1);
    }

    @Test
    void recentSkipsMalformedJsonlRows() throws Exception {
        var store = new LearningExplanationStore();
        var file = store.explanationsFile(tempDir);
        Files.createDirectories(file.getParent());
        Files.writeString(file, """
                {"timestamp":"2026-03-13T10:15:30Z","actionType":"memory_write","targetType":"memory","targetId":"ok","sessionId":"","trigger":"x","summary":"ok","tags":[],"evidence":[]}
                not-json
                """);

        var recent = store.recent(tempDir, 10);

        assertThat(recent).hasSize(1);
        assertThat(recent.getFirst().targetId()).isEqualTo("ok");
    }
}
