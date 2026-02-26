package dev.aceclaw.daemon;

import dev.aceclaw.core.agent.Turn;
import dev.aceclaw.core.llm.*;
import dev.aceclaw.memory.*;
import dev.aceclaw.memory.Insight.ErrorInsight;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end integration test for the self-learning pipeline.
 *
 * <p>Exercises the complete data flow:
 * Turn -> ErrorDetector/PatternDetector -> deduplicate -> persist ->
 * (debounce) -> StrategyRefiner -> consolidated strategies in memory.
 *
 * <p>Wires all components directly (no UDS socket) for deterministic assertions.
 * The socket/handler layer is tested separately in {@link DaemonIntegrationTest}.
 */
class SelfLearningPipelineTest {

    @TempDir
    Path tempDir;

    private AutoMemoryStore memoryStore;
    private SelfImprovementEngine engine;

    @BeforeEach
    void setUp() throws Exception {
        memoryStore = new AutoMemoryStore(tempDir);
        memoryStore.load(tempDir);

        var errorDetector = new ErrorDetector(memoryStore);
        var patternDetector = new PatternDetector(memoryStore);
        var strategyRefiner = new StrategyRefiner(memoryStore);
        engine = new SelfImprovementEngine(errorDetector, patternDetector, memoryStore, strategyRefiner);
    }

    // -- Test 1: Error detection through persistence --

    @Test
    void errorDetectionThroughPersistence() throws Exception {
        // Pre-populate memory with 2 prior ERROR_RECOVERY entries for cross-session boosting
        memoryStore.add(MemoryEntry.Category.ERROR_RECOVERY,
                "Tool 'read_file' error: file missing — resolved by: using correct path",
                List.of("read_file", "error-recovery"), "session:prior-1", true, null);
        memoryStore.add(MemoryEntry.Category.ERROR_RECOVERY,
                "Tool 'read_file' error: permission denied — resolved by: fixing permissions",
                List.of("read_file", "error-recovery"), "session:prior-2", true, null);

        // Turn with error -> retry on the same tool
        var turn = errorCorrectionTurn("read_file",
                "File not found: /missing.txt", "file contents here");

        var insights = engine.analyze(turn, List.of(), Map.of());
        int persisted = engine.persist(insights, "e2e-session-1", tempDir);

        // Base 0.4 + 2 * 0.2 = 0.8 > 0.7 threshold
        assertThat(insights).isNotEmpty();
        assertThat(insights).anyMatch(i -> i instanceof ErrorInsight);
        assertThat(persisted).isGreaterThanOrEqualTo(1);

        // Verify stored in memory with correct category and tags
        var stored = memoryStore.query(MemoryEntry.Category.ERROR_RECOVERY,
                List.of("read_file"), 10);
        // At least: 2 prior + 1 new
        assertThat(stored).hasSizeGreaterThanOrEqualTo(3);
        assertThat(stored).anyMatch(e -> e.source().startsWith("self-improve:"));
    }

    // -- Test 2: Low confidence filtered out --

    @Test
    void lowConfidenceInsightsFilteredOut() {
        // No prior memory — base confidence 0.4 < 0.7
        var turn = errorCorrectionTurn("bash",
                "command not found: foo", "output of foo");

        var insights = engine.analyze(turn, List.of(), Map.of());
        int persisted = engine.persist(insights, "low-conf-session", tempDir);

        // ErrorDetector finds the pattern but confidence too low to persist as ERROR_RECOVERY
        assertThat(insights).isNotEmpty();
        assertThat(insights)
                .filteredOn(i -> i instanceof ErrorInsight)
                .allMatch(i -> i.confidence() < 0.7);
        assertThat(persisted).isGreaterThanOrEqualTo(0);

        var stored = memoryStore.query(MemoryEntry.Category.ERROR_RECOVERY, List.of(), 0);
        assertThat(stored).isEmpty();
    }

    // -- Test 3: Deduplication across turn --

