package dev.aceclaw.daemon;

import dev.aceclaw.memory.HistoricalLogIndex;
import dev.aceclaw.memory.HistoricalSessionSnapshot;

import java.util.List;
import java.util.Map;
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
            var legacyIndexedIds = indexedSessionIds.stream()
                    .filter(sessionId -> !snapshotSessionSet.contains(sessionId))
                    .collect(Collectors.toSet());
            var snapshots = snapshotSessionIds.stream()
                    .map(historyStore::loadSnapshot)
                    .flatMap(java.util.Optional::stream)
                    .filter(snapshot -> workspaceHash.equals(snapshot.workspaceHash()))
                    .toList();
            var expectedCoverage = expectedCoverage(snapshots);
            var expectedIndexedSessionIds = expectedCoverage.keySet();
            var comparableIndexedSessionIds = indexedSessionIds.stream()
                    .filter(sessionId -> !legacyHistoryIds.contains(sessionId) && !legacyIndexedIds.contains(sessionId))
                    .collect(Collectors.toSet());
            if (comparableIndexedSessionIds.equals(expectedIndexedSessionIds)
                    && comparableCoverage(historicalLogIndex.sessionCoverage(workspaceHash), legacyHistoryIds, legacyIndexedIds)
                    .equals(expectedCoverage)) {
                return new RebuildSummary(false, snapshots.size(), indexedSessionIds.size(), snapshotSessionSet, indexedSessionIds);
            }

            historicalLogIndex.replaceSessions(workspaceHash, snapshots);
            var actualCoverage = comparableCoverage(
                    historicalLogIndex.sessionCoverage(workspaceHash),
                    legacyHistoryIds,
                    legacyIndexedIds);
            if (!actualCoverage.equals(expectedCoverage)) {
                throw new IllegalStateException("Historical index rebuild produced inconsistent coverage for workspace "
                        + workspaceHash + ": expected=" + expectedCoverage + ", actual=" + actualCoverage);
            }
            return new RebuildSummary(true, snapshots.size(), indexedSessionIds.size(), snapshotSessionSet, indexedSessionIds);
        } finally {
            workspaceLock.unlock();
        }
    }

    private static Map<String, HistoricalLogIndex.SessionCoverage> expectedCoverage(List<HistoricalSessionSnapshot> snapshots) {
        var coverage = new java.util.LinkedHashMap<String, HistoricalLogIndex.SessionCoverage>();
        for (var snapshot : snapshots) {
            var expected = new HistoricalLogIndex.SessionCoverage(
                    snapshot.toolMetrics().size(),
                    snapshot.errorsEncountered().size(),
                    patternRowCount(snapshot));
            if (expected.toolRows() > 0 || expected.errorRows() > 0 || expected.patternRows() > 0) {
                coverage.put(snapshot.sessionId(), expected);
            }
        }
        return Map.copyOf(coverage);
    }

    private static int patternRowCount(HistoricalSessionSnapshot snapshot) {
        int count = 0;
        if (snapshot.backtrackingDetected()) {
            count++;
        }
        if (!snapshot.endToEndStrategy().isBlank()) {
            count++;
        }
        return count;
    }

    private static Map<String, HistoricalLogIndex.SessionCoverage> comparableCoverage(
            Map<String, HistoricalLogIndex.SessionCoverage> actualCoverage,
            Set<String> legacyHistoryIds,
            Set<String> legacyIndexedIds) {
        return actualCoverage.entrySet().stream()
                .filter(entry -> !legacyHistoryIds.contains(entry.getKey()))
                .filter(entry -> !legacyIndexedIds.contains(entry.getKey()))
                .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, Map.Entry::getValue));
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
