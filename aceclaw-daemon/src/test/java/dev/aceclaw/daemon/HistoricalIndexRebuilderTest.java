package dev.aceclaw.daemon;

import dev.aceclaw.memory.HistoricalLogIndex;
import dev.aceclaw.memory.HistoricalSessionSnapshot;
import dev.aceclaw.core.agent.ToolMetrics;
import dev.aceclaw.memory.WorkspacePaths;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class HistoricalIndexRebuilderTest {

    @TempDir
    Path tempDir;

    @Test
    void rebuildsMissingWorkspaceEntriesFromPersistedHistory() throws Exception {
        var homeDir = tempDir.resolve(".aceclaw");
        Files.createDirectories(homeDir);
        var workspace = tempDir.resolve("workspace");
        Files.createDirectories(workspace);

        var historyStore = new SessionHistoryStore(homeDir);
        historyStore.init();
        var historicalLogIndex = new HistoricalLogIndex(homeDir);
        var rebuilder = new HistoricalIndexRebuilder(historyStore, historicalLogIndex);

        var session = AgentSession.withId("session-1", workspace);
        session.addMessage(new AgentSession.ConversationMessage.User("Fix the build", Instant.parse("2026-03-13T10:00:00Z")));
        session.addMessage(new AgentSession.ConversationMessage.Assistant("$ ./gradlew test", Instant.parse("2026-03-13T10:00:05Z")));
        session.addMessage(new AgentSession.ConversationMessage.User("The build failed with a timeout", Instant.parse("2026-03-13T10:00:10Z")));
        historyStore.saveSession(session);
        historyStore.saveSnapshot(new HistoricalSessionSnapshot(
                "session-1",
                WorkspacePaths.workspaceHash(workspace),
                Instant.parse("2026-03-13T10:00:10Z"),
                java.util.List.of("./gradlew test"),
                java.util.List.of("Command timed out after 30s"),
                java.util.List.of(),
                java.util.Map.of("bash", new ToolMetrics("bash", 2, 1, 1, 500, Instant.parse("2026-03-13T10:00:10Z"))),
                false,
                "Inspect files, then run commands."
        ));

        String workspaceHash = WorkspacePaths.workspaceHash(workspace);
        assertThat(historicalLogIndex.sessionIds(workspaceHash)).isEmpty();

        var summary = rebuilder.rebuildWorkspaceIfStale(workspaceHash);

        assertThat(summary.rebuilt()).isTrue();
        assertThat(summary.rebuiltSessions()).isEqualTo(1);
        assertThat(historicalLogIndex.sessionIds(workspaceHash)).containsExactly("session-1");
        assertThat(historicalLogIndex.toolInvocations(workspaceHash, null, null))
                .extracting(HistoricalLogIndex.ToolInvocationEntry::sessionId)
                .containsExactly("session-1");
        assertThat(historicalLogIndex.patterns(workspaceHash, null, null))
                .extracting(HistoricalLogIndex.PatternEntry::sessionId)
                .contains("session-1");
    }

    @Test
    void rebuildPreservesLegacyIndexedSessionsWithoutSnapshots() throws Exception {
        var homeDir = tempDir.resolve(".aceclaw");
        Files.createDirectories(homeDir);
        var workspace = tempDir.resolve("workspace");
        Files.createDirectories(workspace);

        var historyStore = new SessionHistoryStore(homeDir);
        historyStore.init();
        var historicalLogIndex = new HistoricalLogIndex(homeDir);
        var rebuilder = new HistoricalIndexRebuilder(historyStore, historicalLogIndex);
        String workspaceHash = WorkspacePaths.workspaceHash(workspace);

        historicalLogIndex.index(new HistoricalSessionSnapshot(
                "legacy-indexed",
                workspaceHash,
                Instant.parse("2026-03-13T10:00:00Z"),
                java.util.List.of("./gradlew test"),
                java.util.List.of(),
                java.util.List.of(),
                java.util.Map.of("bash", new ToolMetrics("bash", 1, 1, 0, 100, Instant.parse("2026-03-13T10:00:00Z"))),
                false,
                "Legacy indexed snapshot"
        ));
        historyStore.saveSnapshot(new HistoricalSessionSnapshot(
                "new-snapshot",
                workspaceHash,
                Instant.parse("2026-03-13T10:01:00Z"),
                java.util.List.of("rg search"),
                java.util.List.of(),
                java.util.List.of(),
                java.util.Map.of("rg", new ToolMetrics("rg", 2, 2, 0, 40, Instant.parse("2026-03-13T10:01:00Z"))),
                false,
                "New snapshot"
        ));

        var summary = rebuilder.rebuildWorkspaceIfStale(workspaceHash);

        assertThat(summary.rebuilt()).isTrue();
        assertThat(historicalLogIndex.sessionIds(workspaceHash))
                .containsExactlyInAnyOrder("legacy-indexed", "new-snapshot");
    }

    @Test
    void rebuildClearsWorkspaceWhenNoHistoryRemains() throws Exception {
        var homeDir = tempDir.resolve(".aceclaw");
        Files.createDirectories(homeDir);
        var workspace = tempDir.resolve("workspace");
        Files.createDirectories(workspace);

        var historyStore = new SessionHistoryStore(homeDir);
        historyStore.init();
        var historicalLogIndex = new HistoricalLogIndex(homeDir);
        var rebuilder = new HistoricalIndexRebuilder(historyStore, historicalLogIndex);
        String workspaceHash = WorkspacePaths.workspaceHash(workspace);

        historicalLogIndex.index(new HistoricalSessionSnapshot(
                "stale-indexed",
                workspaceHash,
                Instant.parse("2026-03-13T10:00:00Z"),
                java.util.List.of("./gradlew test"),
                java.util.List.of(),
                java.util.List.of(),
                java.util.Map.of("bash", new ToolMetrics("bash", 1, 1, 0, 100, Instant.parse("2026-03-13T10:00:00Z"))),
                false,
                "Stale indexed snapshot"
        ));

        var summary = rebuilder.rebuildWorkspaceIfStale(workspaceHash);

        assertThat(summary.rebuilt()).isTrue();
        assertThat(historicalLogIndex.sessionIds(workspaceHash)).isEmpty();
    }

    @Test
    void rebuildIgnoresSnapshotsThatProduceNoIndexRows() throws Exception {
        var homeDir = tempDir.resolve(".aceclaw");
        Files.createDirectories(homeDir);
        var workspace = tempDir.resolve("workspace");
        Files.createDirectories(workspace);

        var historyStore = new SessionHistoryStore(homeDir);
        historyStore.init();
        var historicalLogIndex = new HistoricalLogIndex(homeDir);
        var rebuilder = new HistoricalIndexRebuilder(historyStore, historicalLogIndex);
        String workspaceHash = WorkspacePaths.workspaceHash(workspace);

        historyStore.saveSnapshot(new HistoricalSessionSnapshot(
                "quiet-session",
                workspaceHash,
                Instant.parse("2026-03-13T10:00:00Z"),
                java.util.List.of(),
                java.util.List.of(),
                java.util.List.of(),
                java.util.Map.of(),
                false,
                ""
        ));

        var summary = rebuilder.rebuildWorkspaceIfStale(workspaceHash);

        assertThat(summary.rebuilt()).isFalse();
        assertThat(historicalLogIndex.sessionIds(workspaceHash)).isEmpty();
    }

    @Test
    void rebuildsWhenIndexedCoverageIsIncomplete() throws Exception {
        var homeDir = tempDir.resolve(".aceclaw");
        Files.createDirectories(homeDir);
        var workspace = tempDir.resolve("workspace");
        Files.createDirectories(workspace);

        var historyStore = new SessionHistoryStore(homeDir);
        historyStore.init();
        var historicalLogIndex = new HistoricalLogIndex(homeDir);
        var rebuilder = new HistoricalIndexRebuilder(historyStore, historicalLogIndex);
        String workspaceHash = WorkspacePaths.workspaceHash(workspace);

        var snapshot = new HistoricalSessionSnapshot(
                "session-coverage",
                workspaceHash,
                Instant.parse("2026-03-13T10:00:00Z"),
                java.util.List.of("./gradlew test"),
                java.util.List.of("Timed out"),
                java.util.List.of(),
                Map.of("bash", new ToolMetrics("bash", 1, 0, 1, 100, Instant.parse("2026-03-13T10:00:00Z"))),
                true,
                "Run tests, inspect failure, retry."
        );
        historyStore.saveSnapshot(snapshot);

        historicalLogIndex.index(new HistoricalSessionSnapshot(
                "session-coverage",
                workspaceHash,
                snapshot.closedAt(),
                snapshot.executedCommands(),
                java.util.List.of(),
                java.util.List.of(),
                snapshot.toolMetrics(),
                false,
                ""
        ));

        var beforeCoverage = historicalLogIndex.sessionCoverage(workspaceHash).get("session-coverage");
        assertThat(beforeCoverage).isEqualTo(new HistoricalLogIndex.SessionCoverage(1, 0, 0));

        var summary = rebuilder.rebuildWorkspaceIfStale(workspaceHash);

        assertThat(summary.rebuilt()).isTrue();
        assertThat(historicalLogIndex.sessionCoverage(workspaceHash).get("session-coverage"))
                .isEqualTo(new HistoricalLogIndex.SessionCoverage(1, 1, 2));
    }
}
