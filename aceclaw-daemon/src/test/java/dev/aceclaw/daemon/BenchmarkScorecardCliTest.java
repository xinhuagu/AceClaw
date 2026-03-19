package dev.aceclaw.daemon;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class BenchmarkScorecardCliTest {

    @TempDir
    Path tempDir;

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void parsesReplayReportAndProducesScorecardJson() throws Exception {
        Path replayReport = writeReplayReport(tempDir, 0.05, 50, 200, 0.10);
        Path output = tempDir.resolve("scorecard.json");

        int exitCode = runCli("--replay-report", replayReport.toString(),
                "--output", output.toString());

        assertThat(output).exists();
        JsonNode scorecard = mapper.readTree(output.toFile());
        assertThat(scorecard.has("pass")).isTrue();
        assertThat(scorecard.has("metrics")).isTrue();
        assertThat(scorecard.get("metrics").isArray()).isTrue();
        assertThat(scorecard.get("metrics").size()).isEqualTo(8);
    }

    @Test
    void extractsPromotionPrecisionAndFalseLearningRate() throws Exception {
        // Report with promotion_precision and false_learning_rate
        Path replayReport = tempDir.resolve("replay.json");
        Files.writeString(replayReport, """
                {
                  "metrics": {
                    "replay_success_rate_delta": {"value": 0.05, "status": "measured", "sample_size": 20},
                    "promotion_precision": {"value": 0.85, "status": "measured", "sample_size": 15},
                    "false_learning_rate": {"value": 0.08, "status": "measured", "sample_size": 15},
                    "rollback_rate": {"value": 0.10, "status": "measured", "sample_size": 15}
                  }
                }
                """);
        Path output = tempDir.resolve("scorecard.json");

        runCli("--replay-report", replayReport.toString(), "--output", output.toString());

        JsonNode scorecard = mapper.readTree(output.toFile());
        // Safety metrics should be present and evaluated (not INSUFFICIENT_DATA)
        boolean foundPrecision = false;
        boolean foundFalseRate = false;
        for (JsonNode m : scorecard.get("metrics")) {
            if ("promotion_precision".equals(m.get("name").asText())) {
                foundPrecision = true;
                assertThat(m.get("status").asText()).isEqualTo("PASS");
                assertThat(m.get("value").asDouble()).isEqualTo(0.85);
            }
            if ("false_learning_rate".equals(m.get("name").asText())) {
                foundFalseRate = true;
                assertThat(m.get("status").asText()).isEqualTo("PASS");
            }
        }
        assertThat(foundPrecision).isTrue();
        assertThat(foundFalseRate).isTrue();
    }

    @Test
    void pendingMetricsShowInsufficientData() throws Exception {
        Path replayReport = tempDir.resolve("replay.json");
        Files.writeString(replayReport, """
                {
                  "metrics": {
                    "promotion_precision": {"value": null, "status": "pending_instrumentation"},
                    "false_learning_rate": {"value": null, "status": "pending_instrumentation"}
                  }
                }
                """);
        Path output = tempDir.resolve("scorecard.json");

        runCli("--replay-report", replayReport.toString(), "--output", output.toString());

        JsonNode scorecard = mapper.readTree(output.toFile());
        for (JsonNode m : scorecard.get("metrics")) {
            if ("promotion_precision".equals(m.get("name").asText())) {
                assertThat(m.get("status").asText()).isEqualTo("INSUFFICIENT_DATA");
            }
        }
    }

    @Test
    void missingReplayReportProducesAllInsufficientData() throws Exception {
        Path output = tempDir.resolve("scorecard.json");

        runCli("--replay-report", tempDir.resolve("nonexistent.json").toString(),
                "--output", output.toString());

        JsonNode scorecard = mapper.readTree(output.toFile());
        assertThat(scorecard.get("pass").asBoolean()).isTrue(); // insufficient data = no failures
        for (JsonNode m : scorecard.get("metrics")) {
            assertThat(m.get("status").asText()).isEqualTo("INSUFFICIENT_DATA");
        }
    }

    private int runCli(String... args) throws Exception {
        // Run in-process to avoid spawning JVM
        // Capture System.exit by using SecurityManager trick or just call main directly
        // Since main calls System.exit, we run it in a thread and catch
        var exitCode = new int[]{-1};
        var thread = new Thread(() -> {
            try {
                BenchmarkScorecardCli.main(args);
            } catch (Exception e) {
                // System.exit throws SecurityException in some test harnesses
            }
        });
        thread.start();
        thread.join(10_000);
        // The process won't actually exit in test, but the output file will be written
        return 0; // We verify via output file existence
    }

    private Path writeReplayReport(Path dir, double successDelta, double tokenDelta,
                                    double latencyDelta, double rollbackRate) throws Exception {
        Path report = dir.resolve("replay-latest.json");
        Files.writeString(report, """
                {
                  "metrics": {
                    "replay_success_rate_delta": {"value": %s, "status": "measured", "sample_size": 20},
                    "replay_token_delta": {"value": %s, "status": "measured", "sample_size": 20},
                    "replay_latency_delta_ms": {"value": %s, "status": "measured", "sample_size": 20},
                    "replay_failure_distribution_delta": {"value": 0.05, "status": "measured", "sample_size": 20},
                    "promotion_precision": {"value": 0.90, "status": "measured", "sample_size": 15},
                    "false_learning_rate": {"value": 0.05, "status": "measured", "sample_size": 15},
                    "rollback_rate": {"value": %s, "status": "measured", "sample_size": 15}
                  }
                }
                """.formatted(successDelta, tokenDelta, latencyDelta, rollbackRate));
        return report;
    }
}
