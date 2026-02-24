package dev.aceclaw.daemon;

import dev.aceclaw.core.agent.Turn;
import dev.aceclaw.core.agent.ToolMetrics;
import dev.aceclaw.core.llm.ContentBlock;
import dev.aceclaw.core.llm.Message;
import dev.aceclaw.memory.AutoMemoryStore;
import dev.aceclaw.memory.Insight.PatternInsight;
import dev.aceclaw.memory.MemoryEntry;
import dev.aceclaw.memory.PatternType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Detects recurring tool call sequences, user correction patterns, and workflow
 * repetitions across turns and sessions.
 *
 * <p>Operates on structured tool call data from {@link Turn} objects, not text
 * heuristics. Four detection strategies:
 * <ol>
 *   <li>{@link PatternType#REPEATED_TOOL_SEQUENCE} — same 3+ tool sequence appears 3+ times</li>
 *   <li>{@link PatternType#ERROR_CORRECTION} — aggregated error patterns across turns</li>
 *   <li>{@link PatternType#USER_PREFERENCE} — user corrections grouped by similarity</li>
 *   <li>{@link PatternType#WORKFLOW} — recurring user prompt patterns</li>
 * </ol>
 *
 * <p>All detection is heuristic-based (no LLM calls).
 */
public final class PatternDetector {

    private static final Logger log = LoggerFactory.getLogger(PatternDetector.class);

    private static final int MIN_SEQUENCE_LENGTH = 3;
    private static final int MIN_SEQUENCE_FREQUENCY = 3;
    private static final double JACCARD_CORRECTION_THRESHOLD = 0.6;
    private static final double JACCARD_WORKFLOW_THRESHOLD = 0.5;
    private static final int MIN_CORRECTION_FREQUENCY = 2;
    private static final int MIN_WORKFLOW_FREQUENCY = 3;

    private final AutoMemoryStore memoryStore;

    /**
     * Creates a detector with cross-session pattern lookup via the given memory store.
     */
    public PatternDetector(AutoMemoryStore memoryStore) {
        this.memoryStore = memoryStore;
    }

    /**
     * Creates a detector without cross-session pattern lookup.
     */
    public PatternDetector() {
        this(null);
    }

    /**
     * Analyzes a completed turn for patterns.
     *
     * @param turn           the completed agent turn
     * @param sessionHistory full session conversation history for cross-turn matching
     * @param toolMetrics    per-tool execution statistics from current session
     * @return detected patterns (may be empty)
     */
    public List<PatternInsight> analyze(
            Turn turn,
            List<AgentSession.ConversationMessage> sessionHistory,
            Map<String, ToolMetrics> toolMetrics) {

        var safeMetrics = toolMetrics != null ? toolMetrics : Map.<String, ToolMetrics>of();
        var insights = new ArrayList<PatternInsight>();

        if (turn != null) {
            insights.addAll(detectRepeatedToolSequences(turn, sessionHistory));
            insights.addAll(detectErrorCorrectionPatterns(turn, sessionHistory, safeMetrics));
        }

        if (sessionHistory != null && !sessionHistory.isEmpty()) {
            insights.addAll(detectUserPreferences(sessionHistory));
            insights.addAll(detectWorkflows(sessionHistory));
        }

        return List.copyOf(insights);
    }

    // -- Strategy 1: REPEATED_TOOL_SEQUENCE --

    private List<PatternInsight> detectRepeatedToolSequences(
            Turn turn, List<AgentSession.ConversationMessage> sessionHistory) {

        var currentSeq = extractToolSequence(turn.newMessages());
        if (currentSeq.size() < MIN_SEQUENCE_LENGTH) {
            return List.of();
        }

        // Count occurrences: current turn counts as 1
        int frequency = 1;
        var evidence = new ArrayList<String>();
        evidence.add("current turn: " + String.join(" -> ", currentSeq));

        // Check cross-session patterns from memory store
        if (memoryStore != null) {
            try {
                var priorPatterns = memoryStore.query(
                        MemoryEntry.Category.PATTERN, List.of("tool-sequence"), 0);
                for (var entry : priorPatterns) {
                    if (contentContainsSequence(entry.content(), currentSeq)) {
                        frequency++;
                        evidence.add("memory: " + truncate(entry.content(), 100));
                    }
                }
            } catch (Exception e) {
                log.warn("Failed to query memory for cross-session patterns: {}", e.getMessage());
            }
        }

        if (frequency < MIN_SEQUENCE_FREQUENCY) {
            return List.of();
        }

        double confidence = Math.min(1.0, 0.3 + frequency * 0.2);
        String description = "Repeated tool sequence [%s] observed %d times"
                .formatted(String.join(" -> ", currentSeq), frequency);

        return List.of(new PatternInsight(
                PatternType.REPEATED_TOOL_SEQUENCE, description, frequency, confidence, evidence));
    }

    // -- Strategy 2: ERROR_CORRECTION (cross-turn aggregation) --

    private List<PatternInsight> detectErrorCorrectionPatterns(
            Turn turn, List<AgentSession.ConversationMessage> sessionHistory) {
        return detectErrorCorrectionPatterns(turn, sessionHistory, Map.of());
    }

    private List<PatternInsight> detectErrorCorrectionPatterns(
            Turn turn, List<AgentSession.ConversationMessage> sessionHistory,
            Map<String, ToolMetrics> toolMetrics) {

        // Collect error tool names from current turn
        var errorTools = new HashMap<String, List<String>>();
        for (var msg : turn.newMessages()) {
            List<ContentBlock> blocks = switch (msg) {
                case Message.AssistantMessage am -> am.content();
                case Message.UserMessage um -> um.content();
            };
            for (var block : blocks) {
                if (block instanceof ContentBlock.ToolResult tr && tr.isError()) {
                    errorTools.computeIfAbsent(findToolName(turn.newMessages(), tr.toolUseId()),
                            _ -> new ArrayList<>()).add(tr.content());
                }
            }
        }

        if (errorTools.isEmpty()) {
            return List.of();
        }

        var insights = new ArrayList<PatternInsight>();

        for (var entry : errorTools.entrySet()) {
            String toolName = entry.getKey();
            if (toolName == null) continue;

            int currentErrorCount = entry.getValue().size();
            int frequency = currentErrorCount;
            var metrics = toolMetrics.get(toolName);
            if (metrics != null) {
                // Prefer structured metrics over text heuristics from assistant history.
                frequency = Math.max(frequency, metrics.errorCount());
            }

            if (frequency >= 2) {
                var evidence = new ArrayList<String>();
                evidence.add("current turn: " + toolName + " errors=" + currentErrorCount);

                double confidence = Math.min(1.0, 0.3 + frequency * 0.15);

                // Boost confidence when session metrics confirm a high error rate for this tool
                if (metrics != null && metrics.totalInvocations() > 0) {
                    double errorRate = (double) metrics.errorCount() / metrics.totalInvocations();
                    if (errorRate >= 0.5) {
                        confidence = Math.min(1.0, confidence + 0.15);
                        evidence.add("metrics: error rate=" + String.format("%.0f%%", errorRate * 100)
                                + " (" + metrics.errorCount() + "/" + metrics.totalInvocations() + ")");
                    }
                    evidence.add("metrics: session errors=" + metrics.errorCount());
                }

                String description = "Tool '%s' repeatedly fails (%d times in session)"
                        .formatted(toolName, frequency);

                insights.add(new PatternInsight(
                        PatternType.ERROR_CORRECTION, description, frequency, confidence, evidence));
            }
        }

        return insights;
    }

    // -- Strategy 3: USER_PREFERENCE --

    private List<PatternInsight> detectUserPreferences(
            List<AgentSession.ConversationMessage> sessionHistory) {

        // Collect user corrections (user message right after assistant)
        var corrections = new ArrayList<String>();
        for (int i = 1; i < sessionHistory.size(); i++) {
            if (sessionHistory.get(i) instanceof AgentSession.ConversationMessage.User user
                    && sessionHistory.get(i - 1) instanceof AgentSession.ConversationMessage.Assistant) {
                String text = user.content();
                if (text != null && !text.isBlank() && looksLikeCorrection(text)) {
                    corrections.add(text);
                }
            }
        }

        if (corrections.size() < MIN_CORRECTION_FREQUENCY) {
            return List.of();
        }

        // Group by Jaccard similarity
        var groups = groupBySimilarity(corrections, JACCARD_CORRECTION_THRESHOLD);
        var insights = new ArrayList<PatternInsight>();

        for (var group : groups) {
            if (group.size() >= MIN_CORRECTION_FREQUENCY) {
                var evidence = group.stream()
                        .map(c -> "correction: " + truncate(c, 100))
                        .toList();

                double confidence = Math.min(1.0, 0.3 + group.size() * 0.2);
                String description = "User repeatedly corrects: " + truncate(group.getFirst(), 150);

                insights.add(new PatternInsight(
                        PatternType.USER_PREFERENCE, description, group.size(), confidence, evidence));
            }
        }

        return insights;
    }

    // -- Strategy 4: WORKFLOW --

    private List<PatternInsight> detectWorkflows(
            List<AgentSession.ConversationMessage> sessionHistory) {

        // Collect user prompts (first user message of each "turn")
        var userPrompts = new ArrayList<String>();
        boolean expectingUser = true;
        for (var msg : sessionHistory) {
            if (msg instanceof AgentSession.ConversationMessage.User user && expectingUser) {
                String text = user.content();
                if (text != null && !text.isBlank()) {
                    userPrompts.add(text);
                    expectingUser = false;
                }
            } else if (msg instanceof AgentSession.ConversationMessage.Assistant) {
                expectingUser = true;
            }
        }

        if (userPrompts.size() < MIN_WORKFLOW_FREQUENCY) {
            return List.of();
        }

        // Group by Jaccard similarity
        var groups = groupBySimilarity(userPrompts, JACCARD_WORKFLOW_THRESHOLD);
        var insights = new ArrayList<PatternInsight>();

        for (var group : groups) {
            if (group.size() >= MIN_WORKFLOW_FREQUENCY) {
                var evidence = group.stream()
                        .map(p -> "prompt: " + truncate(p, 100))
                        .toList();

                double confidence = Math.min(1.0, 0.3 + group.size() * 0.15);
                String description = "Recurring workflow: " + truncate(group.getFirst(), 150);

                insights.add(new PatternInsight(
                        PatternType.WORKFLOW, description, group.size(), confidence, evidence));
            }
        }

        return insights;
    }

    // -- Helper: tool sequence extraction --

    /**
     * Extracts ordered tool name list from messages.
     * Filters to actual tool calls (ToolUse content blocks in assistant messages).
     */
    static List<String> extractToolSequence(List<Message> messages) {
        var sequence = new ArrayList<String>();
        for (var msg : messages) {
            if (msg instanceof Message.AssistantMessage am) {
                for (var block : am.content()) {
                    if (block instanceof ContentBlock.ToolUse tu) {
                        sequence.add(tu.name());
                    }
                }
            }
        }
        return List.copyOf(sequence);
    }

    /**
     * Checks if 'candidate' is a subsequence match against 'reference'.
     * Uses longest common subsequence (LCS) >= minLength as match criteria.
     */
    static boolean isSubsequenceMatch(List<String> candidate, List<String> reference, int minLength) {
        if (candidate.size() < minLength || reference.size() < minLength) {
            return false;
        }
        int lcs = longestCommonSubsequence(candidate, reference);
        return lcs >= minLength;
    }

    /**
     * Computes Jaccard similarity between two strings based on word tokens.
     * Returns |A intersection B| / |A union B|.
     */
    static double jaccardSimilarity(String a, String b) {
        if (a == null || b == null || a.isBlank() || b.isBlank()) {
            return 0.0;
        }
        var setA = tokenize(a);
        var setB = tokenize(b);
        if (setA.isEmpty() && setB.isEmpty()) return 1.0;
        if (setA.isEmpty() || setB.isEmpty()) return 0.0;

        var intersection = new HashSet<>(setA);
        intersection.retainAll(setB);

        var union = new HashSet<>(setA);
        union.addAll(setB);

        return (double) intersection.size() / union.size();
    }

    // -- internal helpers --

    private static int longestCommonSubsequence(List<String> a, List<String> b) {
        int m = a.size(), n = b.size();
        var dp = new int[m + 1][n + 1];
        for (int i = 1; i <= m; i++) {
            for (int j = 1; j <= n; j++) {
                if (a.get(i - 1).equals(b.get(j - 1))) {
                    dp[i][j] = dp[i - 1][j - 1] + 1;
                } else {
                    dp[i][j] = Math.max(dp[i - 1][j], dp[i][j - 1]);
                }
            }
        }
        return dp[m][n];
    }

    private static Set<String> tokenize(String text) {
        return Arrays.stream(text.toLowerCase().split("\\W+"))
                .filter(t -> !t.isBlank())
                .collect(Collectors.toSet());
    }

    private static String findToolName(List<Message> messages, String toolUseId) {
        for (var msg : messages) {
            if (msg instanceof Message.AssistantMessage am) {
                for (var block : am.content()) {
                    if (block instanceof ContentBlock.ToolUse tu && tu.id().equals(toolUseId)) {
                        return tu.name();
                    }
                }
            }
        }
        return null;
    }

    private static boolean contentContainsSequence(String content, List<String> sequence) {
        if (content == null) return false;
        for (var tool : sequence) {
            if (!content.contains(tool)) return false;
        }
        return true;
    }

    private static boolean looksLikeCorrection(String text) {
        var lower = text.toLowerCase();
        return lower.startsWith("no,") || lower.startsWith("no ") ||
                lower.startsWith("wrong") || lower.startsWith("actually") ||
                lower.contains("should be") || lower.contains("instead of") ||
                lower.startsWith("don't") || lower.startsWith("please use");
    }

    private static List<List<String>> groupBySimilarity(List<String> items, double threshold) {
        var groups = new ArrayList<List<String>>();
        var assigned = new boolean[items.size()];

        for (int i = 0; i < items.size(); i++) {
            if (assigned[i]) continue;

            var group = new ArrayList<String>();
            group.add(items.get(i));
            assigned[i] = true;

            for (int j = i + 1; j < items.size(); j++) {
                if (assigned[j]) continue;
                if (jaccardSimilarity(items.get(i), items.get(j)) >= threshold) {
                    group.add(items.get(j));
                    assigned[j] = true;
                }
            }

            groups.add(group);
        }

        return groups;
    }

    private static String truncate(String text, int maxChars) {
        if (text == null) return "";
        if (text.length() <= maxChars) return text;
        return text.substring(0, maxChars - 3) + "...";
    }
}