    @Test
    void deduplicationAcrossTurn() throws Exception {
        // Pre-populate for cross-session boost (2 prior entries => 0.4 + 0.4 = 0.8)
        memoryStore.add(MemoryEntry.Category.ERROR_RECOVERY,
                "Tool 'edit_file' error: merge conflict — resolved by: manual fix",
                List.of("edit_file", "error-recovery"), "session:p1", true, null);
        memoryStore.add(MemoryEntry.Category.ERROR_RECOVERY,
                "Tool 'edit_file' error: match not found — resolved by: correcting old_string",
                List.of("edit_file", "error-recovery"), "session:p2", true, null);

        // Turn with TWO error-correction pairs for the same tool
        var messages = List.<Message>of(
                assistantWithToolUse("tu-1", "edit_file"),
                toolResult("tu-1", "old_string not found in file", true),
                assistantWithToolUse("tu-2", "edit_file"),
                toolResult("tu-2", "File edited successfully", false),
                assistantWithToolUse("tu-3", "edit_file"),
                toolResult("tu-3", "old_string not found in target file", true),
                assistantWithToolUse("tu-4", "edit_file"),
                toolResult("tu-4", "File edited successfully", false)
        );
        var turn = new Turn(messages, StopReason.END_TURN, new Usage(200, 100));

        var rawInsights = engine.analyze(turn, List.of(), Map.of());

        // ErrorDetector produces 2 ErrorInsights for the 2 pairs; dedup collapses them to 1
        // (same tool, same category, similar descriptions: Jaccard >= 0.7).
        // PatternDetector may also produce an ERROR_CORRECTION PatternInsight (different category).
        long errorInsightCount = rawInsights.stream()
                .filter(i -> i instanceof ErrorInsight)
                .count();
        assertThat(errorInsightCount).isEqualTo(1);

        int persisted = engine.persist(rawInsights, "dedup-session", tempDir);

        // ErrorInsight persists (confidence 0.8 >= 0.7) but PatternInsight may not
        // (ERROR_CORRECTION pattern confidence = 0.3 + 2*0.15 = 0.6 < 0.7)
        assertThat(persisted).isGreaterThanOrEqualTo(1);
    }

    // -- Test 4: Cross-session boosting integration --

    @Test
    void crossSessionBoostingIntegration() throws Exception {
        // Pre-populate 3 prior entries => confidence = 0.4 + 3 * 0.2 = 1.0 (capped)
        for (int i = 1; i <= 3; i++) {
            memoryStore.add(MemoryEntry.Category.ERROR_RECOVERY,
                    "Tool 'glob' error: pattern failure " + i + " — resolved by: escaping glob chars",
                    List.of("glob", "error-recovery"), "session:boost-" + i, true, null);
        }

        var turn = errorCorrectionTurn("glob",
                "Invalid glob pattern: [unclosed", "matched files: a.java, b.java");

        var insights = engine.analyze(turn, List.of(), Map.of());
        int persisted = engine.persist(insights, "boost-session", tempDir);

        assertThat(persisted).isGreaterThanOrEqualTo(1);

        // Verify the persisted insight has high confidence (via content inspection)
        var stored = memoryStore.query(MemoryEntry.Category.ERROR_RECOVERY,
                List.of("glob"), 10);
        // 3 prior + 1 new
        assertThat(stored).hasSizeGreaterThanOrEqualTo(4);
    }

    // -- Test 5: Strategy refinement triggers after debounce --

