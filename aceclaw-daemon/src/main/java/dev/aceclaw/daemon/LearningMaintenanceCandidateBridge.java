package dev.aceclaw.daemon;

import dev.aceclaw.memory.CandidateKind;
import dev.aceclaw.memory.CandidateState;
import dev.aceclaw.memory.CandidateStore;
import dev.aceclaw.memory.CrossSessionPatternMiner;
import dev.aceclaw.memory.ErrorClass;
import dev.aceclaw.memory.MemoryEntry;
import dev.aceclaw.memory.TrendDetector;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Bridges deferred learning-maintenance signals into the candidate pipeline.
 */
public final class LearningMaintenanceCandidateBridge {

    private final CandidateStore candidateStore;

    public LearningMaintenanceCandidateBridge(CandidateStore candidateStore) {
        this.candidateStore = Objects.requireNonNull(candidateStore, "candidateStore");
    }

    public BridgeResult bridge(String trigger,
                               CrossSessionPatternMiner.MiningResult miningResult,
                               List<TrendDetector.Trend> trends) {
        Objects.requireNonNull(trigger, "trigger");
        Objects.requireNonNull(miningResult, "miningResult");
        Objects.requireNonNull(trends, "trends");

        int upserts = 0;
        for (var observation : toObservations(trigger, miningResult, trends)) {
            candidateStore.upsert(observation);
            upserts++;
        }
        var transitions = candidateStore.evaluateAll();
        int promoted = (int) transitions.stream()
                .filter(t -> t.toState() == CandidateState.PROMOTED)
                .count();
        return new BridgeResult(upserts, transitions.size(), promoted);
    }

    private static List<CandidateStore.CandidateObservation> toObservations(
            String trigger,
            CrossSessionPatternMiner.MiningResult miningResult,
            List<TrendDetector.Trend> trends) {
        var observations = new ArrayList<CandidateStore.CandidateObservation>();
        Instant occurredAt = Instant.now();

        for (var chain : miningResult.frequentErrorChains()) {
            String chainKey = chain.chain().stream().map(ErrorClass::name).reduce((a, b) -> a + "->" + b).orElse("unknown");
            observations.add(new CandidateStore.CandidateObservation(
                    MemoryEntry.Category.ANTI_PATTERN,
                    CandidateKind.ANTI_PATTERN,
                    "Recurring error chain across sessions: " + chainKey,
                    "general",
                    List.of("cross-session", "error-chain", "maintenance"),
                    chain.confidence(),
                    0,
                    Math.max(1, chain.support()),
                    "maintenance:" + trigger + ":error-chain:" + chainKey.toLowerCase(Locale.ROOT),
                    occurredAt,
                    true,
                    false,
                    "Cross-session error chain recurred in " + chain.support() + " sessions.",
                    null
            ));
        }

        for (var workflow : miningResult.stableWorkflows()) {
            observations.add(new CandidateStore.CandidateObservation(
                    MemoryEntry.Category.WORKFLOW,
                    CandidateKind.WORKFLOW,
                    workflow.description(),
                    "general",
                    List.of("cross-session", "workflow", "maintenance"),
                    workflow.confidence(),
                    Math.max(1, workflow.support()),
                    0,
                    "maintenance:" + trigger + ":workflow:" + workflow.signature(),
                    occurredAt
            ));
        }

        for (var strategy : miningResult.convergingStrategies()) {
            observations.add(new CandidateStore.CandidateObservation(
                    MemoryEntry.Category.SUCCESSFUL_STRATEGY,
                    CandidateKind.SKILL_SEED,
                    strategy.description(),
                    "general",
                    List.of("cross-session", "converging-strategy", "maintenance"),
                    strategy.confidence(),
                    Math.max(1, strategy.support()),
                    0,
                    "maintenance:" + trigger + ":strategy:" + strategy.signature(),
                    occurredAt
            ));
        }

        for (var degradation : miningResult.degradationSignals()) {
            observations.add(new CandidateStore.CandidateObservation(
                    MemoryEntry.Category.FAILURE_SIGNAL,
                    CandidateKind.ERROR_RECOVERY,
                    "Tool '" + degradation.toolName() + "' is degrading across recent sessions.",
                    degradation.toolName(),
                    List.of("cross-session", "degradation", "maintenance", degradation.toolName()),
                    degradation.confidence(),
                    0,
                    Math.max(1, degradation.support()),
                    "maintenance:" + trigger + ":degradation:" + degradation.toolName(),
                    occurredAt,
                    true,
                    false,
                    "Error rate rose from " + degradation.earlierErrorRate() + " to " + degradation.laterErrorRate(),
                    null
            ));
        }

        for (var trend : trends) {
            var category = switch (trend.direction()) {
                case RISING -> trend.metric().endsWith(".errorRate") || trend.metric().endsWith(".frequency")
                        ? MemoryEntry.Category.ANTI_PATTERN
                        : MemoryEntry.Category.FAILURE_SIGNAL;
                case FALLING -> MemoryEntry.Category.SUCCESSFUL_STRATEGY;
                case STABLE -> null;
            };
            if (category == null) {
                continue;
            }
            var kind = switch (category) {
                case ANTI_PATTERN -> CandidateKind.ANTI_PATTERN;
                case FAILURE_SIGNAL -> CandidateKind.ERROR_RECOVERY;
                case SUCCESSFUL_STRATEGY -> CandidateKind.SKILL_SEED;
                default -> CandidateKind.WORKFLOW;
            };
            String toolTag = metricToolTag(trend.metric());
            observations.add(new CandidateStore.CandidateObservation(
                    category,
                    kind,
                    trend.description(),
                    toolTag,
                    List.of("trend", "maintenance", trend.direction().name().toLowerCase(Locale.ROOT)),
                    trendConfidence(trend),
                    trend.direction() == TrendDetector.TrendDirection.FALLING ? 1 : 0,
                    trend.direction() == TrendDetector.TrendDirection.FALLING ? 0 : 1,
                    "maintenance:" + trigger + ":trend:" + trend.metric(),
                    occurredAt
            ));
        }

        return List.copyOf(observations);
    }

    private static double trendConfidence(TrendDetector.Trend trend) {
        double magnitude = Math.min(100.0, Math.abs(trend.magnitude()));
        return Math.min(0.95, 0.55 + (magnitude / 100.0) * 0.35);
    }

    private static String metricToolTag(String metric) {
        if (metric == null || metric.isBlank() || metric.startsWith("overall.")) {
            return "general";
        }
        int dot = metric.indexOf('.');
        if (dot <= 0) {
            return "general";
        }
        return metric.substring(0, dot).toLowerCase(Locale.ROOT);
    }

    public record BridgeResult(int upserts, int transitions, int promoted) {}
}
