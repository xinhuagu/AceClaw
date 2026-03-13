package dev.aceclaw.daemon;

import dev.aceclaw.core.agent.ToolMetrics;
import dev.aceclaw.core.agent.Turn;
import dev.aceclaw.memory.AutoMemoryStore;
import dev.aceclaw.memory.CandidateKind;
import dev.aceclaw.memory.CandidateStore;
import dev.aceclaw.memory.Insight;
import dev.aceclaw.memory.MemoryEntry;
import dev.aceclaw.memory.StrategyRefiner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

/**
 * Orchestrates the self-learning pipeline: runs detectors, deduplicates insights,
 * filters noise, and persists high-confidence learnings to {@link AutoMemoryStore}.
 *
 * <p>Pipeline:
 * <pre>
 * Agent Turn
 *   ├── ErrorDetector  → ErrorInsights
 *   └── PatternDetector → PatternInsights
 *   └── FailureSignalDetector → FailureInsights
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

    /** Number of turns between strategy refinement runs. */
    static final int REFINE_DEBOUNCE_TURNS = 10;

    /** Minimum accumulated persisted insights before attempting refinement. */
    static final int REFINE_MIN_PERSISTED = 3;

    private final ErrorDetector errorDetector;
    private final PatternDetector patternDetector;
    private final FailureSignalDetector failureSignalDetector;
    private final AutoMemoryStore memoryStore;
    private final StrategyRefiner strategyRefiner;
    private final CandidateStore candidateStore;
    private final boolean candidateTransitionsEnabled;
    private final Consumer<Path> draftReevaluationTrigger;
    private LearningExplanationRecorder learningExplanationRecorder;

    private final AtomicInteger turnsSinceRefinement = new AtomicInteger();
    private final AtomicInteger persistedSinceLastRefinement = new AtomicInteger();
    /** Serializes the check-refine-reset block so only one thread runs refine() at a time. */
    private final ReentrantLock refinementLock = new ReentrantLock();

    /**
     * Creates a self-improvement engine without strategy refinement.
     * Backward-compatible with existing code and tests.
     */
    public SelfImprovementEngine(ErrorDetector errorDetector,
                                  PatternDetector patternDetector,
                                  AutoMemoryStore memoryStore) {
        this(errorDetector, patternDetector, new FailureSignalDetector(), memoryStore, null, null);
    }

    /**
     * Creates a self-improvement engine with optional strategy refinement.
     *
     * @param errorDetector    detects error-correction patterns
     * @param patternDetector  detects recurring behavioral patterns
     * @param memoryStore      persistent memory for insights
     * @param strategyRefiner  optional refiner that consolidates insights into strategies (nullable)
     */
    public SelfImprovementEngine(ErrorDetector errorDetector,
                                  PatternDetector patternDetector,
                                  AutoMemoryStore memoryStore,
                                  StrategyRefiner strategyRefiner) {
        this(errorDetector, patternDetector, new FailureSignalDetector(), memoryStore, strategyRefiner, null);
    }

    /**
     * Creates a self-improvement engine with explicit detectors and optional strategy refinement.
     */
    public SelfImprovementEngine(ErrorDetector errorDetector,
                                 PatternDetector patternDetector,
                                 FailureSignalDetector failureSignalDetector,
                                 AutoMemoryStore memoryStore,
                                 StrategyRefiner strategyRefiner) {
        this(errorDetector, patternDetector, failureSignalDetector, memoryStore, strategyRefiner, null);
    }

    /**
     * Creates a self-improvement engine with all dependencies including candidate store.
     *
     * @param errorDetector          detects error-correction patterns
     * @param patternDetector        detects recurring behavioral patterns
     * @param failureSignalDetector  detects normalized failure signals
     * @param memoryStore            persistent memory for insights
     * @param strategyRefiner        optional refiner (nullable)
     * @param candidateStore         optional candidate store for learning pipeline (nullable)
     */
    public SelfImprovementEngine(ErrorDetector errorDetector,
                                 PatternDetector patternDetector,
                                 FailureSignalDetector failureSignalDetector,
                                 AutoMemoryStore memoryStore,
                                 StrategyRefiner strategyRefiner,
                                 CandidateStore candidateStore) {
        this(errorDetector, patternDetector, failureSignalDetector, memoryStore,
                strategyRefiner, candidateStore, true, null);
    }

    /**
     * Creates a self-improvement engine with explicit control over candidate transitions.
     */
    public SelfImprovementEngine(ErrorDetector errorDetector,
                                 PatternDetector patternDetector,
                                 FailureSignalDetector failureSignalDetector,
                                 AutoMemoryStore memoryStore,
                                 StrategyRefiner strategyRefiner,
                                 CandidateStore candidateStore,
                                 boolean candidateTransitionsEnabled) {
        this(errorDetector, patternDetector, failureSignalDetector, memoryStore,
                strategyRefiner, candidateStore, candidateTransitionsEnabled, null);
    }

    /**
     * Creates a self-improvement engine with explicit candidate-transition and draft re-evaluation hooks.
     */
    public SelfImprovementEngine(ErrorDetector errorDetector,
                                 PatternDetector patternDetector,
                                 FailureSignalDetector failureSignalDetector,
                                 AutoMemoryStore memoryStore,
                                 StrategyRefiner strategyRefiner,
                                 CandidateStore candidateStore,
                                 boolean candidateTransitionsEnabled,
                                 Consumer<Path> draftReevaluationTrigger) {
        this.errorDetector = Objects.requireNonNull(errorDetector, "errorDetector");
        this.patternDetector = Objects.requireNonNull(patternDetector, "patternDetector");
        this.failureSignalDetector = Objects.requireNonNull(failureSignalDetector, "failureSignalDetector");
        this.memoryStore = Objects.requireNonNull(memoryStore, "memoryStore");
        this.strategyRefiner = strategyRefiner;
        this.candidateStore = candidateStore;
        this.candidateTransitionsEnabled = candidateTransitionsEnabled;
        this.draftReevaluationTrigger = draftReevaluationTrigger;
    }

    public void setLearningExplanationRecorder(LearningExplanationRecorder learningExplanationRecorder) {
        this.learningExplanationRecorder = learningExplanationRecorder;
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
        turnsSinceRefinement.incrementAndGet();

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

        try {
            insights.addAll(failureSignalDetector.analyze(turn));
        } catch (Exception e) {
            log.warn("FailureSignalDetector failed: {}", e.getMessage());
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
                if (learningExplanationRecorder != null) {
                    learningExplanationRecorder.recordMemoryWrite(
                            projectPath,
                            sessionId,
                            "self-improvement",
                            insight.targetCategory(),
                            content,
                            insight.tags(),
                            List.of(new LearningExplanation.EvidenceRef(
                                    "insight",
                                    insight.getClass().getSimpleName(),
                                    truncate(insight.description(), 140))));
                }
                persisted++;
                log.debug("Persisted insight: category={}, confidence={}, content={}",
                        insight.targetCategory(), insight.confidence(), truncate(content));
            } catch (Exception e) {
                log.warn("Failed to persist insight: {}", e.getMessage());
            }
        }

        // Upsert insights as candidate observations and evaluate state transitions
        if (candidateStore != null) {
            try {
                for (var insight : insights) {
                    if (insight.confidence() < PERSISTENCE_THRESHOLD) continue;
                    var kind = mapInsightToKind(insight);
                    boolean isSuccess = !(insight instanceof Insight.ErrorInsight)
                            && !(insight instanceof Insight.FailureInsight);
                    boolean severeFailure = insight instanceof Insight.FailureInsight f
                            && !f.retryable();
                    boolean correctionConflict = insight instanceof Insight.PatternInsight p
                            && p.patternType() == dev.aceclaw.memory.PatternType.USER_PREFERENCE;
                    var observation = new CandidateStore.CandidateObservation(
                            insight.targetCategory(), kind, truncate(insight.description(), MAX_CONTENT_LENGTH),
                            extractToolTag(insight), insight.tags(), insight.confidence(),
                            isSuccess ? 1 : 0, isSuccess ? 0 : 1,
                            "self-improve:" + sessionId, null,
                            severeFailure, correctionConflict, insight.description(), null);
                    var stored = candidateStore.upsert(observation);
                    if (learningExplanationRecorder != null) {
                        learningExplanationRecorder.recordCandidateObservation(
                                projectPath,
                                sessionId,
                                "self-improvement",
                                stored,
                                truncate(insight.description(), 160),
                                List.of(new LearningExplanation.EvidenceRef(
                                        "insight",
                                        insight.getClass().getSimpleName(),
                                        truncate(insight.description(), 140))));
                    }
                }
                if (candidateTransitionsEnabled) {
                    var transitions = candidateStore.evaluateAll();
                    if (!transitions.isEmpty()) {
                        log.info("Candidate pipeline: {} transitions applied after turn", transitions.size());
                        if (learningExplanationRecorder != null) {
                            for (var transition : transitions) {
                                learningExplanationRecorder.recordCandidateTransition(
                                        projectPath, sessionId, "self-improvement", transition);
                            }
                        }
                    }
                }
                // Always fire draft re-evaluation trigger (not just on new promotions).
                // Draft generation skips candidates that already have drafts (create-missing-only).
                // Validation only appends audit entries when the verdict changes.
                if (draftReevaluationTrigger != null) {
                    try {
                        draftReevaluationTrigger.accept(projectPath);
                    } catch (Exception e) {
                        log.warn("Auto draft generation/validation failed: {}", e.getMessage());
                    }
                }
            } catch (Exception e) {
                log.warn("Candidate pipeline evaluation failed: {}", e.getMessage());
            }
        }

        // Accumulate persisted count across turns and trigger refinement when enough have built up.
        // The lock serializes refinement so only one thread runs refine() at a time.
        // Subtracting the snapshot (instead of zeroing) preserves increments from concurrent threads.
        persistedSinceLastRefinement.addAndGet(persisted);
        if (strategyRefiner != null
                && persistedSinceLastRefinement.get() >= REFINE_MIN_PERSISTED
                && turnsSinceRefinement.get() >= REFINE_DEBOUNCE_TURNS
                && refinementLock.tryLock()) {
            try {
                // Snapshot under lock — other threads can still increment atomically
                int snapshotPersisted = persistedSinceLastRefinement.get();
                int snapshotTurns = turnsSinceRefinement.get();
                if (snapshotPersisted >= REFINE_MIN_PERSISTED && snapshotTurns >= REFINE_DEBOUNCE_TURNS) {
                    var result = strategyRefiner.refine(insights, projectPath);
                    if (result.hasChanges()) {
                        log.info("Strategy refinement after {} turns ({} persisted): {} strategies, {} anti-patterns, {} preferences ({} consolidated)",
                                snapshotTurns, snapshotPersisted,
                                result.strategiesCreated(),
                                result.antiPatternsCreated(), result.preferencesStrengthened(),
                                result.entriesConsolidated());
                    }
                    // Subtract snapshot values to preserve concurrent increments
                    turnsSinceRefinement.addAndGet(-snapshotTurns);
                    persistedSinceLastRefinement.addAndGet(-snapshotPersisted);
                }
            } catch (Exception e) {
                log.warn("Strategy refinement failed: {}", e.getMessage());
            } finally {
                refinementLock.unlock();
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

    private static CandidateKind mapInsightToKind(Insight insight) {
        return switch (insight) {
            case Insight.ErrorInsight _ -> CandidateKind.ERROR_RECOVERY;
            case Insight.RecoveryRecipe _ -> CandidateKind.ERROR_RECOVERY;
            case Insight.FailureInsight _ -> CandidateKind.ERROR_RECOVERY;
            case Insight.PatternInsight p -> switch (p.patternType()) {
                case USER_PREFERENCE -> CandidateKind.PREFERENCE;
                case WORKFLOW -> CandidateKind.WORKFLOW;
                default -> CandidateKind.WORKFLOW;
            };
            case Insight.SuccessInsight _ -> CandidateKind.WORKFLOW;
        };
    }

    private static String extractToolTag(Insight insight) {
        return switch (insight) {
            case Insight.ErrorInsight e -> e.toolName();
            case Insight.RecoveryRecipe r -> r.toolName();
            case Insight.FailureInsight f -> f.toolOrAgent();
            default -> "general";
        };
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
