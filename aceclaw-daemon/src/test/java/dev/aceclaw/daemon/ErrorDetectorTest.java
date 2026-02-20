package dev.aceclaw.daemon;

import dev.aceclaw.core.agent.Turn;
import dev.aceclaw.core.llm.*;
import dev.aceclaw.memory.AutoMemoryStore;
import dev.aceclaw.memory.ErrorClass;
import dev.aceclaw.memory.Insight;
import dev.aceclaw.memory.Insight.ErrorInsight;
import dev.aceclaw.memory.Insight.RecoveryRecipe;
import dev.aceclaw.memory.MemoryEntry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ErrorDetectorTest {

    private ErrorDetector detector;

    @BeforeEach
    void setUp() {
        detector = new ErrorDetector();
    }

    @Test
    void detectsSingleErrorCorrection() {
        // Assistant requests read_file, it fails, then retries and succeeds
        var messages = List.of(
                assistantWithToolUse("tu-1", "read_file", "{\"path\":\"/missing.txt\"}"),
                toolResult("tu-1", "File not found: /missing.txt", true),
                assistantWithToolUse("tu-2", "read_file", "{\"path\":\"/correct.txt\"}"),
                toolResult("tu-2", "file contents here", false)
        );

        var turn = new Turn(messages, StopReason.END_TURN, new Usage(0, 0));
        var insights = detector.analyze(turn);

        assertThat(insights).hasSize(1);
        assertThat(insights.getFirst()).isInstanceOf(ErrorInsight.class);
        var error = (ErrorInsight) insights.getFirst();
        assertThat(error.toolName()).isEqualTo("read_file");
        assertThat(error.errorMessage()).contains("File not found");
        assertThat(error.confidence()).isEqualTo(0.4);
    }

    @Test
    void detectsMultipleIndependentErrorCorrections() {
        var messages = List.of(
                // First error-correction pair: read_file
                assistantWithToolUse("tu-1", "read_file", "{\"path\":\"/bad.txt\"}"),
                toolResult("tu-1", "Permission denied", true),
                assistantWithToolUse("tu-2", "read_file", "{\"path\":\"/good.txt\"}"),
                toolResult("tu-2", "content", false),
                // Second error-correction pair: bash
                assistantWithToolUse("tu-3", "bash", "{\"command\":\"rm /root/x\"}"),
                toolResult("tu-3", "Permission denied", true),
                assistantWithToolUse("tu-4", "bash", "{\"command\":\"rm ~/x\"}"),
                toolResult("tu-4", "removed", false)
        );

        var turn = new Turn(messages, StopReason.END_TURN, new Usage(0, 0));
        var insights = detector.analyze(turn);

        assertThat(insights).hasSize(2);
        var toolNames = insights.stream()
                .filter(ErrorInsight.class::isInstance)
                .map(i -> ((ErrorInsight) i).toolName())
                .toList();
        assertThat(toolNames).containsExactlyInAnyOrder("read_file", "bash");
    }

    @Test
    void ignoresErrorsWithNoSuccessfulRetry() {
        var messages = List.of(
                assistantWithToolUse("tu-1", "read_file", "{\"path\":\"/missing.txt\"}"),
                toolResult("tu-1", "File not found", true),
                // No retry, just a text response
                Message.assistant("I could not find the file.")
        );

        var turn = new Turn(messages, StopReason.END_TURN, new Usage(0, 0));
        var insights = detector.analyze(turn);

        assertThat(insights).isEmpty();
    }

    @Test
    void ignoresSuccessWithoutPriorError() {
        var messages = List.of(
                assistantWithToolUse("tu-1", "read_file", "{\"path\":\"/ok.txt\"}"),
                toolResult("tu-1", "content here", false)
        );

        var turn = new Turn(messages, StopReason.END_TURN, new Usage(0, 0));
        var insights = detector.analyze(turn);

        assertThat(insights).isEmpty();
    }

    @Test
    void handlesEmptyTurn() {
        var turn = new Turn(List.of(), StopReason.END_TURN, new Usage(0, 0));
        assertThat(detector.analyze(turn)).isEmpty();
    }

    @Test
    void handlesNullTurn() {
        assertThat(detector.analyze(null)).isEmpty();
    }

    @Test
    void doesNotMatchDifferentToolNames() {
        // Error in read_file, success in bash — should NOT be paired
        var messages = List.of(
                assistantWithToolUse("tu-1", "read_file", "{\"path\":\"/x\"}"),
                toolResult("tu-1", "error", true),
                assistantWithToolUse("tu-2", "bash", "{\"command\":\"ls\"}"),
                toolResult("tu-2", "ok", false)
        );

        var turn = new Turn(messages, StopReason.END_TURN, new Usage(0, 0));
        var insights = detector.analyze(turn);

        assertThat(insights).isEmpty();
    }

    @Test
    void successMustOccurAfterError() {
        // Success BEFORE error should NOT count
        var messages = List.of(
                assistantWithToolUse("tu-1", "read_file", "{\"path\":\"/ok.txt\"}"),
                toolResult("tu-1", "content", false),
                assistantWithToolUse("tu-2", "read_file", "{\"path\":\"/bad.txt\"}"),
                toolResult("tu-2", "File not found", true)
        );

        var turn = new Turn(messages, StopReason.END_TURN, new Usage(0, 0));
        var insights = detector.analyze(turn);

        assertThat(insights).isEmpty();
    }

    @Test
    void truncatesLongErrorMessages() {
        String longError = "E".repeat(600);
        var messages = List.of(
                assistantWithToolUse("tu-1", "bash", "{\"command\":\"fail\"}"),
                toolResult("tu-1", longError, true),
                assistantWithToolUse("tu-2", "bash", "{\"command\":\"ok\"}"),
                toolResult("tu-2", "done", false)
        );

        var turn = new Turn(messages, StopReason.END_TURN, new Usage(0, 0));
        var insights = detector.analyze(turn);

        assertThat(insights).hasSize(1);
        var error = (ErrorInsight) insights.getFirst();
        assertThat(error.errorMessage().length()).isLessThanOrEqualTo(500);
        assertThat(error.errorMessage()).endsWith("...");
    }

    @Test
    void crossSessionBoostingIncreasesConfidence(@TempDir Path tempDir) throws Exception {
        var store = new AutoMemoryStore(tempDir);
        store.load(tempDir);

        // Add 2 prior error recovery entries for "read_file"
        store.add(MemoryEntry.Category.ERROR_RECOVERY,
                "read_file failed with missing path, resolved by checking existence first",
                List.of("read_file", "error-recovery"), "session:prior1", true, null);
        store.add(MemoryEntry.Category.ERROR_RECOVERY,
                "read_file permission denied, resolved by using absolute path",
                List.of("read_file", "error-recovery"), "session:prior2", true, null);

        var boostedDetector = new ErrorDetector(store);

        var messages = List.of(
                assistantWithToolUse("tu-1", "read_file", "{\"path\":\"/x\"}"),
                toolResult("tu-1", "File not found", true),
                assistantWithToolUse("tu-2", "read_file", "{\"path\":\"/y\"}"),
                toolResult("tu-2", "content", false)
        );

        var turn = new Turn(messages, StopReason.END_TURN, new Usage(0, 0));
        var insights = boostedDetector.analyze(turn);

        assertThat(insights).hasSize(1);
        // Base 0.4 + 2 * 0.2 = 0.8
        assertThat(insights.getFirst().confidence()).isEqualTo(0.8);
    }

    @Test
    void crossSessionConfidenceCappedAtOne(@TempDir Path tempDir) throws Exception {
        var store = new AutoMemoryStore(tempDir);
        store.load(tempDir);

        // Add 5 prior entries — should cap at 1.0 (base 0.4 + 5*0.2 = 1.4 → 1.0)
        for (int i = 0; i < 5; i++) {
            store.add(MemoryEntry.Category.ERROR_RECOVERY,
                    "bash error #" + i,
                    List.of("bash", "error-recovery"), "session:p" + i, true, null);
        }

        var boostedDetector = new ErrorDetector(store);

        var messages = List.of(
                assistantWithToolUse("tu-1", "bash", "{\"command\":\"fail\"}"),
                toolResult("tu-1", "error", true),
                assistantWithToolUse("tu-2", "bash", "{\"command\":\"ok\"}"),
                toolResult("tu-2", "success", false)
        );

        var turn = new Turn(messages, StopReason.END_TURN, new Usage(0, 0));
        var insights = boostedDetector.analyze(turn);

        assertThat(insights).hasSize(1);
        assertThat(insights.getFirst().confidence()).isEqualTo(1.0);
    }

    // -- multi-step recovery tests --

    @Test
    void detectsMultiStepRecovery() {
        // Error on read_file → bash (intermediate) → success on read_file
        var messages = List.of(
                assistantWithToolUse("tu-1", "read_file", "{\"path\":\"/missing.txt\"}"),
                toolResult("tu-1", "File not found: /missing.txt", true),
                assistantWithToolUse("tu-2", "bash", "{\"command\":\"find / -name missing.txt\"}"),
                toolResult("tu-2", "/home/user/missing.txt", false),
                assistantWithToolUse("tu-3", "read_file", "{\"path\":\"/home/user/missing.txt\"}"),
                toolResult("tu-3", "file contents here", false)
        );

        var turn = new Turn(messages, StopReason.END_TURN, new Usage(0, 0));
        var insights = detector.analyze(turn);

        // Should produce both an ErrorInsight (Phase 1: simple correction) and a RecoveryRecipe (Phase 2: multi-step)
        assertThat(insights).anyMatch(i -> i instanceof ErrorInsight);
        assertThat(insights).anyMatch(i -> i instanceof RecoveryRecipe);
        var recipe = insights.stream()
                .filter(RecoveryRecipe.class::isInstance)
                .map(RecoveryRecipe.class::cast)
                .findFirst().orElseThrow();
        assertThat(recipe.toolName()).isEqualTo("read_file");
        assertThat(recipe.steps()).hasSizeGreaterThanOrEqualTo(3); // error detect + bash intermediate + retry
        assertThat(recipe.triggerPattern()).contains("PATH_NOT_FOUND");
    }

    @Test
    void multiStepRecoveryRequiresIntermediateSteps() {
        // Error on read_file → success on read_file (NO intermediate tool)
        // This should produce an ErrorInsight, NOT a RecoveryRecipe
        var messages = List.of(
                assistantWithToolUse("tu-1", "read_file", "{\"path\":\"/missing.txt\"}"),
                toolResult("tu-1", "File not found: /missing.txt", true),
                assistantWithToolUse("tu-2", "read_file", "{\"path\":\"/correct.txt\"}"),
                toolResult("tu-2", "file contents here", false)
        );

        var turn = new Turn(messages, StopReason.END_TURN, new Usage(0, 0));
        var insights = detector.analyze(turn);

        assertThat(insights).noneMatch(i -> i instanceof RecoveryRecipe);
        assertThat(insights).anyMatch(i -> i instanceof ErrorInsight);
    }

    @Test
    void multiStepRecoveryIncludesAllIntermediateTools() {
        // Error on read_file → bash → grep (both intermediate) → success on read_file
        var messages = List.of(
                assistantWithToolUse("tu-1", "read_file", "{\"path\":\"/missing.txt\"}"),
                toolResult("tu-1", "File not found: /missing.txt", true),
                assistantWithToolUse("tu-2", "bash", "{\"command\":\"ls /\"}"),
                toolResult("tu-2", "home\nusr\ntmp", false),
                assistantWithToolUse("tu-3", "grep", "{\"pattern\":\"missing\"}"),
                toolResult("tu-3", "/home/user/missing.txt", false),
                assistantWithToolUse("tu-4", "read_file", "{\"path\":\"/home/user/missing.txt\"}"),
                toolResult("tu-4", "file contents", false)
        );

        var turn = new Turn(messages, StopReason.END_TURN, new Usage(0, 0));
        var insights = detector.analyze(turn);

        // Should have both ErrorInsight and RecoveryRecipe
        assertThat(insights).anyMatch(i -> i instanceof ErrorInsight);
        var recipes = insights.stream()
                .filter(RecoveryRecipe.class::isInstance)
                .map(RecoveryRecipe.class::cast)
                .toList();
        assertThat(recipes).hasSize(1);
        var recipe = recipes.getFirst();
        // Steps: error detect + bash + grep + retry = 4
        assertThat(recipe.steps()).hasSize(4);
        assertThat(recipe.steps().get(1).toolName()).isEqualTo("bash");
        assertThat(recipe.steps().get(2).toolName()).isEqualTo("grep");
    }

    @Test
    void errorClassSetOnErrorInsight() {
        var messages = List.of(
                assistantWithToolUse("tu-1", "read_file", "{\"path\":\"/secret.txt\"}"),
                toolResult("tu-1", "Permission denied: /secret.txt", true),
                assistantWithToolUse("tu-2", "read_file", "{\"path\":\"/other.txt\"}"),
                toolResult("tu-2", "content", false)
        );

        var turn = new Turn(messages, StopReason.END_TURN, new Usage(0, 0));
        var insights = detector.analyze(turn);

        assertThat(insights).hasSize(1);
        var error = (ErrorInsight) insights.getFirst();
        assertThat(error.errorClass()).isEqualTo(ErrorClass.PERMISSION);
    }

    // -- helpers --

    private static Message assistantWithToolUse(String id, String toolName, String inputJson) {
        return new Message.AssistantMessage(List.of(new ContentBlock.ToolUse(id, toolName, inputJson)));
    }

    private static Message toolResult(String toolUseId, String content, boolean isError) {
        return Message.toolResult(toolUseId, content, isError);
    }
}
