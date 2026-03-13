package dev.aceclaw.daemon;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class LearningMaintenanceRunStoreTest {

    @TempDir
    Path tempDir;

    @Test
    void appendAndRecent_roundTripsRuns() throws Exception {
        var store = new LearningMaintenanceRunStore();
        store.append(tempDir, new LearningMaintenanceRun(
                Instant.parse("2026-03-13T20:00:00Z"),
                "session-count",
                "abc123",
                tempDir.toString(),
                1, 2, 3,
                4, 5, 6, 7,
                8,
                9, 10, 11,
                "maintenance summary"));
        store.append(tempDir, new LearningMaintenanceRun(
                Instant.parse("2026-03-13T19:00:00Z"),
                "time-interval",
                "abc123",
                tempDir.toString(),
                0, 0, 0,
                0, 0, 0, 0,
                0,
                0, 0, 0,
                "résumé"));

        var recent = store.recent(tempDir, 10);
        assertThat(recent).hasSize(2);
        assertThat(recent.get(0).trigger()).isEqualTo("session-count");
        assertThat(recent.get(0).pruned()).isEqualTo(3);
        assertThat(recent.get(0).candidatePromoted()).isEqualTo(11);
        assertThat(recent.get(1).trigger()).isEqualTo("time-interval");
        assertThat(recent.get(1).summary()).isEqualTo("résumé");
    }
}