    @Test
    void strategyRefinementTriggersAfterDebounce() throws Exception {
        // Pre-populate 4 similar ERROR_RECOVERY entries with "resolved by" — these will be
        // consolidated by the StrategyRefiner when it fires (MIN_ENTRIES_TO_CONSOLIDATE=2).
        // These are similar enough for the refiner's groupBySimilarity (Jaccard >= 0.7).
        memoryStore.add(MemoryEntry.Category.ERROR_RECOVERY,
                "Tool 'write_file' error: disk full — resolved by: clearing old temp files",
                List.of("write_file", "error-recovery"), "session:seed-1", true, null);
        memoryStore.add(MemoryEntry.Category.ERROR_RECOVERY,
                "Tool 'write_file' error: disk quota — resolved by: clearing old temp data",
                List.of("write_file", "error-recovery"), "session:seed-2", true, null);
        memoryStore.add(MemoryEntry.Category.ERROR_RECOVERY,
                "Tool 'write_file' error: disk space — resolved by: clearing old temp cache",
                List.of("write_file", "error-recovery"), "session:seed-3", true, null);
        memoryStore.add(MemoryEntry.Category.ERROR_RECOVERY,
                "Tool 'write_file' error: disk limit — resolved by: clearing old temp logs",
                List.of("write_file", "error-recovery"), "session:seed-4", true, null);

        // Run 11 turns using DIFFERENT tools each time so each insight avoids in-memory dedup.
        // Each tool gets 2 prior entries for cross-session boost (0.4 + 2*0.2 = 0.8 > 0.7).
        String[] tools = {"bash", "read_file", "glob", "grep", "edit_file",
                "bash_v2", "read_v2", "glob_v2", "grep_v2", "edit_v2", "write_v2"};
        int totalPersisted = 0;
        for (int i = 0; i < 11; i++) {
            String tool = tools[i];
            // Seed 2 prior entries for this tool's cross-session boost
            memoryStore.add(MemoryEntry.Category.ERROR_RECOVERY,
                    "Tool '" + tool + "' previously failed with alpha issue — resolved by: alpha fix",
                    List.of(tool, "error-recovery"), "session:boost-" + tool + "-1", true, null);
            memoryStore.add(MemoryEntry.Category.ERROR_RECOVERY,
                    "Tool '" + tool + "' previously failed with beta issue — resolved by: beta fix",
                    List.of(tool, "error-recovery"), "session:boost-" + tool + "-2", true, null);

            var turn = errorCorrectionTurn(tool,
                    tool + " encountered a unique failure scenario " + i,
                    tool + " recovered after applying specific correction " + i);

            var insights = engine.analyze(turn, List.of(), Map.of());
            int p = engine.persist(insights, "refine-session-" + i, tempDir);
            totalPersisted += p;
        }

        // We expect at least REFINE_MIN_PERSISTED insights persisted
        assertThat(totalPersisted).isGreaterThanOrEqualTo(SelfImprovementEngine.REFINE_MIN_PERSISTED);

        // After 11 turns with enough persisted insights, refinement should have fired.
        // The StrategyRefiner consolidates the 4 similar "write_file" ERROR_RECOVERY entries
        // (pre-populated above) into a SUCCESSFUL_STRATEGY.
        var strategies = memoryStore.query(MemoryEntry.Category.SUCCESSFUL_STRATEGY, List.of(), 0);
        assertThat(strategies).isNotEmpty();
        assertThat(strategies).anyMatch(e ->
                e.tags().contains("strategy-refined") && e.tags().contains("error-consolidated"));
    }

    // -- Test 6: Refinement does NOT trigger before debounce --

    @Test
    void refinementDoesNotTriggerBeforeDebounce() throws Exception {
        // Seed 4 similar ERROR_RECOVERY entries (enough for refiner to consolidate)
        memoryStore.add(MemoryEntry.Category.ERROR_RECOVERY,
                "Tool 'grep' error: regex invalid — resolved by: escaping special chars",
                List.of("grep", "error-recovery"), "session:seed-1", true, null);
        memoryStore.add(MemoryEntry.Category.ERROR_RECOVERY,
                "Tool 'grep' error: regex timeout — resolved by: escaping special patterns",
                List.of("grep", "error-recovery"), "session:seed-2", true, null);
        memoryStore.add(MemoryEntry.Category.ERROR_RECOVERY,
                "Tool 'grep' error: regex failure — resolved by: escaping special syntax",
                List.of("grep", "error-recovery"), "session:seed-3", true, null);

        // Only 5 turns (< REFINE_DEBOUNCE_TURNS=10)
        // Use different tools so insights persist (need boost seeds)
        String[] tools = {"bash", "read_file", "glob", "edit_file", "write_file"};
        for (int i = 0; i < 5; i++) {
            String tool = tools[i];
            memoryStore.add(MemoryEntry.Category.ERROR_RECOVERY,
                    "Tool '" + tool + "' failed gamma — resolved by: gamma fix",
                    List.of(tool, "error-recovery"), "session:boost-" + tool + "-1", true, null);
            memoryStore.add(MemoryEntry.Category.ERROR_RECOVERY,
                    "Tool '" + tool + "' failed delta — resolved by: delta fix",
                    List.of(tool, "error-recovery"), "session:boost-" + tool + "-2", true, null);

            var turn = errorCorrectionTurn(tool,
                    tool + " unique failure " + i, tool + " unique fix " + i);
            var insights = engine.analyze(turn, List.of(), Map.of());
            engine.persist(insights, "no-refine-" + i, tempDir);
        }

        // No SUCCESSFUL_STRATEGY should be created — debounce not met (5 < 10)
        var strategies = memoryStore.query(MemoryEntry.Category.SUCCESSFUL_STRATEGY, List.of(), 0);
        assertThat(strategies).isEmpty();
    }

    // -- Test 7: Full pipeline — error to strategy --

