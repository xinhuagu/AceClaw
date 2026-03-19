package dev.aceclaw.daemon;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.aceclaw.core.stats.BenchmarkScorecard;

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
 * <p>Usage: {@code java BenchmarkScorecardCli --replay-report <path> [--runtime-metrics <path>]}
 */
public final class BenchmarkScorecardCli {

    public static void main(String[] args) throws Exception {
        String replayReportPath = null;
        String runtimeMetricsPath = null;
        String outputPath = null;

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--replay-report" -> replayReportPath = args[++i];
                case "--runtime-metrics" -> runtimeMetricsPath = args[++i];
                case "--output" -> outputPath = args[++i];
            }
        }

        if (replayReportPath == null) {
            System.err.println("Usage: BenchmarkScorecardCli --replay-report <path> [--runtime-metrics <path>] [--output <path>]");
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

        // Parse runtime metrics if available
        if (runtimeMetricsPath != null) {
            Path runtimePath = Path.of(runtimeMetricsPath);
            if (Files.isRegularFile(runtimePath)) {
                try {
                    JsonNode runtime = mapper.readTree(runtimePath.toFile());
                    JsonNode metrics = runtime.path("metrics");
                    extractMetric(metrics, "first_try_success_rate", replayDeltas, sampleSizes);
                    // Map to delta name expected by scorecard
                    Double ftsr = replayDeltas.remove("first_try_success_rate");
                    Integer ftsrSize = sampleSizes.remove("first_try_success_rate");
                    if (ftsr != null) {
                        replayDeltas.put("first_try_success_rate_delta", ftsr);
                        sampleSizes.put("first_try_success_rate_delta",
                                ftsrSize != null ? ftsrSize : 0);
                    }
                } catch (Exception e) {
                    System.err.println("Warning: failed to parse runtime metrics: " + e.getMessage());
                    // Non-fatal — scorecard can evaluate without runtime metrics
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
