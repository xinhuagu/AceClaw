package dev.aceclaw.daemon;

import dev.aceclaw.memory.HistoricalLogIndex;
import dev.aceclaw.memory.HistoricalSessionSnapshot;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

/**
 * Rebuilds workspace-scoped historical index entries from persisted session history when needed.
 */
public final class HistoricalIndexRebuilder {

    private final SessionHistoryStore historyStore;
    private final HistoricalLogIndex historicalLogIndex;
    private final ConcurrentHashMap<String, ReentrantLock> workspaceLocks = new ConcurrentHashMap<>();

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
        var workspaceLock = workspaceLocks.computeIfAbsent(workspaceHash, ignored -> new ReentrantLock());
        workspaceLock.lock();
        try {
            var historySessionIds = historyStore.listSessionsForWorkspace(workspaceHash).stream()
                    .sorted()
                    .toList();
            var snapshotSessionIds = historyStore.listSnapshotSessionsForWorkspace(workspaceHash).stream()
                    .sorted()
                    .toList();
            var indexedSessionIds = historicalLogIndex.sessionIds(workspaceHash);
            if (snapshotSessionIds.isEmpty()) {
                if (historySessionIds.isEmpty() && !indexedSessionIds.isEmpty()) {
                    historicalLogIndex.clearWorkspace(workspaceHash);
                    return new RebuildSummary(true, 0, indexedSessionIds.size(), Set.of(), indexedSessionIds);
                }
                return new RebuildSummary(false, 0, indexedSessionIds.size(), Set.of(), indexedSessionIds);
            }

            var snapshotSessionSet = Set.copyOf(snapshotSessionIds);
            var legacyHistoryIds = historySessionIds.stream()
                    .filter(sessionId -> !snapshotSessionSet.contains(sessionId))
                    .collect(Collectors.toSet());
            var snapshots = snapshotSessionIds.stream()
                    .map(historyStore::loadSnapshot)
                    .flatMap(java.util.Optional::stream)
                    .filter(snapshot -> workspaceHash.equals(snapshot.workspaceHash()))
                    .toList();
            var expectedIndexedSessionIds = snapshots.stream()
                    .filter(HistoricalIndexRebuilder::producesIndexEntries)
                    .map(HistoricalSessionSnapshot::sessionId)
                    .collect(Collectors.toSet());
            var comparableIndexedSessionIds = indexedSessionIds.stream()
                    .filter(sessionId -> !legacyHistoryIds.contains(sessionId))
                    .collect(Collectors.toSet());
            if (comparableIndexedSessionIds.equals(expectedIndexedSessionIds)) {
                return new RebuildSummary(false, snapshots.size(), indexedSessionIds.size(), snapshotSessionSet, indexedSessionIds);
            }

            historicalLogIndex.replaceSessions(workspaceHash, snapshots);
            return new RebuildSummary(true, snapshots.size(), indexedSessionIds.size(), snapshotSessionSet, indexedSessionIds);
        } finally {
            workspaceLock.unlock();
        }
    }

    private static boolean producesIndexEntries(HistoricalSessionSnapshot snapshot) {
        return !snapshot.toolMetrics().isEmpty()
                || !snapshot.errorsEncountered().isEmpty()
                || snapshot.backtrackingDetected()
                || !snapshot.endToEndStrategy().isBlank();
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
