package dev.aceclaw.daemon;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.aceclaw.core.agent.ToolMetricsCollector;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class RuntimeMetricsExporterTest {

    @TempDir
    Path tempDir;

    @Test
    void export_producesValidJson() throws Exception {
        var exporter = new RuntimeMetricsExporter();
        exporter.recordTaskOutcome(true, true, 0);
        exporter.recordTaskOutcome(true, false, 2);
        exporter.recordTaskOutcome(false, false, 1);
        exporter.recordPermissionDecision(false);
        exporter.recordPermissionDecision(true);
        exporter.recordTurn();
        exporter.recordTurn();
        exporter.recordTurn();
        exporter.recordTimeout();

        var toolMetrics = new ToolMetricsCollector();
        toolMetrics.record("bash", true, 100);
        toolMetrics.record("bash", true, 200);
        toolMetrics.record("bash", false, 50);
        toolMetrics.record("read_file", true, 30);

        exporter.export(tempDir, toolMetrics);

        Path output = tempDir.resolve(".aceclaw/metrics/continuous-learning/runtime-latest.json");
        assertThat(output).exists();

        var mapper = new ObjectMapper();
        JsonNode root = mapper.readTree(output.toFile());
        assertThat(root.has("exported_at")).isTrue();

        JsonNode metrics = root.get("metrics");

        // task_success_rate: 2/3
        assertThat(metrics.get("task_success_rate").get("value").asDouble())
                .isEqualTo(0.6667);
        assertThat(metrics.get("task_success_rate").get("sample_size").asInt())
                .isEqualTo(3);
        assertThat(metrics.get("task_success_rate").get("status").asText())
                .isEqualTo("measured");

        // first_try_success_rate: 1/3
        assertThat(metrics.get("first_try_success_rate").get("value").asDouble())
                .isEqualTo(0.3333);

        // retry_count_per_task: 3/3 = 1.0
        assertThat(metrics.get("retry_count_per_task").get("value").asDouble())
                .isEqualTo(1.0);

        // tool_execution_success_rate: 3/4
        assertThat(metrics.get("tool_execution_success_rate").get("value").asDouble())
                .isEqualTo(0.75);

        // tool_error_rate: 1/4
        assertThat(metrics.get("tool_error_rate").get("value").asDouble())
                .isEqualTo(0.25);

        // permission_block_rate: 1/2
        assertThat(metrics.get("permission_block_rate").get("value").asDouble())
                .isEqualTo(0.5);

        // timeout_rate: 1/3
        assertThat(metrics.get("timeout_rate").get("value").asDouble())
                .isEqualTo(0.3333);
    }

    @Test
    void recordLlmRequests_accumulatesTotalAndPerSource() throws Exception {
        // PR C: runtime-latest.json gains absolute-count metrics for LLM requests by source
        // so offline tools can build a Copilot-usage baseline. Each per-source count lands
        // in a metric named llm_requests_<source>; the grand total lands in llm_requests_total.
        var exporter = new RuntimeMetricsExporter();

        // Turn 1: two main-turn + one planner.
        exporter.recordLlmRequests(dev.aceclaw.core.llm.RequestAttribution.builder()
                .record(dev.aceclaw.core.llm.RequestSource.MAIN_TURN, 2)
                .record(dev.aceclaw.core.llm.RequestSource.PLANNER, 1)
                .build());
        // Turn 2: one main-turn + one continuation.
        exporter.recordLlmRequests(dev.aceclaw.core.llm.RequestAttribution.builder()
                .record(dev.aceclaw.core.llm.RequestSource.MAIN_TURN)
                .record(dev.aceclaw.core.llm.RequestSource.CONTINUATION)
                .build());
        // Empty / null are ignored.
        exporter.recordLlmRequests(null);
        exporter.recordLlmRequests(dev.aceclaw.core.llm.RequestAttribution.empty());

        exporter.export(tempDir, null);

        Path output = tempDir.resolve(".aceclaw/metrics/continuous-learning/runtime-latest.json");
        JsonNode metrics = new ObjectMapper().readTree(output.toFile()).get("metrics");

        assertThat(metrics.get("llm_requests_total").get("value").asLong()).isEqualTo(5);
        assertThat(metrics.get("llm_requests_total").get("status").asText()).isEqualTo("measured");
        assertThat(metrics.get("llm_requests_main_turn").get("value").asLong()).isEqualTo(3);
        assertThat(metrics.get("llm_requests_planner").get("value").asLong()).isEqualTo(1);
        assertThat(metrics.get("llm_requests_continuation").get("value").asLong()).isEqualTo(1);
        // Sample size is the grand total so downstream scripts can compute ratios
        // without having to re-sum every per-source metric.
        assertThat(metrics.get("llm_requests_main_turn").get("sample_size").asLong()).isEqualTo(5);
        // Sources that never fired are NOT emitted — keeps the metrics file compact.
        assertThat(metrics.has("llm_requests_replan")).isFalse();
        assertThat(metrics.has("llm_requests_fallback")).isFalse();
    }

    @Test
    void export_withNoLlmRequests_omitsPerSourceMetrics() throws Exception {
        // A daemon lifetime with no LLM requests at all (only task or tool metrics) should
        // emit the total metric with pending_instrumentation status but no per-source
        // clutter — otherwise fresh baselines look noisy.
        var exporter = new RuntimeMetricsExporter();
        exporter.recordTurn();
        exporter.export(tempDir, null);

        Path output = tempDir.resolve(".aceclaw/metrics/continuous-learning/runtime-latest.json");
        JsonNode metrics = new ObjectMapper().readTree(output.toFile()).get("metrics");

        assertThat(metrics.get("llm_requests_total").get("status").asText())
                .isEqualTo("pending_instrumentation");
        assertThat(metrics.has("llm_requests_main_turn")).isFalse();
    }

    @Test
    void export_withNoData_marksPendingInstrumentation() throws Exception {
        var exporter = new RuntimeMetricsExporter();
        exporter.export(tempDir, null);

        Path output = tempDir.resolve(".aceclaw/metrics/continuous-learning/runtime-latest.json");
        assertThat(output).exists();

        var mapper = new ObjectMapper();
        JsonNode metrics = mapper.readTree(output.toFile()).get("metrics");

        assertThat(metrics.get("task_success_rate").get("status").asText())
                .isEqualTo("pending_instrumentation");
        assertThat(metrics.get("task_success_rate").get("value").isNull()).isTrue();
        assertThat(metrics.get("task_success_rate").get("sample_size").asInt()).isEqualTo(0);
    }

    @Test
    void snapshot_returnsCurrentCounters() {
        var exporter = new RuntimeMetricsExporter();
        exporter.recordTaskOutcome(true, true, 0);
        exporter.recordTaskOutcome(false, false, 3);
        exporter.recordPermissionDecision(true);
        exporter.recordTurn();
        exporter.recordTimeout();

        var snap = exporter.snapshot();
        assertThat(snap.taskTotal()).isEqualTo(2);
        assertThat(snap.taskSuccess()).isEqualTo(1);
        assertThat(snap.taskFirstTrySuccess()).isEqualTo(1);
        assertThat(snap.retryCountTotal()).isEqualTo(3);
        assertThat(snap.permissionBlocks()).isEqualTo(1);
        assertThat(snap.turnTotal()).isEqualTo(1);
        assertThat(snap.timeoutCount()).isEqualTo(1);
    }

    @Test
    void concurrentRecording_producesConsistentSnapshot() throws Exception {
        var exporter = new RuntimeMetricsExporter();
        int threads = 8;
        int iterations = 1000;
        var latch = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(threads);

        for (int t = 0; t < threads; t++) {
            executor.submit(() -> {
                try {
                    latch.await();
                    for (int i = 0; i < iterations; i++) {
                        exporter.recordTaskOutcome(true, true, 1);
                        exporter.recordPermissionDecision(false);
                        exporter.recordTurn();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        latch.countDown();
        executor.shutdown();
        assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();

        var snap = exporter.snapshot();
        int expected = threads * iterations;
        // With lock-based synchronization, success must never exceed total
        assertThat(snap.taskTotal()).isEqualTo(expected);
        assertThat(snap.taskSuccess()).isEqualTo(expected);
        assertThat(snap.taskFirstTrySuccess()).isEqualTo(expected);
        assertThat(snap.taskSuccess()).isLessThanOrEqualTo(snap.taskTotal());
        assertThat(snap.permissionRequests()).isEqualTo(expected);
        assertThat(snap.turnTotal()).isEqualTo(expected);
    }

    @Test
    void export_nullProjectRoot_throwsNPE() {
        var exporter = new RuntimeMetricsExporter();
        org.junit.jupiter.api.Assertions.assertThrows(NullPointerException.class,
                () -> exporter.export(null, null));
    }
}
