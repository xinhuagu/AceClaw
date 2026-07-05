package dev.aceclaw.learning.maintenance;

import dev.aceclaw.memory.CandidateStore;
import dev.aceclaw.memory.CrossSessionPatternMiner;
import dev.aceclaw.memory.LearningCandidate;
import dev.aceclaw.memory.TrendDetector;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LearningMaintenanceCandidateBridgeTest {

    @TempDir
    Path tempDir;

    @Test
    void bridgesMinedSignalsIntoCandidateStore() throws Exception {
        var candidateStore = new CandidateStore(tempDir);
        var bridge = new LearningMaintenanceCandidateBridge(candidateStore);

        var result = bridge.bridge(
                "scheduled",
                new CrossSessionPatternMiner.MiningResult(
                        List.of(),
                        List.of(new CrossSessionPatternMiner.StableWorkflow(
                                "inspect-files>run-commands",
                                "Inspect files, then run commands.",
                                3,
                                0.82,
                                List.of("s1", "s2", "s3"))),
                        List.of(new CrossSessionPatternMiner.ConvergingStrategy(
                                "inspect-files>run-commands",
                                "Inspect files, then run commands.",
                                4,
                                8.0,
                                4.0,
                                0.86,
                                List.of("s1", "s2", "s3", "s4"))),
                        List.of()),
                List.of(new TrendDetector.Trend(
                        "overall.toolInvocationsPerSession",
                        TrendDetector.TrendDirection.FALLING,
                        -35.0,
                        10,
                        "Overall tool invocations per session fell from 8.0 to 4.0 across the last 10 sessions."))
        );

        assertThat(result.upserts()).isEqualTo(3);
        assertThat(result.transitions()).isGreaterThanOrEqualTo(0);
        assertThat(candidateStore.all()).hasSizeGreaterThanOrEqualTo(2);
        assertThat(candidateStore.all())
                .extracting(LearningCandidate::state)
                .isNotEmpty();
    }

    @Test
    void suppressesRecentlyObservedMaintenanceSourceRefs() throws Exception {
        var candidateStore = new CandidateStore(tempDir);
        candidateStore.upsert(new CandidateStore.CandidateObservation(
                dev.aceclaw.memory.MemoryEntry.Category.SUCCESSFUL_STRATEGY,
                dev.aceclaw.memory.CandidateKind.SKILL_SEED,
                "Inspect files, then run commands.",
                "general",
                List.of("cross-session", "converging-strategy", "maintenance"),
                0.86,
                1,
                0,
                "maintenance:scheduled:strategy:inspect-files>run-commands",
                java.time.Instant.now()));

        var bridge = new LearningMaintenanceCandidateBridge(candidateStore);
        var result = bridge.bridge(
                "scheduled",
                new CrossSessionPatternMiner.MiningResult(
                        List.of(),
                        List.of(),
                        List.of(new CrossSessionPatternMiner.ConvergingStrategy(
                                "inspect-files>run-commands",
                                "Inspect files, then run commands.",
                                4,
                                8.0,
                                4.0,
                                0.86,
                                List.of("s1", "s2", "s3", "s4"))),
                        List.of()),
                List.of());

        assertThat(result.upserts()).isEqualTo(0);
    }
}
