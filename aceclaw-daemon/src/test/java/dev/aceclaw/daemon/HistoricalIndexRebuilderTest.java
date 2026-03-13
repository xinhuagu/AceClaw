package dev.aceclaw.daemon;

import dev.aceclaw.memory.HistoricalLogIndex;
import dev.aceclaw.memory.WorkspacePaths;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

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
        var rebuilder = new HistoricalIndexRebuilder(historyStore, historicalLogIndex, new SessionAnalyzer());

        var session = AgentSession.withId("session-1", workspace);
        session.addMessage(new AgentSession.ConversationMessage.User("Fix the build", Instant.parse("2026-03-13T10:00:00Z")));
        session.addMessage(new AgentSession.ConversationMessage.Assistant("$ ./gradlew test", Instant.parse("2026-03-13T10:00:05Z")));
        session.addMessage(new AgentSession.ConversationMessage.User("The build failed with a timeout", Instant.parse("2026-03-13T10:00:10Z")));
        historyStore.saveSession(session);

        String workspaceHash = WorkspacePaths.workspaceHash(workspace);
        assertThat(historicalLogIndex.sessionIds(workspaceHash)).isEmpty();

        var summary = rebuilder.rebuildWorkspaceIfStale(workspaceHash);

        assertThat(summary.rebuilt()).isTrue();
        assertThat(summary.rebuiltSessions()).isEqualTo(1);
        assertThat(historicalLogIndex.sessionIds(workspaceHash)).containsExactly("session-1");
        assertThat(historicalLogIndex.patterns(workspaceHash, null, null))
                .extracting(HistoricalLogIndex.PatternEntry::sessionId)
                .contains("session-1");
    }
}
