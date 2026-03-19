package dev.aceclaw.core.stats;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Compact benchmark scorecard for self-learning A/B evaluation.
 *
 * <p>Produces a machine-readable scorecard from replay results that CI
 * and local replay runs can consume. Each metric includes value, threshold,
 * pass/fail status, and category (effectiveness, efficiency, safety).
 */
public final class BenchmarkScorecard {

    private BenchmarkScorecard() {}

    public enum Category {
        EFFECTIVENESS, EFFICIENCY, SAFETY
    }

    public enum Status {
        PASS, FAIL, INSUFFICIENT_DATA
    }

    /**
     * A single scored metric with threshold evaluation.
     */
    public record ScoredMetric(
            String name,
            double value,
            double threshold,
            String direction,  // "higher_is_better" or "lower_is_better"
            Category category,
            Status status,
            int sampleSize,
            String detail
    ) {}

    /**
     * The complete scorecard with overall verdict.
     */
    public record Scorecard(
            boolean pass,
            List<ScoredMetric> metrics,
            List<String> failures
    ) {
        public Scorecard {
            metrics = metrics != null ? List.copyOf(metrics) : List.of();
            failures = failures != null ? List.copyOf(failures) : List.of();
        }

        /** Returns metrics grouped by category. */
        public Map<Category, List<ScoredMetric>> byCategory() {
            var grouped = new LinkedHashMap<Category, List<ScoredMetric>>();
            for (var m : metrics) {
                grouped.computeIfAbsent(m.category(), _ -> new ArrayList<>()).add(m);
            }
            return grouped;
        }

        /** Returns a compact summary string for CI output. */
        public String toSummary() {
            var sb = new StringBuilder();
            sb.append("=== Self-Learning Benchmark Scorecard ===\n");
            sb.append("Verdict: ").append(pass ? "PASS" : "FAIL").append('\n');
            sb.append('\n');

            for (var entry : byCategory().entrySet()) {
                sb.append("--- ").append(entry.getKey()).append(" ---\n");
                for (var m : entry.getValue()) {
                    String icon = switch (m.status()) {
                        case PASS -> "[OK]";
                        case FAIL -> "[FAIL]";
                        case INSUFFICIENT_DATA -> "[??]";
                    };
                    sb.append("  ").append(icon).append(' ').append(m.name())
                            .append(": ").append(formatValue(m.value()))
                            .append(" (threshold: ").append(formatValue(m.threshold()))
                            .append(", n=").append(m.sampleSize()).append(")\n");
                }
            }

            if (!failures.isEmpty()) {
                sb.append("\nFailures:\n");
                for (var f : failures) {
                    sb.append("  - ").append(f).append('\n');
                }
            }
            return sb.toString();
        }
    }

    /**
     * Minimum sample size for a metric to be considered measured.
     */
    public static final int MIN_SAMPLE_SIZE = 10;

    /**
     * Builds a scorecard from replay deltas and lifecycle metrics.
     *
     * @param replayDeltas   map of metric name to measured delta value
     * @param sampleSizes    map of metric name to sample size
     * @param lifecycleRates map of lifecycle metric name to measured rate
     * @return evaluated scorecard
     */
    public static Scorecard evaluate(Map<String, Double> replayDeltas,
                                      Map<String, Integer> sampleSizes,
                                      Map<String, Double> lifecycleRates) {
        Objects.requireNonNull(replayDeltas, "replayDeltas");
        var metrics = new ArrayList<ScoredMetric>();
        var failures = new ArrayList<String>();
        var safeSizes = sampleSizes != null ? sampleSizes : Map.<String, Integer>of();
        var safeRates = lifecycleRates != null ? lifecycleRates : Map.<String, Double>of();

        // Effectiveness metrics (higher delta = better)
        addMetric(metrics, failures, "replay_success_rate_delta",
                replayDeltas.getOrDefault("replay_success_rate_delta", Double.NaN),
                0.00, "higher_is_better", Category.EFFECTIVENESS,
                safeSizes.getOrDefault("replay_success_rate_delta", 0));

        addMetric(metrics, failures, "first_try_success_rate_delta",
                replayDeltas.getOrDefault("first_try_success_rate_delta", Double.NaN),
                0.00, "higher_is_better", Category.EFFECTIVENESS,
                safeSizes.getOrDefault("first_try_success_rate_delta", 0));

        addMetric(metrics, failures, "retry_count_per_task_delta",
                replayDeltas.getOrDefault("retry_count_per_task_delta", Double.NaN),
                0.00, "lower_is_better", Category.EFFECTIVENESS,
                safeSizes.getOrDefault("retry_count_per_task_delta", 0));

        // Efficiency metrics (lower delta = better for tokens/latency)
        addMetric(metrics, failures, "replay_token_delta",
                replayDeltas.getOrDefault("replay_token_delta", Double.NaN),
                0.10, "lower_is_better", Category.EFFICIENCY,
                safeSizes.getOrDefault("replay_token_delta", 0));

        addMetric(metrics, failures, "replay_latency_delta_ms",
                replayDeltas.getOrDefault("replay_latency_delta_ms", Double.NaN),
                500.0, "lower_is_better", Category.EFFICIENCY,
                safeSizes.getOrDefault("replay_latency_delta_ms", 0));

        // Safety metrics (from lifecycle)
        addMetric(metrics, failures, "promotion_precision",
                safeRates.getOrDefault("promotion_precision", Double.NaN),
                0.80, "higher_is_better", Category.SAFETY,
                safeSizes.getOrDefault("promotion_precision", 0));

        addMetric(metrics, failures, "false_learning_rate",
                safeRates.getOrDefault("false_learning_rate", Double.NaN),
                0.10, "lower_is_better", Category.SAFETY,
                safeSizes.getOrDefault("false_learning_rate", 0));

        addMetric(metrics, failures, "rollback_rate",
                safeRates.getOrDefault("rollback_rate", Double.NaN),
                0.20, "lower_is_better", Category.SAFETY,
                safeSizes.getOrDefault("rollback_rate", 0));

        boolean pass = failures.isEmpty();
        return new Scorecard(pass, metrics, failures);
    }

    private static void addMetric(List<ScoredMetric> metrics, List<String> failures,
                                   String name, double value, double threshold,
                                   String direction, Category category, int sampleSize) {
        Status status;
        String detail;

        if (Double.isNaN(value)) {
            status = Status.INSUFFICIENT_DATA;
            detail = "no data available";
        } else if (sampleSize < MIN_SAMPLE_SIZE) {
            status = Status.INSUFFICIENT_DATA;
            detail = "sample size %d < minimum %d".formatted(sampleSize, MIN_SAMPLE_SIZE);
        } else if ("higher_is_better".equals(direction)) {
            if (value >= threshold) {
                status = Status.PASS;
                detail = "meets threshold";
            } else {
                status = Status.FAIL;
                detail = "%s=%.4f below threshold %.4f".formatted(name, value, threshold);
                failures.add(detail);
            }
        } else {
            // lower_is_better
            if (value <= threshold) {
                status = Status.PASS;
                detail = "meets threshold";
            } else {
                status = Status.FAIL;
                detail = "%s=%.4f exceeds threshold %.4f".formatted(name, value, threshold);
                failures.add(detail);
            }
        }

        metrics.add(new ScoredMetric(name, value, threshold, direction, category, status, sampleSize, detail));
    }

    private static String formatValue(double v) {
        if (Double.isNaN(v)) return "N/A";
        if (v == (long) v) return String.valueOf((long) v);
        return "%.4f".formatted(v);
    }
}