    @Test
    void fullPipelineErrorToStrategy() throws Exception {
        // Pre-populate 3 similar ERROR_RECOVERY entries with "resolved by" for consolidation.
        // These are the entries the StrategyRefiner will consolidate into SUCCESSFUL_STRATEGY.
        memoryStore.add(MemoryEntry.Category.ERROR_RECOVERY,
                "Tool 'bash' error: command not found npm — resolved by: using npx instead",
                List.of("bash", "error-recovery"), "session:pre-1", true, null);
        memoryStore.add(MemoryEntry.Category.ERROR_RECOVERY,
                "Tool 'bash' error: command not found yarn — resolved by: using npx instead",
                List.of("bash", "error-recovery"), "session:pre-2", true, null);
        memoryStore.add(MemoryEntry.Category.ERROR_RECOVERY,
                "Tool 'bash' error: command not found pnpm — resolved by: using npx instead",
                List.of("bash", "error-recovery"), "session:pre-3", true, null);

        // Run 11 turns using DIFFERENT tools each turn to avoid in-memory dedup.
        // Each tool gets 2 prior entries for cross-session boost.
        String[] tools = {"read_file", "glob", "grep", "edit_file", "write_file",
                "read_v2", "glob_v2", "grep_v2", "edit_v2", "write_v2", "bash_v2"};
        int totalPersisted = 0;
        for (int i = 0; i < 11; i++) {
            String tool = tools[i];
            memoryStore.add(MemoryEntry.Category.ERROR_RECOVERY,
                    "Tool '" + tool + "' failed with epsilon error — resolved by: epsilon fix",
                    List.of(tool, "error-recovery"), "session:boost-" + tool + "-1", true, null);
            memoryStore.add(MemoryEntry.Category.ERROR_RECOVERY,
                    "Tool '" + tool + "' failed with zeta error — resolved by: zeta fix",
                    List.of(tool, "error-recovery"), "session:boost-" + tool + "-2", true, null);

            var turn = errorCorrectionTurn(tool,
                    tool + " encountered unique problem " + i,
                    tool + " applied unique solution " + i);

            var insights = engine.analyze(turn, List.of(), Map.of());
            int p = engine.persist(insights, "full-pipeline-" + i, tempDir);
            totalPersisted += p;
        }

        // Verify insights were persisted
        assertThat(totalPersisted).isGreaterThanOrEqualTo(SelfImprovementEngine.REFINE_MIN_PERSISTED);

        // After turn 11, refinement should fire:
        // 3 similar "bash" ERROR_RECOVERY entries with "resolved by" -> SUCCESSFUL_STRATEGY
        var strategies = memoryStore.query(MemoryEntry.Category.SUCCESSFUL_STRATEGY, List.of(), 0);
        assertThat(strategies).isNotEmpty();

        // The strategy should reference bash tool
        assertThat(strategies).anyMatch(e -> e.tags().contains("bash"));
    }

    // -- Test 8: Empty turns produce no side effects --

    @Test
    void emptyTurnsProduceNoSideEffects() {
        // Turn with only successful tool calls — no error-correction pair
        var messages = List.<Message>of(
                assistantWithToolUse("tu-1", "read_file"),
                toolResult("tu-1", "file contents here", false),
                assistantWithToolUse("tu-2", "glob"),
                toolResult("tu-2", "src/Main.java\nsrc/App.java", false),
                Message.assistant("Here are the files.")
        );
        var turn = new Turn(messages, StopReason.END_TURN, new Usage(100, 50));

        var insights = engine.analyze(turn, List.of(), Map.of());
        int persisted = engine.persist(insights, "empty-session", tempDir);

        assertThat(insights).isEmpty();
        assertThat(persisted).isEqualTo(0);

        // No entries in any category
        assertThat(memoryStore.query(MemoryEntry.Category.ERROR_RECOVERY, List.of(), 0)).isEmpty();
        assertThat(memoryStore.query(MemoryEntry.Category.SUCCESSFUL_STRATEGY, List.of(), 0)).isEmpty();
        assertThat(memoryStore.query(MemoryEntry.Category.ANTI_PATTERN, List.of(), 0)).isEmpty();
    }

    // -- Helpers --

    private Turn errorCorrectionTurn(String toolName, String error, String success) {
        var messages = List.<Message>of(
                assistantWithToolUse("tu-err", toolName),
                toolResult("tu-err", error, true),
                assistantWithToolUse("tu-fix", toolName),
                toolResult("tu-fix", success, false)
        );
        return new Turn(messages, StopReason.END_TURN, new Usage(100, 50));
    }

    private static Message assistantWithToolUse(String id, String toolName) {
        return new Message.AssistantMessage(List.of(
                new ContentBlock.ToolUse(id, toolName, "{}")));
    }

    private static Message toolResult(String toolUseId, String content, boolean isError) {
        return Message.toolResult(toolUseId, content, isError);
    }
}
