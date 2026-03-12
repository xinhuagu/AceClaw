package dev.aceclaw.memory;

import dev.aceclaw.core.agent.ToolMetrics;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CrossSessionPatternMinerTest {

    @TempDir
    Path tempDir;

    @Test
    void minesRecurringPatternsWithinWorkspaceAndPersistsMemory() throws Exception {
        var workspace = tempDir.resolve("workspace-a");
        var store = AutoMemoryStore.forWorkspace(tempDir, workspace);
        var index = new HistoricalLogIndex(tempDir);
        var miner = new CrossSessionPatternMiner();
        var t0 = Instant.parse("2026-03-12T10:00:00Z");
        String workspaceHash = WorkspacePaths.workspaceHash(workspace);

        index.index(snapshot("s1", workspaceHash, t0, 6, 2, true));
        index.index(snapshot("s2", workspaceHash, t0.plusSeconds(60), 5, 2, true));
        index.index(snapshot("s3", workspaceHash, t0.plusSeconds(120), 4, 2, true));
        index.index(snapshot("s4", workspaceHash, t0.plusSeconds(180), 3, 0, true));
        index.index(snapshot("s5", workspaceHash, t0.plusSeconds(240), 2, 0, true));

        // Noise from another workspace should not contribute.
        index.index(snapshot("other-1", "ws-other", t0.plusSeconds(300), 9, 4, true));

        var result = miner.mine(index, store, workspaceHash, workspace, 20);

        assertThat(result.frequentErrorChains())
                .singleElement()
                .satisfies(chain -> {
                    assertThat(chain.support()).isEqualTo(3);
                    assertThat(chain.chain()).containsExactly(ErrorClass.TIMEOUT, ErrorClass.PERMISSION);
                });

        assertThat(result.stableWorkflows())
                .singleElement()
                .satisfies(workflow -> assertThat(workflow.support()).isEqualTo(5));

        assertThat(result.convergingStrategies())
                .singleElement()
                .satisfies(strategy -> {
                    assertThat(strategy.support()).isEqualTo(5);
                    assertThat(strategy.lateAverageSteps()).isLessThan(strategy.earlyAverageSteps());
                });

        assertThat(store.query(MemoryEntry.Category.ANTI_PATTERN, List.of("cross-session"), 10))
                .anySatisfy(entry -> assertThat(entry.content()).contains("TIMEOUT->PERMISSION"));
        assertThat(store.query(MemoryEntry.Category.WORKFLOW, List.of("cross-session"), 10))
                .anySatisfy(entry -> assertThat(entry.content()).contains("Stable cross-session workflow"));
        assertThat(store.query(MemoryEntry.Category.SUCCESSFUL_STRATEGY, List.of("cross-session"), 10))
                .anySatisfy(entry -> assertThat(entry.content()).contains("converging strategy"));
    }

    @Test
    void detectsDegradationSignalsAndPersistsFailureSignal() throws Exception {
        var workspace = tempDir.resolve("workspace-b");
        var store = AutoMemoryStore.forWorkspace(tempDir, workspace);
        var index = new HistoricalLogIndex(tempDir);
        var miner = new CrossSessionPatternMiner();
        var t0 = Instant.parse("2026-03-12T10:00:00Z");
        String workspaceHash = WorkspacePaths.workspaceHash(workspace);

        index.index(snapshot("d1", workspaceHash, t0, 4, 0, false));
        index.index(snapshot("d2", workspaceHash, t0.plusSeconds(60), 4, 1, false));
        index.index(snapshot("d3", workspaceHash, t0.plusSeconds(120), 4, 3, false));
        index.index(snapshot("d4", workspaceHash, t0.plusSeconds(180), 4, 4, false));

        var result = miner.mine(index, store, workspaceHash, workspace, 20);

        assertThat(result.degradationSignals())
                .singleElement()
                .satisfies(signal -> {
                    assertThat(signal.toolName()).isEqualTo("bash");
                    assertThat(signal.laterErrorRate()).isGreaterThan(signal.earlierErrorRate());
                });
        assertThat(store.query(MemoryEntry.Category.FAILURE_SIGNAL, List.of("cross-session"), 10))
                .anySatisfy(entry -> assertThat(entry.content()).contains("degradation signal"));
    }

    private static HistoricalSessionSnapshot snapshot(String sessionId,
                                                     String workspaceHash,
                                                     Instant timestamp,
                                                     int invocations,
                                                     int errorCount,
                                                     boolean backtracking) {
        return new HistoricalSessionSnapshot(
                sessionId,
                workspaceHash,
                timestamp,
                List.of("bash build.sh", "rg compile src"),
                errorCount > 0
                        ? List.of("Command timed out after 30s", "Permission denied").subList(0, Math.min(2, errorCount >= 2 ? 2 : 1))
                        : List.of(),
                List.of("src/main/App.java"),
                Map.of("bash", new ToolMetrics("bash", invocations, Math.max(0, invocations - errorCount), errorCount, invocations * 100L, timestamp)),
                backtracking,
                "The end-to-end strategy was to inspect files, then run build commands."
        );
    }
}
