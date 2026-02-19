package dev.aceclaw.daemon;

import dev.aceclaw.core.agent.ToolMetrics;
import dev.aceclaw.core.agent.Turn;
import dev.aceclaw.memory.AutoMemoryStore;
import dev.aceclaw.memory.Insight;
import dev.aceclaw.memory.MemoryEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.*;

/**
 * Orchestrates the self-learning pipeline: runs detectors, deduplicates insights,
 * filters noise, and persists high-confidence learnings to {@link AutoMemoryStore}.
 *
 * <p>Pipeline:
 * <pre>
 * Agent Turn
 *   ├── ErrorDetector  → ErrorInsights
 *   └── PatternDetector → PatternInsights
 *         ↓
 *   SelfImprovementEngine (deduplicate + filter)
 *         ↓
 *   AutoMemoryStore.add() (persist high-confidence insights)
 * </pre>
 *
 * <p>Designed to run asynchronously on a virtual thread after each agent turn.
 * Failures are logged but never propagate to the agent session.
 */
public final class SelfImprovementEngine {

    private static final Logger log = LoggerFactory.getLogger(SelfImprovementEngine.class);

    /** Only persist insights with confidence at or above this threshold. */
    static final double PERSISTENCE_THRESHOLD = 0.7;

    /** Jaccard similarity threshold for deduplication against existing memory. */
    private static final double DEDUP_SIMILARITY_THRESHOLD = 0.7;

    /** Maximum length for insight content stored in memory. */
    private static final int MAX_CONTENT_LENGTH = 500;

    private final ErrorDetector errorDetector;
    private final PatternDetector patternDetector;
    private final AutoMemoryStore memoryStore;

    public SelfImprovementEngine(ErrorDetector errorDetector,
                                  PatternDetector patternDetector,
                                  AutoMemoryStore memoryStore) {
        this.errorDetector = Objects.requireNonNull(errorDetector, "errorDetector");
        this.patternDetector = Objects.requireNonNull(patternDetector, "patternDetector");
        this.memoryStore = Objects.requireNonNull(memoryStore, "memoryStore");
    }

    /**
     * Analyzes a completed turn and returns deduplicated insights from all detectors.
     *
     * @param turn           the completed agent turn
     * @param sessionHistory full session conversation history
     * @param toolMetrics    per-tool execution statistics
     * @return deduplicated insights (never null, may be empty)
     */
    public List<Insight> analyze(Turn turn,
                                  List<AgentSession.ConversationMessage> sessionHistory,
                                  Map<String, ToolMetrics> toolMetrics) {
        var insights = new ArrayList<Insight>();

        try {
            insights.addAll(errorDetector.analyze(turn));
        } catch (Exception e) {
            log.warn("ErrorDetector failed: {}", e.getMessage());
        }

        try {
            insights.addAll(patternDetector.analyze(turn, sessionHistory, toolMetrics));
        } catch (Exception e) {
            log.warn("PatternDetector failed: {}", e.getMessage());
        }

        return deduplicate(insights);
    }

    /**
     * Persists high-confidence insights to AutoMemoryStore.
     *
     * @param insights    the insights to consider for persistence
     * @param sessionId   the session that produced these insights
     * @param projectPath the project directory for memory storage
     * @return the number of insights actually persisted
     */
    public int persist(List<Insight> insights, String sessionId, Path projectPath) {
        int persisted = 0;

        for (var insight : insights) {
            if (insight.confidence() < PERSISTENCE_THRESHOLD) {
                log.debug("Skipping low-confidence insight ({} < {}): {}",
                        insight.confidence(), PERSISTENCE_THRESHOLD, truncate(insight.description()));
                continue;
            }

            // Check for duplicates in existing memory
            if (isDuplicateInMemory(insight)) {
                log.debug("Skipping duplicate insight: {}", truncate(insight.description()));
                continue;
            }

            try {
                String content = truncate(insight.description(), MAX_CONTENT_LENGTH);
                memoryStore.add(
                        insight.targetCategory(),
                        content,
                        insight.tags(),
                        "self-improve:" + sessionId,
                        false,
                        projectPath);
                persisted++;
                log.debug("Persisted insight: category={}, confidence={}, content={}",
                        insight.targetCategory(), insight.confidence(), truncate(content));
            } catch (Exception e) {
                log.warn("Failed to persist insight: {}", e.getMessage());
            }
        }

        return persisted;
    }

    /**
     * Deduplicates insights by grouping on category + description similarity.
     * For each group, keeps the highest-confidence insight.
     */
    List<Insight> deduplicate(List<Insight> insights) {
        if (insights.size() <= 1) {
            return List.copyOf(insights);
        }

        var result = new ArrayList<Insight>();
        var used = new boolean[insights.size()];

        for (int i = 0; i < insights.size(); i++) {
            if (used[i]) continue;

            var best = insights.get(i);
            used[i] = true;

            for (int j = i + 1; j < insights.size(); j++) {
                if (used[j]) continue;

                var other = insights.get(j);
                if (best.targetCategory() == other.targetCategory()
                        && jaccardSimilarity(best.description(), other.description()) >= DEDUP_SIMILARITY_THRESHOLD) {
                    used[j] = true;
                    if (other.confidence() > best.confidence()) {
                        best = other;
                    }
                }
            }

            result.add(best);
        }

        return List.copyOf(result);
    }

    private boolean isDuplicateInMemory(Insight insight) {
        try {
            var existing = memoryStore.query(insight.targetCategory(), insight.tags(), 10);
            for (var entry : existing) {
                if (jaccardSimilarity(entry.content(), insight.description()) >= DEDUP_SIMILARITY_THRESHOLD) {
                    return true;
                }
            }
        } catch (Exception e) {
            log.warn("Failed to check for duplicates in memory: {}", e.getMessage());
        }
        return false;
    }

    static double jaccardSimilarity(String a, String b) {
        if (a == null || b == null || a.isBlank() || b.isBlank()) return 0.0;
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

    private static Set<String> tokenize(String text) {
        var tokens = new HashSet<String>();
        for (var token : text.toLowerCase().split("\\W+")) {
            if (!token.isBlank()) tokens.add(token);
        }
        return tokens;
    }

    private static String truncate(String text) {
        return truncate(text, 100);
    }

    private static String truncate(String text, int maxChars) {
        if (text == null) return "";
        if (text.length() <= maxChars) return text;
        return text.substring(0, maxChars - 3) + "...";
    }
}
