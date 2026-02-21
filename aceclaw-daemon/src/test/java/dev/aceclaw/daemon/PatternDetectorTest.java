package dev.aceclaw.daemon;

import dev.aceclaw.core.agent.ToolMetrics;
import dev.aceclaw.core.agent.Turn;
import dev.aceclaw.core.llm.*;
import dev.aceclaw.memory.AutoMemoryStore;
import dev.aceclaw.memory.Insight.PatternInsight;
import dev.aceclaw.memory.MemoryEntry;
import dev.aceclaw.memory.PatternType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PatternDetectorTest {

    private PatternDetector detector;

    @BeforeEach
    void setUp() {
        detector = new PatternDetector();
    }

    // -- extractToolSequence tests --

    @Test
    void extractToolSequenceFromMessages() {
        var messages = List.<Message>of(
                assistantWithToolUse("t1", "grep"),
                toolResult("t1", "match found", false),
                assistantWithToolUse("t2", "read_file"),
                toolResult("t2", "content", false),
                assistantWithToolUse("t3", "edit_file"),
                toolResult("t3", "edited", false)
        );

        var seq = PatternDetector.extractToolSequence(messages);
        assertThat(seq).containsExactly("grep", "read_file", "edit_file");
    }

    @Test
    void extractToolSequenceIgnoresToolResults() {
        var messages = List.<Message>of(
                toolResult("t1", "some result", false),
                Message.assistant("Just text, no tools")
        );

        var seq = PatternDetector.extractToolSequence(messages);
        assertThat(seq).isEmpty();
    }

    // -- isSubsequenceMatch tests --

    @Test
    void subsequenceMatchFindsCommonSequence() {
        var a = List.of("grep", "read_file", "edit_file");
        var b = List.of("glob", "grep", "read_file", "edit_file", "bash");

        assertThat(PatternDetector.isSubsequenceMatch(a, b, 3)).isTrue();
    }

    @Test
    void subsequenceMatchRejectsTooShort() {
        var a = List.of("grep", "read_file");
        var b = List.of("grep", "read_file", "edit_file");

        assertThat(PatternDetector.isSubsequenceMatch(a, b, 3)).isFalse();
    }

    @Test
    void subsequenceMatchRejectsNoOverlap() {
        var a = List.of("grep", "read_file", "edit_file");
        var b = List.of("bash", "write_file", "glob");

        assertThat(PatternDetector.isSubsequenceMatch(a, b, 3)).isFalse();
    }

    // -- jaccardSimilarity tests --

    @Test
    void jaccardSimilarityIdenticalStrings() {
        assertThat(PatternDetector.jaccardSimilarity("use snake case", "use snake case"))
                .isEqualTo(1.0);
    }

    @Test
    void jaccardSimilarityPartialOverlap() {
        double sim = PatternDetector.jaccardSimilarity(
                "please use snake case for methods",
                "use snake case for variables");
        // shared: use, snake, case, for (4) / total: use, snake, case, for, please, methods, variables (7)
        assertThat(sim).isBetween(0.4, 0.7);
    }

    @Test
    void jaccardSimilarityNoOverlap() {
        assertThat(PatternDetector.jaccardSimilarity("hello world", "foo bar"))
                .isEqualTo(0.0);
    }

    @Test
    void jaccardSimilarityHandlesNullAndBlank() {
        assertThat(PatternDetector.jaccardSimilarity(null, "test")).isEqualTo(0.0);
        assertThat(PatternDetector.jaccardSimilarity("test", null)).isEqualTo(0.0);
        assertThat(PatternDetector.jaccardSimilarity("", "test")).isEqualTo(0.0);
        assertThat(PatternDetector.jaccardSimilarity("  ", "test")).isEqualTo(0.0);
    }

    // -- USER_PREFERENCE detection --

    @Test
    void detectsUserPreferenceFromRepeatedCorrections() {
        var history = List.<AgentSession.ConversationMessage>of(
                new AgentSession.ConversationMessage.Assistant("I'll use var here"),
                new AgentSession.ConversationMessage.User("no, please use explicit types always"),
                new AgentSession.ConversationMessage.Assistant("I'll use var again"),
                new AgentSession.ConversationMessage.User("no, please use explicit types for these")
        );

        var turn = new Turn(List.of(Message.assistant("ok")), StopReason.END_TURN, new Usage(0, 0));
        var insights = detector.analyze(turn, history, Map.of());

        var prefInsights = insights.stream()
                .filter(i -> i.patternType() == PatternType.USER_PREFERENCE)
                .toList();

        assertThat(prefInsights).hasSize(1);
        assertThat(prefInsights.getFirst().frequency()).isGreaterThanOrEqualTo(2);
    }

    @Test
    void noUserPreferenceFromSingleCorrection() {
        var history = List.<AgentSession.ConversationMessage>of(
                new AgentSession.ConversationMessage.Assistant("I used HashMap"),
                new AgentSession.ConversationMessage.User("no, use ConcurrentHashMap")
        );

        var turn = new Turn(List.of(Message.assistant("ok")), StopReason.END_TURN, new Usage(0, 0));
        var insights = detector.analyze(turn, history, Map.of());

        var prefInsights = insights.stream()
                .filter(i -> i.patternType() == PatternType.USER_PREFERENCE)
                .toList();
        assertThat(prefInsights).isEmpty();
    }

    // -- WORKFLOW detection --

    @Test
    void detectsWorkflowFromRepeatedPrompts() {
        var history = new ArrayList<AgentSession.ConversationMessage>();
        for (int i = 0; i < 4; i++) {
            history.add(new AgentSession.ConversationMessage.User("run the gradle build and fix errors"));
            history.add(new AgentSession.ConversationMessage.Assistant("Build completed successfully"));
        }

        var turn = new Turn(List.of(Message.assistant("done")), StopReason.END_TURN, new Usage(0, 0));
        var insights = detector.analyze(turn, history, Map.of());

        var workflowInsights = insights.stream()
                .filter(i -> i.patternType() == PatternType.WORKFLOW)
                .toList();

        assertThat(workflowInsights).hasSize(1);
        assertThat(workflowInsights.getFirst().frequency()).isGreaterThanOrEqualTo(3);
    }

    @Test
    void noWorkflowFromDifferentPrompts() {
        var history = List.<AgentSession.ConversationMessage>of(
                new AgentSession.ConversationMessage.User("fix the login bug"),
                new AgentSession.ConversationMessage.Assistant("Fixed"),
                new AgentSession.ConversationMessage.User("add a new API endpoint"),
                new AgentSession.ConversationMessage.Assistant("Added"),
                new AgentSession.ConversationMessage.User("update the README"),
                new AgentSession.ConversationMessage.Assistant("Updated")
        );

        var turn = new Turn(List.of(Message.assistant("done")), StopReason.END_TURN, new Usage(0, 0));
        var insights = detector.analyze(turn, history, Map.of());

        var workflowInsights = insights.stream()
                .filter(i -> i.patternType() == PatternType.WORKFLOW)
                .toList();
        assertThat(workflowInsights).isEmpty();
    }

    // -- ERROR_CORRECTION aggregation --

    @Test
    void detectsErrorCorrectionAcrossTurns() {
        // Current turn has a read_file error
        var turnMessages = List.<Message>of(
                assistantWithToolUse("t1", "read_file"),
                toolResult("t1", "File not found: /missing.txt", true),
                Message.assistant("The file was not found")
        );

        // Session history also had a read_file error
        var history = List.<AgentSession.ConversationMessage>of(
                new AgentSession.ConversationMessage.Assistant("Tool error: read_file /old.txt not found"),
                new AgentSession.ConversationMessage.User("try a different path")
        );

        var turn = new Turn(turnMessages, StopReason.END_TURN, new Usage(0, 0));
        var insights = detector.analyze(turn, history, Map.of());

        var errorInsights = insights.stream()
                .filter(i -> i.patternType() == PatternType.ERROR_CORRECTION)
                .toList();

        assertThat(errorInsights).hasSize(1);
        assertThat(errorInsights.getFirst().description()).contains("read_file");
    }

    // -- Edge cases --

    @Test
    void handlesNullTurn() {
        var insights = detector.analyze(null, List.of(), Map.of());
        assertThat(insights).isEmpty();
    }

    @Test
    void handlesEmptySessionHistory() {
        var turn = new Turn(List.of(Message.assistant("hi")), StopReason.END_TURN, new Usage(0, 0));
        var insights = detector.analyze(turn, List.of(), Map.of());
        assertThat(insights).isEmpty();
    }

    @Test
    void handlesNullSessionHistory() {
        var turn = new Turn(List.of(Message.assistant("hi")), StopReason.END_TURN, new Usage(0, 0));
        var insights = detector.analyze(turn, null, Map.of());
        assertThat(insights).isEmpty();
    }

    @Test
    void noFalsePositivesOnSingleOccurrence() {
        // A single tool sequence should not trigger any pattern
        var messages = List.<Message>of(
                assistantWithToolUse("t1", "grep"),
                toolResult("t1", "found", false),
                assistantWithToolUse("t2", "read_file"),
                toolResult("t2", "content", false),
                assistantWithToolUse("t3", "edit_file"),
                toolResult("t3", "edited", false)
        );

        var turn = new Turn(messages, StopReason.END_TURN, new Usage(0, 0));
        var insights = detector.analyze(turn, List.of(), Map.of());

        var seqInsights = insights.stream()
                .filter(i -> i.patternType() == PatternType.REPEATED_TOOL_SEQUENCE)
                .toList();
        assertThat(seqInsights).isEmpty();
    }

    @Test
    void crossSessionSequenceDetection(@TempDir Path tempDir) throws Exception {
        var store = new AutoMemoryStore(tempDir);
        store.load(tempDir);

        // Add 2 prior pattern entries containing the same tool sequence
        store.add(MemoryEntry.Category.PATTERN,
                "Repeated tool sequence [grep -> read_file -> edit_file]",
                List.of("tool-sequence", "grep", "read_file", "edit_file"),
                "session:prior1", true, null);
        store.add(MemoryEntry.Category.PATTERN,
                "Repeated tool sequence [grep -> read_file -> edit_file]",
                List.of("tool-sequence", "grep", "read_file", "edit_file"),
                "session:prior2", true, null);

        var crossDetector = new PatternDetector(store);

        var messages = List.<Message>of(
                assistantWithToolUse("t1", "grep"),
                toolResult("t1", "found", false),
                assistantWithToolUse("t2", "read_file"),
                toolResult("t2", "content", false),
                assistantWithToolUse("t3", "edit_file"),
                toolResult("t3", "edited", false)
        );

        var turn = new Turn(messages, StopReason.END_TURN, new Usage(0, 0));
        // 1 (current) + 2 (memory) = 3 >= MIN_SEQUENCE_FREQUENCY
        var insights = crossDetector.analyze(turn, List.of(), Map.of());

        var seqInsights = insights.stream()
                .filter(i -> i.patternType() == PatternType.REPEATED_TOOL_SEQUENCE)
                .toList();

        assertThat(seqInsights).hasSize(1);
        assertThat(seqInsights.getFirst().frequency()).isGreaterThanOrEqualTo(3);
    }

    // -- metrics-aware confidence boost tests --

    @Test
    void metricsHighErrorRateBoostsConfidence() {
        // Turn with a read_file error
        var turnMessages = List.<Message>of(
                assistantWithToolUse("t1", "read_file"),
                toolResult("t1", "File not found", true),
                Message.assistant("Could not read the file")
        );
        // Session history has another read_file error to meet frequency threshold
        var history = List.<AgentSession.ConversationMessage>of(
                new AgentSession.ConversationMessage.Assistant("Tool error: read_file /old.txt not found"),
                new AgentSession.ConversationMessage.User("try again")
        );
        var turn = new Turn(turnMessages, StopReason.END_TURN, new Usage(0, 0));

        // Without metrics: baseline confidence
        var withoutMetrics = detector.analyze(turn, history, Map.of());
        var baseInsight = withoutMetrics.stream()
                .filter(i -> i instanceof PatternInsight pi
                        && pi.patternType() == PatternType.ERROR_CORRECTION)
                .map(i -> (PatternInsight) i)
                .findFirst();
        assertThat(baseInsight).isPresent();
        double baseConfidence = baseInsight.get().confidence();

        // With metrics confirming 2/2 errors (100% error rate)
        var metrics = Map.of("read_file",
                new ToolMetrics("read_file", 2, 0, 2, 500L, java.time.Instant.now()));
        var withMetrics = detector.analyze(turn, history, metrics);
        var boostedInsight = withMetrics.stream()
                .filter(i -> i instanceof PatternInsight pi
                        && pi.patternType() == PatternType.ERROR_CORRECTION)
                .map(i -> (PatternInsight) i)
                .findFirst();
        assertThat(boostedInsight).isPresent();
        assertThat(boostedInsight.get().confidence()).isGreaterThan(baseConfidence);
    }

    @Test
    void metricsLowErrorRateDoesNotBoostConfidence() {
        // Turn with a read_file error
        var turnMessages = List.<Message>of(
                assistantWithToolUse("t1", "read_file"),
                toolResult("t1", "File not found", true),
                Message.assistant("Could not read the file")
        );
        var history = List.<AgentSession.ConversationMessage>of(
                new AgentSession.ConversationMessage.Assistant("Tool error: read_file /old.txt not found"),
                new AgentSession.ConversationMessage.User("try again")
        );
        var turn = new Turn(turnMessages, StopReason.END_TURN, new Usage(0, 0));

        double baseConfidence = detector.analyze(turn, history, Map.of()).stream()
                .filter(i -> i instanceof PatternInsight pi
                        && pi.patternType() == PatternType.ERROR_CORRECTION)
                .map(i -> (PatternInsight) i)
                .findFirst()
                .map(PatternInsight::confidence)
                .orElse(0.0);

        // Low error rate (1/10 = 10%) — no boost
        var metrics = Map.of("read_file",
                new ToolMetrics("read_file", 10, 9, 1, 2000L, java.time.Instant.now()));
        var withMetrics = detector.analyze(turn, history, metrics);
        var insight = withMetrics.stream()
                .filter(i -> i instanceof PatternInsight pi
                        && pi.patternType() == PatternType.ERROR_CORRECTION)
                .map(i -> (PatternInsight) i)
                .findFirst();
        assertThat(insight).isPresent();
        assertThat(insight.get().confidence()).isEqualTo(baseConfidence);
    }

    @Test
    void missingMetricsDoesNotBreakAnalysis() {
        var turnMessages = List.<Message>of(
                assistantWithToolUse("t1", "read_file"),
                toolResult("t1", "File not found", true),
                Message.assistant("Could not read the file")
        );
        var history = List.<AgentSession.ConversationMessage>of(
                new AgentSession.ConversationMessage.Assistant("Tool error: read_file /old.txt not found"),
                new AgentSession.ConversationMessage.User("try again")
        );
        var turn = new Turn(turnMessages, StopReason.END_TURN, new Usage(0, 0));

        // Metrics exist for a different tool — should not throw
        var metrics = Map.of("bash",
                new ToolMetrics("bash", 5, 5, 0, 1000L, java.time.Instant.now()));
        assertThat(detector.analyze(turn, history, metrics)).isNotEmpty();
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
