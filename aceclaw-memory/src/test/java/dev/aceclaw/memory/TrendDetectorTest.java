package dev.aceclaw.memory;

import dev.aceclaw.core.agent.ToolMetrics;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TrendDetectorTest {

    @TempDir
    Path tempDir;

    @Test
    void detectsRisingErrorRateAndFrequencyAndPersistsAntiPatterns() throws Exception {
        var workspace = tempDir.resolve("workspace-a");
        var store = AutoMemoryStore.forWorkspace(tempDir, workspace);
        var index = new HistoricalLogIndex(tempDir);
        var detector = new TrendDetector();
        var t0 = Instant.parse("2026-03-12T10:00:00Z");
        String workspaceHash = WorkspacePaths.workspaceHash(workspace);

        index.index(snapshot("s0a", workspaceHash, t0.minusSeconds(120), 5, 0, 580, List.of()));
        index.index(snapshot("s0b", workspaceHash, t0.minusSeconds(60), 5, 0, 590, List.of()));
        index.index(snapshot("s1", workspaceHash, t0, 5, 0, 600, List.of()));
        index.index(snapshot("s2", workspaceHash, t0.plusSeconds(60), 5, 1, 650, List.of("Command timed out after 30s")));
        index.index(snapshot("s3", workspaceHash, t0.plusSeconds(120), 5, 2, 700,
                List.of("Command timed out after 30s", "Permission denied")));
        index.index(snapshot("s4", workspaceHash, t0.plusSeconds(180), 5, 3, 720,
                List.of("Command timed out after 30s", "Command timed out after 30s", "Permission denied")));
        index.index(snapshot("s5", workspaceHash, t0.plusSeconds(240), 5, 4, 760,
                List.of("Command timed out after 30s", "Command timed out after 30s",
                        "Permission denied", "Command timed out after 30s")));

        var trends = detector.detect(index, store, workspaceHash, workspace, 10);

        assertThat(trends)
                .extracting(TrendDetector.Trend::metric)
                .contains("bash.errorRate", "timeout.frequency");
        assertThat(trends)
                .filteredOn(trend -> trend.metric().equals("bash.errorRate"))
                .singleElement()
                .satisfies(trend -> assertThat(trend.direction()).isEqualTo(TrendDetector.TrendDirection.RISING));
        assertThat(store.query(MemoryEntry.Category.ANTI_PATTERN, List.of("trend"), 10))
                .anySatisfy(entry -> assertThat(entry.content()).contains("Tool 'bash' error rate rose"))
                .anySatisfy(entry -> assertThat(entry.content()).contains("Error class 'TIMEOUT' frequency rose"));
    }

    @Test
    void detectsFallingEfficiencyAndLatencyAndPersistsSuccessfulStrategies() throws Exception {
        var workspace = tempDir.resolve("workspace-b");
        var store = AutoMemoryStore.forWorkspace(tempDir, workspace);
        var index = new HistoricalLogIndex(tempDir);
        var detector = new TrendDetector();
        var t0 = Instant.parse("2026-03-12T10:00:00Z");
        String workspaceHash = WorkspacePaths.workspaceHash(workspace);

        index.index(snapshot("s1", workspaceHash, t0, 5, 1, 1800, List.of("Command timed out after 30s")));
        index.index(snapshot("s2", workspaceHash, t0.plusSeconds(60), 4, 1, 1600, List.of("Command timed out after 30s")));
        index.index(snapshot("s3", workspaceHash, t0.plusSeconds(120), 0, 0, 0, List.of()));
        index.index(snapshot("s4", workspaceHash, t0.plusSeconds(180), 0, 0, 0, List.of()));

        var trends = detector.detect(index, store, workspaceHash, workspace, 10);

        assertThat(trends)
                .extracting(TrendDetector.Trend::metric)
                .contains("overall.toolInvocationsPerSession", "bash.avgDurationMs");
        assertThat(trends)
                .filteredOn(trend -> trend.metric().equals("overall.toolInvocationsPerSession"))
                .singleElement()
                .satisfies(trend -> assertThat(trend.direction()).isEqualTo(TrendDetector.TrendDirection.FALLING));
        assertThat(store.query(MemoryEntry.Category.SUCCESSFUL_STRATEGY, List.of("trend"), 10))
                .anySatisfy(entry -> assertThat(entry.content()).contains("Overall tool invocations per session fell"))
                .anySatisfy(entry -> assertThat(entry.content()).contains("average duration fell"));
    }

    @Test
    void rejectsPersistenceWhenWorkspaceHashDoesNotMatchProjectPath() throws Exception {
        var workspaceA = tempDir.resolve("workspace-a");
        var workspaceB = tempDir.resolve("workspace-b");
        var store = AutoMemoryStore.forWorkspace(tempDir, workspaceA);
        var index = new HistoricalLogIndex(tempDir);
        var detector = new TrendDetector();
        var t0 = Instant.parse("2026-03-12T10:00:00Z");
        String workspaceHashB = WorkspacePaths.workspaceHash(workspaceB);

        index.index(snapshot("s1", workspaceHashB, t0, 5, 0, 600, List.of()));
        index.index(snapshot("s2", workspaceHashB, t0.plusSeconds(60), 5, 1, 650,
                List.of("Command timed out after 30s")));
        index.index(snapshot("s3", workspaceHashB, t0.plusSeconds(120), 5, 2, 700,
                List.of("Command timed out after 30s", "Permission denied")));
        index.index(snapshot("s4", workspaceHashB, t0.plusSeconds(180), 5, 3, 760,
                List.of("Command timed out after 30s", "Command timed out after 30s", "Permission denied")));

        assertThrows(IllegalArgumentException.class,
                () -> detector.detect(index, store, workspaceHashB, workspaceA, 10));
        assertThat(store.query(MemoryEntry.Category.SUCCESSFUL_STRATEGY, List.of("trend"), 10)).isEmpty();
        assertThat(store.query(MemoryEntry.Category.ANTI_PATTERN, List.of("trend"), 10)).isEmpty();
    }

    @Test
    void rejectsPersistenceWhenWorkspaceHashIsMissing() throws Exception {
        var workspace = tempDir.resolve("workspace-c");
        var store = AutoMemoryStore.forWorkspace(tempDir, workspace);
        var index = new HistoricalLogIndex(tempDir);
        var detector = new TrendDetector();
        var t0 = Instant.parse("2026-03-12T10:00:00Z");
        String workspaceHash = WorkspacePaths.workspaceHash(workspace);

        index.index(snapshot("s1", workspaceHash, t0, 5, 1, 600, List.of("Command timed out after 30s")));
        index.index(snapshot("s2", workspaceHash, t0.plusSeconds(60), 5, 2, 650,
                List.of("Command timed out after 30s", "Permission denied")));
        index.index(snapshot("s3", workspaceHash, t0.plusSeconds(120), 5, 3, 700,
                List.of("Command timed out after 30s", "Permission denied", "Command timed out after 30s")));
        index.index(snapshot("s4", workspaceHash, t0.plusSeconds(180), 5, 4, 760,
                List.of("Command timed out after 30s", "Permission denied", "Command timed out after 30s",
                        "Command timed out after 30s")));

        assertThrows(NullPointerException.class,
                () -> detector.detect(index, store, null, workspace, 10));
        assertThrows(IllegalArgumentException.class,
                () -> detector.detect(index, store, "   ", workspace, 10));
    }

    private static HistoricalSessionSnapshot snapshot(String sessionId,
                                                     String workspaceHash,
                                                     Instant timestamp,
                                                     int invocations,
                                                     int errorCount,
                                                     long avgDurationMs,
                                                     List<String> errors) {
        long totalDurationMs = Math.max(1, invocations) * avgDurationMs;
        return new HistoricalSessionSnapshot(
                sessionId,
                workspaceHash,
                timestamp,
                List.of("bash build.sh"),
                errors,
                List.of("src/main/App.java"),
                Map.of("bash", new ToolMetrics("bash", invocations, Math.max(0, invocations - errorCount), errorCount,
                        totalDurationMs, timestamp)),
                errorCount > 0,
                "The end-to-end strategy was to inspect files, then run build commands."
        );
    }
}
