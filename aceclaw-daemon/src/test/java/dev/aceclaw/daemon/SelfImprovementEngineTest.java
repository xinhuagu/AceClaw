package dev.aceclaw.daemon;

import dev.aceclaw.core.agent.ToolMetrics;
import dev.aceclaw.core.agent.Turn;
import dev.aceclaw.core.llm.*;
import dev.aceclaw.memory.AutoMemoryStore;
import dev.aceclaw.memory.Insight;
import dev.aceclaw.memory.Insight.ErrorInsight;
import dev.aceclaw.memory.Insight.PatternInsight;
import dev.aceclaw.memory.MemoryEntry;
import dev.aceclaw.memory.PatternType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SelfImprovementEngineTest {

    private AutoMemoryStore memoryStore;
    private SelfImprovementEngine engine;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() throws Exception {
        memoryStore = new AutoMemoryStore(tempDir);
        memoryStore.load(tempDir);

        var errorDetector = new ErrorDetector(memoryStore);
        var patternDetector = new PatternDetector(memoryStore);
        engine = new SelfImprovementEngine(errorDetector, patternDetector, memoryStore);
    }

    // -- analyze() tests --

    @Test
    void analyzeDetectsErrorCorrection() {
        var messages = List.<Message>of(
                assistantWithToolUse("tu-1", "read_file"),
                toolResult("tu-1", "File not found: /missing.txt", true),
                assistantWithToolUse("tu-2", "read_file"),
                toolResult("tu-2", "file contents here", false)
        );
        var turn = new Turn(messages, StopReason.END_TURN, new Usage(0, 0));

        var insights = engine.analyze(turn, List.of(), Map.of());

        assertThat(insights).isNotEmpty();
        assertThat(insights).anyMatch(i -> i instanceof ErrorInsight);
    }

    @Test
    void analyzeReturnsEmptyForCleanTurn() {
        var messages = List.<Message>of(
                assistantWithToolUse("tu-1", "read_file"),
                toolResult("tu-1", "file contents", false),
                Message.assistant("Here is the file content.")
        );
        var turn = new Turn(messages, StopReason.END_TURN, new Usage(0, 0));

        var insights = engine.analyze(turn, List.of(), Map.of());
        assertThat(insights).isEmpty();
    }

    @Test
    void analyzeHandlesNullTurn() {
        var insights = engine.analyze(null, List.of(), Map.of());
        assertThat(insights).isEmpty();
    }

    // -- deduplicate() tests --

    @Test
    void deduplicateRemovesSimilarInsights() {
        var insights = List.<Insight>of(
                ErrorInsight.of("read_file", "File not found: /a.txt", "Used correct path", 0.8),
                ErrorInsight.of("read_file", "File not found: /b.txt", "Used correct path", 0.6)
        );

        var result = engine.deduplicate(insights);

        // Both insights are similar (same tool, same category, similar description)
        // Should keep only the higher-confidence one
        assertThat(result).hasSize(1);
        assertThat(result.getFirst().confidence()).isEqualTo(0.8);
    }

    @Test
    void deduplicateKeepsDifferentInsights() {
        var insights = List.<Insight>of(
                ErrorInsight.of("read_file", "File not found", "Used correct path", 0.8),
                new PatternInsight(PatternType.WORKFLOW, "Recurring workflow: build and test",
                        3, 0.7, List.of("prompt: build and test"))
        );

        var result = engine.deduplicate(insights);

        // Different categories should not be deduped
        assertThat(result).hasSize(2);
    }

    @Test
    void deduplicateHandlesEmptyList() {
        assertThat(engine.deduplicate(List.of())).isEmpty();
    }

    @Test
    void deduplicateHandlesSingleInsight() {
        var insights = List.<Insight>of(
                ErrorInsight.of("bash", "Permission denied", "Used sudo", 0.5)
        );
        assertThat(engine.deduplicate(insights)).hasSize(1);
    }

    // -- persist() tests --

    @Test
    void persistStoresHighConfidenceInsights() {
        var insights = List.<Insight>of(
                ErrorInsight.of("read_file", "File not found", "Used correct path", 0.9),
                ErrorInsight.of("bash", "Permission denied", "Changed dir", 0.8)
        );

        int count = engine.persist(insights, "test-session", tempDir);

        assertThat(count).isEqualTo(2);
        // Verify they actually ended up in memory
        var stored = memoryStore.query(MemoryEntry.Category.ERROR_RECOVERY, List.of(), 0);
        assertThat(stored).hasSizeGreaterThanOrEqualTo(2);
    }

    @Test
    void persistSkipsLowConfidenceInsights() {
        var insights = List.<Insight>of(
                ErrorInsight.of("read_file", "File not found", "Retry", 0.3), // below 0.7
                ErrorInsight.of("bash", "Timeout", "Retry with longer timeout", 0.5) // below 0.7
        );

        int count = engine.persist(insights, "test-session", tempDir);

        assertThat(count).isEqualTo(0);
        var stored = memoryStore.query(MemoryEntry.Category.ERROR_RECOVERY, List.of(), 0);
        assertThat(stored).isEmpty();
    }

    @Test
    void persistSkipsDuplicatesAlreadyInMemory() throws Exception {
        // Pre-populate memory with an existing entry
        memoryStore.add(MemoryEntry.Category.ERROR_RECOVERY,
                "Tool 'read_file' error: File not found: /missing.txt — resolved by: Used correct path",
                List.of("read_file", "error-recovery"),
                "prior-session", true, null);

        // Now try to persist a very similar insight
        var insights = List.<Insight>of(
                ErrorInsight.of("read_file", "File not found: /missing.txt", "Used correct path", 0.9)
        );

        int count = engine.persist(insights, "test-session", tempDir);

        // Should be skipped as duplicate
        assertThat(count).isEqualTo(0);
    }

    @Test
    void persistHandlesEmptyList() {
        int count = engine.persist(List.of(), "test-session", tempDir);
        assertThat(count).isEqualTo(0);
    }

    @Test
    void persistReturnsCorrectCount() {
        var insights = List.<Insight>of(
                ErrorInsight.of("bash", "command failed", "fixed command", 0.9),
                ErrorInsight.of("edit_file", "merge conflict", "resolved manually", 0.3), // below threshold
                new PatternInsight(PatternType.USER_PREFERENCE, "User prefers explicit types",
                        2, 0.8, List.of("correction: use explicit types"))
        );

        int count = engine.persist(insights, "test-session", tempDir);

        // Only 2 above threshold (0.9 and 0.8), edit_file one is 0.3
        assertThat(count).isEqualTo(2);
    }

    // -- jaccardSimilarity tests --

    @Test
    void jaccardSimilarityIdentical() {
        assertThat(SelfImprovementEngine.jaccardSimilarity("hello world", "hello world"))
                .isEqualTo(1.0);
    }

    @Test
    void jaccardSimilarityNoOverlap() {
        assertThat(SelfImprovementEngine.jaccardSimilarity("hello world", "foo bar"))
                .isEqualTo(0.0);
    }

    @Test
    void jaccardSimilarityPartialOverlap() {
        double sim = SelfImprovementEngine.jaccardSimilarity(
                "read file not found error",
                "read file permission denied error");
        // shared: read, file, error (3) / total: read, file, not, found, error, permission, denied (7)
        assertThat(sim).isBetween(0.3, 0.5);
    }

    @Test
    void jaccardSimilarityHandlesNullAndBlank() {
        assertThat(SelfImprovementEngine.jaccardSimilarity(null, "test")).isEqualTo(0.0);
        assertThat(SelfImprovementEngine.jaccardSimilarity("test", null)).isEqualTo(0.0);
        assertThat(SelfImprovementEngine.jaccardSimilarity("", "test")).isEqualTo(0.0);
        assertThat(SelfImprovementEngine.jaccardSimilarity("  ", "test")).isEqualTo(0.0);
    }

    // -- Integration: full pipeline --

    @Test
    void fullPipelineAnalyzeAndPersist() {
        // Turn with an error-correction pair
        var messages = List.<Message>of(
                assistantWithToolUse("tu-1", "bash"),
                toolResult("tu-1", "command not found: gradle", true),
                assistantWithToolUse("tu-2", "bash"),
                toolResult("tu-2", "BUILD SUCCESSFUL", false)
        );
        var turn = new Turn(messages, StopReason.END_TURN, new Usage(100, 50));

        var insights = engine.analyze(turn, List.of(), Map.of());
        int persisted = engine.persist(insights, "pipeline-session", tempDir);

        // ErrorDetector should find the bash error-correction, which has base confidence 0.4
        // That's below the 0.7 threshold, so 0 persisted unless cross-session boosted
        assertThat(insights).isNotEmpty();
        // Confidence is 0.4 (base, no cross-session boost), so won't persist
        assertThat(persisted).isEqualTo(0);
    }

    @Test
    void fullPipelineWithCrossSessionBoosting() throws Exception {
        // Add prior error recovery entries to boost confidence above threshold
        memoryStore.add(MemoryEntry.Category.ERROR_RECOVERY,
                "bash failed with command not found, resolved by using full path",
                List.of("bash", "error-recovery"), "session:p1", true, null);
        memoryStore.add(MemoryEntry.Category.ERROR_RECOVERY,
                "bash permission error, resolved by checking permissions",
                List.of("bash", "error-recovery"), "session:p2", true, null);

        var messages = List.<Message>of(
                assistantWithToolUse("tu-1", "bash"),
                toolResult("tu-1", "command not found: gradle", true),
                assistantWithToolUse("tu-2", "bash"),
                toolResult("tu-2", "BUILD SUCCESSFUL", false)
        );
        var turn = new Turn(messages, StopReason.END_TURN, new Usage(100, 50));

        var insights = engine.analyze(turn, List.of(), Map.of());
        int persisted = engine.persist(insights, "boosted-session", tempDir);

        // Base 0.4 + 2 * 0.2 = 0.8, above threshold
        assertThat(persisted).isGreaterThanOrEqualTo(1);
    }

    // -- helpers --

    private static Message assistantWithToolUse(String id, String toolName) {
        return new Message.AssistantMessage(List.of(
                new ContentBlock.ToolUse(id, toolName, "{}")));
    }

    private static Message toolResult(String toolUseId, String content, boolean isError) {
        return Message.toolResult(toolUseId, content, isError);
    }
}
