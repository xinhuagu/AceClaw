package dev.aceclaw.memory;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class StrategyRefinerTest {

    @TempDir
    Path tempDir;

    private AutoMemoryStore store;
    private StrategyRefiner refiner;
    private Path projectPath;

    @BeforeEach
    void setUp() throws IOException {
        store = new AutoMemoryStore(tempDir);
        projectPath = tempDir.resolve("workspace");
        Files.createDirectories(projectPath);
        store.load(projectPath);
        refiner = new StrategyRefiner(store);
    }

    // -- Strategy 1: Error Recovery Consolidation --

    @Test
    void errorConsolidationCreatesStrategyFrom3SimilarErrors() {
        // 3 similar ERROR_RECOVERY entries about read_file failures (high pairwise similarity)
        store.add(MemoryEntry.Category.ERROR_RECOVERY,
                "Tool read_file error: file not found — resolved by: checking path exists first",
                List.of("read_file", "error-recovery"), "session:1", false, projectPath);
        store.add(MemoryEntry.Category.ERROR_RECOVERY,
                "Tool read_file error: file not found — resolved by: checking path exists before read",
                List.of("read_file", "error-recovery"), "session:2", false, projectPath);
        store.add(MemoryEntry.Category.ERROR_RECOVERY,
                "Tool read_file error: file not found — resolved by: checking path exists beforehand",
                List.of("read_file", "error-recovery"), "session:3", false, projectPath);

        assertThat(store.size()).isEqualTo(3);

        var result = refiner.refine(List.of(), projectPath);

        assertThat(result.strategiesCreated()).isEqualTo(1);
        assertThat(result.entriesConsolidated()).isEqualTo(3);
        assertThat(result.hasChanges()).isTrue();

        // Old entries removed, new strategy created
        var strategies = store.query(MemoryEntry.Category.SUCCESSFUL_STRATEGY, null, 0);
        assertThat(strategies).hasSize(1);
        assertThat(strategies.getFirst().tags()).contains("strategy-refined", "error-consolidated", "read_file");
        assertThat(strategies.getFirst().source()).isEqualTo("strategy-refiner");

        // Original entries should be gone
        var remaining = store.query(MemoryEntry.Category.ERROR_RECOVERY, null, 0);
        assertThat(remaining).isEmpty();
    }

    @Test
    void errorConsolidationSkipsWhenLessThan3Entries() {
        store.add(MemoryEntry.Category.ERROR_RECOVERY,
                "Tool bash error: command not found — resolved by: using full path",
                List.of("bash", "error-recovery"), "session:1", false, projectPath);
        store.add(MemoryEntry.Category.ERROR_RECOVERY,
                "Tool bash error: command not found — resolved by: installing dependency",
                List.of("bash", "error-recovery"), "session:2", false, projectPath);

        var result = refiner.refine(List.of(), projectPath);

        assertThat(result.strategiesCreated()).isEqualTo(0);
        assertThat(result.entriesConsolidated()).isEqualTo(0);
        assertThat(result.hasChanges()).isFalse();
        assertThat(store.size()).isEqualTo(2);
    }

    // -- Strategy 2: Tool Sequence Optimization --

    @Test
    void sequenceOptimizationCreatesStrategyFrom3SimilarPatterns() {
        store.add(MemoryEntry.Category.PATTERN,
                "Repeated tool sequence: glob_search then read_file then edit_file for code modification tasks",
                List.of("glob_search", "read_file", "edit_file", "tool-sequence"), "session:1", false, projectPath);
        store.add(MemoryEntry.Category.PATTERN,
                "Repeated tool sequence: glob_search then read_file then edit_file for code modification work",
                List.of("glob_search", "read_file", "edit_file", "tool-sequence"), "session:2", false, projectPath);
        store.add(MemoryEntry.Category.PATTERN,
                "Repeated tool sequence: glob_search then read_file then edit_file for code modification changes",
                List.of("glob_search", "read_file", "edit_file", "tool-sequence"), "session:3", false, projectPath);

        var result = refiner.refine(List.of(), projectPath);

        assertThat(result.strategiesCreated()).isEqualTo(1);
        assertThat(result.entriesConsolidated()).isEqualTo(3);

        var strategies = store.query(MemoryEntry.Category.SUCCESSFUL_STRATEGY, null, 0);
        assertThat(strategies).hasSize(1);
        assertThat(strategies.getFirst().tags()).contains("strategy-refined", "sequence-optimized");
    }

    // -- Strategy 3: Anti-Pattern Generation --

    @Test
    void antiPatternGeneratedFrom3RecurringUnresolvedErrors() {
        // Errors without "resolved by" or "fix:" in content
        store.add(MemoryEntry.Category.ERROR_RECOVERY,
                "Tool bash error: permission denied when writing to /etc/config",
                List.of("bash", "error-recovery"), "session:1", false, projectPath);
        store.add(MemoryEntry.Category.ERROR_RECOVERY,
                "Tool bash error: permission denied when writing to /etc/settings",
                List.of("bash", "error-recovery"), "session:2", false, projectPath);
        store.add(MemoryEntry.Category.ERROR_RECOVERY,
                "Tool bash error: permission denied when writing to /etc/hosts",
                List.of("bash", "error-recovery"), "session:3", false, projectPath);

        var result = refiner.refine(List.of(), projectPath);

        assertThat(result.antiPatternsCreated()).isEqualTo(1);
        assertThat(result.entriesConsolidated()).isEqualTo(3);

        var antiPatterns = store.query(MemoryEntry.Category.ANTI_PATTERN, null, 0);
        assertThat(antiPatterns).hasSize(1);
        assertThat(antiPatterns.getFirst().content()).startsWith("Avoid:");
        assertThat(antiPatterns.getFirst().tags()).contains("anti-pattern", "bash");
    }

    // -- Strategy 4: User Preference Strengthening --

    @Test
    void preferenceStrengtheningFrom3SimilarCorrections() {
        store.add(MemoryEntry.Category.CORRECTION,
                "User prefers snake_case for variable naming in Python files",
                List.of("naming"), "session:1", false, projectPath);
        store.add(MemoryEntry.Category.CORRECTION,
                "User prefers snake_case for variable naming in Python code",
                List.of("naming"), "session:2", false, projectPath);
        store.add(MemoryEntry.Category.CORRECTION,
                "User prefers snake_case for variable naming in Python modules",
                List.of("naming"), "session:3", false, projectPath);

        var result = refiner.refine(List.of(), projectPath);

        assertThat(result.preferencesStrengthened()).isEqualTo(1);
        assertThat(result.entriesConsolidated()).isEqualTo(3);

        var prefs = store.query(MemoryEntry.Category.PREFERENCE, null, 0);
        assertThat(prefs).hasSize(1);
        assertThat(prefs.getFirst().content()).startsWith("User preference:");
        assertThat(prefs.getFirst().tags()).contains("preference-strengthened");

        // Original corrections removed
        var corrections = store.query(MemoryEntry.Category.CORRECTION, null, 0);
        assertThat(corrections).isEmpty();
    }

    // -- Mixed and Edge Cases --

    @Test
    void mixedStrategiesInOneRefineCall() {
        // 3 similar error recoveries
        for (int i = 0; i < 3; i++) {
            store.add(MemoryEntry.Category.ERROR_RECOVERY,
                    "Tool write_file error: disk full when writing output file variant " + i
                            + " — resolved by: clearing temp files first",
                    List.of("write_file", "error-recovery"), "session:" + i, false, projectPath);
        }

        // 3 similar corrections
        for (int i = 0; i < 3; i++) {
            store.add(MemoryEntry.Category.CORRECTION,
                    "User wants concise commit messages without emoji in project repositories " + i,
                    List.of("git"), "session:" + i, false, projectPath);
        }

        assertThat(store.size()).isEqualTo(6);

        var result = refiner.refine(List.of(), projectPath);

        assertThat(result.strategiesCreated()).isGreaterThanOrEqualTo(1);
        assertThat(result.preferencesStrengthened()).isGreaterThanOrEqualTo(1);
        assertThat(result.hasChanges()).isTrue();
    }

    @Test
    void emptyStoreReturnsNoChanges() {
        var result = refiner.refine(List.of(), projectPath);

        assertThat(result.strategiesCreated()).isEqualTo(0);
        assertThat(result.entriesConsolidated()).isEqualTo(0);
        assertThat(result.antiPatternsCreated()).isEqualTo(0);
        assertThat(result.preferencesStrengthened()).isEqualTo(0);
        assertThat(result.hasChanges()).isFalse();
    }

    @Test
    void alreadyRefinedEntriesAreSkipped() {
        // Add entries that are already refined (tagged with "strategy-refined")
        store.add(MemoryEntry.Category.ERROR_RECOVERY,
                "Tool read_file error: file not found — resolved by: checking exists",
                List.of("read_file", "error-recovery", "strategy-refined"), "session:1", false, projectPath);
        store.add(MemoryEntry.Category.ERROR_RECOVERY,
                "Tool read_file error: file not found — resolved by: verifying path",
                List.of("read_file", "error-recovery", "strategy-refined"), "session:2", false, projectPath);
        store.add(MemoryEntry.Category.ERROR_RECOVERY,
                "Tool read_file error: file not found — resolved by: path check first",
                List.of("read_file", "error-recovery", "strategy-refined"), "session:3", false, projectPath);

        int initialSize = store.size();
        var result = refiner.refine(List.of(), projectPath);

        // No new strategies since entries are already refined
        assertThat(result.strategiesCreated()).isEqualTo(0);
        assertThat(store.size()).isEqualTo(initialSize);
    }

    // -- Jaccard similarity --

    @Test
    void jaccardSimilarityIdentical() {
        assertThat(StrategyRefiner.jaccardSimilarity("hello world test", "hello world test"))
                .isEqualTo(1.0);
    }

    @Test
    void jaccardSimilarityNoOverlap() {
        assertThat(StrategyRefiner.jaccardSimilarity("hello world", "foo bar"))
                .isEqualTo(0.0);
    }

    @Test
    void jaccardSimilarityHandlesNullAndBlank() {
        assertThat(StrategyRefiner.jaccardSimilarity(null, "test")).isEqualTo(0.0);
        assertThat(StrategyRefiner.jaccardSimilarity("test", null)).isEqualTo(0.0);
        assertThat(StrategyRefiner.jaccardSimilarity("", "test")).isEqualTo(0.0);
        assertThat(StrategyRefiner.jaccardSimilarity("  ", "test")).isEqualTo(0.0);
    }
}
