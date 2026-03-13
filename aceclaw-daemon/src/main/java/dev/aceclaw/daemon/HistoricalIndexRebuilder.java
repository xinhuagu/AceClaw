package dev.aceclaw.daemon;

import dev.aceclaw.memory.HistoricalLogIndex;
import dev.aceclaw.memory.HistoricalSessionSnapshot;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Rebuilds workspace-scoped historical index entries from persisted session history when needed.
 */
public final class HistoricalIndexRebuilder {

    private final SessionHistoryStore historyStore;
    private final HistoricalLogIndex historicalLogIndex;
    private final SessionAnalyzer sessionAnalyzer;

    public HistoricalIndexRebuilder(SessionHistoryStore historyStore,
                                    HistoricalLogIndex historicalLogIndex,
                                    SessionAnalyzer sessionAnalyzer) {
        this.historyStore = Objects.requireNonNull(historyStore, "historyStore");
        this.historicalLogIndex = Objects.requireNonNull(historicalLogIndex, "historicalLogIndex");
        this.sessionAnalyzer = Objects.requireNonNull(sessionAnalyzer, "sessionAnalyzer");
    }

    public RebuildSummary rebuildWorkspaceIfStale(String workspaceHash) throws Exception {
        Objects.requireNonNull(workspaceHash, "workspaceHash");
        if (workspaceHash.isBlank()) {
            throw new IllegalArgumentException("workspaceHash must not be blank");
        }

        var historySessionIds = historyStore.listSessionsForWorkspace(workspaceHash).stream()
                .sorted()
                .toList();
        if (historySessionIds.isEmpty()) {
            return new RebuildSummary(false, 0, 0, Set.of(), Set.of());
        }

        var indexedSessionIds = historicalLogIndex.sessionIds(workspaceHash);
        var historySessionSet = Set.copyOf(historySessionIds);
        if (indexedSessionIds.equals(historySessionSet)) {
            return new RebuildSummary(false, historySessionIds.size(), indexedSessionIds.size(), historySessionSet, indexedSessionIds);
        }

        var snapshots = new ArrayList<HistoricalSessionSnapshot>();
        for (var sessionId : historySessionIds) {
            var messages = historyStore.loadSession(sessionId);
            if (messages.isEmpty()) {
                continue;
            }
            var learnings = sessionAnalyzer.analyze(messages, Map.of());
            Instant closedAt = messages.stream()
                    .map(HistoricalIndexRebuilder::timestampOf)
                    .filter(Objects::nonNull)
                    .max(Comparator.naturalOrder())
                    .orElse(Instant.now());
            snapshots.add(new HistoricalSessionSnapshot(
                    sessionId,
                    workspaceHash,
                    closedAt,
                    learnings.executedCommands(),
                    learnings.errorsEncountered(),
                    learnings.extractedFilePaths(),
                    Map.of(),
                    learnings.backtrackingDetected(),
                    learnings.endToEndStrategy()
            ));
        }

        historicalLogIndex.replaceWorkspace(workspaceHash, snapshots);
        return new RebuildSummary(true, snapshots.size(), indexedSessionIds.size(), historySessionSet, indexedSessionIds);
    }

    private static Instant timestampOf(AgentSession.ConversationMessage message) {
        return switch (message) {
            case AgentSession.ConversationMessage.User user -> user.timestamp();
            case AgentSession.ConversationMessage.Assistant assistant -> assistant.timestamp();
            case AgentSession.ConversationMessage.System system -> system.timestamp();
        };
    }

    public record RebuildSummary(
            boolean rebuilt,
            int rebuiltSessions,
            int indexedSessionsBefore,
            Set<String> historySessionIds,
            Set<String> indexedSessionIds
    ) {
        public RebuildSummary {
            historySessionIds = historySessionIds != null ? Set.copyOf(historySessionIds) : Set.of();
            indexedSessionIds = indexedSessionIds != null ? Set.copyOf(indexedSessionIds) : Set.of();
        }
    }
}
