package dev.aceclaw.daemon;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.aceclaw.core.stats.BenchmarkScorecard;
import dev.aceclaw.core.stats.ReplayBenchmarkValidator;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * CLI entry point that reads replay report and runtime metrics,
 * evaluates the {@link BenchmarkScorecard}, and prints the verdict.
 *
 * <p>Exit code: 0 = pass, 1 = fail, 2 = error.
 *
 * <p>Usage: {@code java BenchmarkScorecardCli --replay-report <path>
 *   [--runtime-metrics <path>] [--output <path>] [--replay-prompts <path>]}
 *
 * <p>When {@code --replay-prompts} is provided, the CLI reads the prompts file,
 * computes the actual minimum per-category case count, and uses that as each
 * metric's effective sample_size (instead of the total case count from the
 * replay report). This enforces the two-layer threshold model: a suite may
 * pass structural validation (3 = can run) but still report {@code INSUFFICIENT_DATA}
 * in the scorecard when per-category coverage is below statistical significance
 * (10 = can trust).
 */
public final class BenchmarkScorecardCli {

    public static void main(String[] args) throws Exception {
        String replayReportPath = null;
        String runtimeMetricsPath = null;
        String outputPath = null;
        String replayPromptsPath = null;

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--replay-report" -> replayReportPath = args[++i];
                case "--runtime-metrics" -> runtimeMetricsPath = args[++i];
                case "--output" -> outputPath = args[++i];
                case "--replay-prompts" -> replayPromptsPath = args[++i];
            }
        }

        if (replayReportPath == null) {
            System.err.println("Usage: BenchmarkScorecardCli --replay-report <path> [--runtime-metrics <path>] [--output <path>] [--replay-prompts <path>]");
            System.exit(2);
            return;
        }

        var mapper = new ObjectMapper();
        var replayDeltas = new LinkedHashMap<String, Double>();
        var sampleSizes = new LinkedHashMap<String, Integer>();
        var lifecycleRates = new LinkedHashMap<String, Double>();

        // Parse replay report metrics
        Path replayPath = Path.of(replayReportPath);
        if (Files.isRegularFile(replayPath)) {
            try {
                JsonNode report = mapper.readTree(replayPath.toFile());
                JsonNode metrics = report.path("metrics");
                extractMetric(metrics, "replay_success_rate_delta", replayDeltas, sampleSizes);
                extractMetric(metrics, "replay_token_delta", replayDeltas, sampleSizes);
                extractMetric(metrics, "replay_latency_delta_ms", replayDeltas, sampleSizes);
                extractMetric(metrics, "replay_failure_distribution_delta", replayDeltas, sampleSizes);

                // Lifecycle metrics — extract keys that match BenchmarkScorecard expectations
                extractMetric(metrics, "promotion_precision", lifecycleRates, sampleSizes);
                extractMetric(metrics, "false_learning_rate", lifecycleRates, sampleSizes);
                extractMetric(metrics, "rollback_rate", lifecycleRates, sampleSizes);
            } catch (Exception e) {
                System.err.println("Failed to parse replay report: " + e.getMessage());
                System.exit(2);
                return;
            }
        }

        // Note: first_try_success_rate_delta and retry_count_per_task_delta require
        // true A/B replay data (off vs on), not runtime absolute values. They stay
        // pending_instrumentation until replay cases track per-case retry counts.
        // Runtime metrics (runtime-latest.json) provide absolute rates which cannot
        // be substituted for deltas without misrepresenting the scorecard contract.

        // When prompts file is available, compute actual min per-category count
        // and use it as effective sample_size. This ensures scorecard reports
        // INSUFFICIENT_DATA when per-category coverage is below significance (10),
        // even when total case count is above 10.
        if (replayPromptsPath != null) {
            Path promptsPath = Path.of(replayPromptsPath);
            if (Files.isRegularFile(promptsPath)) {
                int effectiveSampleSize = computeMinPerCategory(mapper, promptsPath);
                if (effectiveSampleSize > 0) {
                    int cap = effectiveSampleSize;
                    for (var key : sampleSizes.keySet()) {
                        sampleSizes.computeIfPresent(key, (_, v) -> Math.min(v, cap));
                    }
                }
            }
        }

        // Evaluate scorecard
        var scorecard = BenchmarkScorecard.evaluate(replayDeltas, sampleSizes, lifecycleRates);

        // Print summary
        System.out.println(scorecard.toSummary());

        // Write JSON output if requested
        if (outputPath != null) {
            Path out = Path.of(outputPath);
            if (out.getParent() != null) Files.createDirectories(out.getParent());
            var root = mapper.createObjectNode();
            root.put("pass", scorecard.pass());
            var metricsArray = root.putArray("metrics");
            for (var m : scorecard.metrics()) {
                var node = mapper.createObjectNode();
                node.put("name", m.name());
                if (Double.isNaN(m.value())) {
                    node.putNull("value");
                } else {
                    node.put("value", m.value());
                }
                node.put("threshold", m.threshold());
                node.put("direction", m.direction());
                node.put("category", m.category().name());
                node.put("status", m.status().name());
                node.put("sample_size", m.sampleSize());
                node.put("detail", m.detail());
                metricsArray.add(node);
            }
            var failuresArray = root.putArray("failures");
            scorecard.failures().forEach(failuresArray::add);
            Files.writeString(out, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(root) + "\n");
        }

        System.exit(scorecard.pass() ? 0 : 1);
    }

    /**
     * Reads the replay prompts file and returns the minimum case count
     * across all required benchmark categories.
     */
    private static int computeMinPerCategory(ObjectMapper mapper, Path promptsPath) {
        try {
            JsonNode root = mapper.readTree(promptsPath.toFile());
            JsonNode cases = root.path("cases");
            if (!cases.isArray() || cases.isEmpty()) return 0;

            var counts = new LinkedHashMap<String, Integer>();
            for (JsonNode c : cases) {
                String cat = c.path("category").asText("").trim().toLowerCase();
                if (!cat.isEmpty()) {
                    counts.merge(cat, 1, Integer::sum);
                }
            }

            int min = Integer.MAX_VALUE;
            for (String required : ReplayBenchmarkValidator.REQUIRED_CATEGORIES) {
                int count = counts.getOrDefault(required, 0);
                if (count < min) min = count;
            }
            return min == Integer.MAX_VALUE ? 0 : min;
        } catch (Exception e) {
            System.err.println("WARNING: Failed to read replay prompts for per-category count: " + e.getMessage());
            return 0;
        }
    }

    private static void extractMetric(JsonNode metrics, String name,
                                       Map<String, Double> values, Map<String, Integer> sizes) {
        JsonNode m = metrics.path(name);
        if (m.isMissingNode()) return;
        String status = m.path("status").asText("");
        if (!"measured".equals(status)) return;
        JsonNode valueNode = m.get("value");
        if (valueNode == null || valueNode.isNull()) return;
        values.put(name, valueNode.asDouble());
        sizes.put(name, m.path("sample_size").asInt(0));
    }

}
