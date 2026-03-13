package dev.aceclaw.daemon;

import dev.aceclaw.memory.HistoricalLogIndex;
import dev.aceclaw.memory.HistoricalSessionSnapshot;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Rebuilds workspace-scoped historical index entries from persisted session history when needed.
 */
public final class HistoricalIndexRebuilder {

    private final SessionHistoryStore historyStore;
    private final HistoricalLogIndex historicalLogIndex;

    public HistoricalIndexRebuilder(SessionHistoryStore historyStore,
                                    HistoricalLogIndex historicalLogIndex) {
        this.historyStore = Objects.requireNonNull(historyStore, "historyStore");
        this.historicalLogIndex = Objects.requireNonNull(historicalLogIndex, "historicalLogIndex");
    }

    public RebuildSummary rebuildWorkspaceIfStale(String workspaceHash) throws Exception {
        Objects.requireNonNull(workspaceHash, "workspaceHash");
        if (workspaceHash.isBlank()) {
            throw new IllegalArgumentException("workspaceHash must not be blank");
        }

        var snapshotSessionIds = historyStore.listSnapshotSessionsForWorkspace(workspaceHash).stream()
                .sorted()
                .toList();
        if (snapshotSessionIds.isEmpty()) {
            return new RebuildSummary(false, 0, 0, Set.of(), Set.of());
        }

        var indexedSessionIds = historicalLogIndex.sessionIds(workspaceHash);
        var snapshotSessionSet = Set.copyOf(snapshotSessionIds);
        if (indexedSessionIds.containsAll(snapshotSessionSet)) {
            return new RebuildSummary(false, snapshotSessionIds.size(), indexedSessionIds.size(), snapshotSessionSet, indexedSessionIds);
        }

        var snapshots = snapshotSessionIds.stream()
                .map(historyStore::loadSnapshot)
                .flatMap(java.util.Optional::stream)
                .filter(snapshot -> workspaceHash.equals(snapshot.workspaceHash()))
                .toList();

        historicalLogIndex.replaceSessions(workspaceHash, snapshots);
        return new RebuildSummary(true, snapshots.size(), indexedSessionIds.size(), snapshotSessionSet, indexedSessionIds);
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
