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
    void recordLlmRequests_bucketsByProviderAndModel() throws Exception {
        // Per-(provider, model) partitioning: a mid-session /model switch must not cross-
        // contaminate the per-model baseline. Two recordings against different buckets
        // produce two entries in llm_requests_by_model, each carrying its own total +
        // by_source breakdown.
        var exporter = new RuntimeMetricsExporter();

        exporter.recordLlmRequests(dev.aceclaw.core.llm.RequestAttribution.builder()
                .record(dev.aceclaw.core.llm.RequestSource.MAIN_TURN, 2)
                .record(dev.aceclaw.core.llm.RequestSource.PLANNER, 1)
                .build(), "copilot", "claude-opus-4.6");
        exporter.recordLlmRequests(dev.aceclaw.core.llm.RequestAttribution.builder()
                .record(dev.aceclaw.core.llm.RequestSource.MAIN_TURN)
                .build(), "copilot", "claude-sonnet-4.5");
        // Null / empty attribution is ignored on every path.
        exporter.recordLlmRequests(null, "copilot", "claude-opus-4.6");
        exporter.recordLlmRequests(dev.aceclaw.core.llm.RequestAttribution.empty(),
                "copilot", "claude-opus-4.6");

        exporter.export(tempDir, null);

        Path output = tempDir.resolve(".aceclaw/metrics/continuous-learning/runtime-latest.json");
        JsonNode root = new ObjectMapper().readTree(output.toFile());
        assertThat(root.get("rate_card_date").asText()).isEqualTo(CopilotRequestMultipliers.RATE_CARD_DATE);

        JsonNode byModel = root.get("llm_requests_by_model");
        assertThat(byModel.isArray()).isTrue();
        assertThat(byModel.size()).isEqualTo(2);

        JsonNode opus = byModel.get(0);
        assertThat(opus.get("provider").asText()).isEqualTo("copilot");
        assertThat(opus.get("model").asText()).isEqualTo("claude-opus-4.6");
        assertThat(opus.get("multiplier").asDouble()).isEqualTo(3.0);
        assertThat(opus.get("total").asLong()).isEqualTo(3);
        assertThat(opus.get("by_source").get("main_turn").asLong()).isEqualTo(2);
        assertThat(opus.get("by_source").get("planner").asLong()).isEqualTo(1);

        JsonNode sonnet = byModel.get(1);
        assertThat(sonnet.get("model").asText()).isEqualTo("claude-sonnet-4.5");
        assertThat(sonnet.get("multiplier").asDouble()).isEqualTo(1.0);
        assertThat(sonnet.get("total").asLong()).isEqualTo(1);

        // Grand total is the sum across buckets.
        assertThat(root.get("metrics").get("llm_requests_total").get("value").asLong())
                .isEqualTo(4);
    }

    @Test
    void recordLlmRequests_preservesHistoryAcrossModelSwitch() throws Exception {
        // The bug the reviewer called out: /model mid-session used to relabel prior history
        // as the new model. Now an Opus-then-Sonnet sequence keeps both buckets intact and
        // no count is misattributed.
        var exporter = new RuntimeMetricsExporter();
        exporter.recordLlmRequests(dev.aceclaw.core.llm.RequestAttribution.builder()
                .record(dev.aceclaw.core.llm.RequestSource.MAIN_TURN, 10).build(),
                "copilot", "claude-opus-4.6");
        exporter.recordLlmRequests(dev.aceclaw.core.llm.RequestAttribution.builder()
                .record(dev.aceclaw.core.llm.RequestSource.MAIN_TURN, 5).build(),
                "copilot", "claude-sonnet-4.5");

        exporter.export(tempDir, null);
        JsonNode root = new ObjectMapper().readTree(
                tempDir.resolve(".aceclaw/metrics/continuous-learning/runtime-latest.json").toFile());

        JsonNode byModel = root.get("llm_requests_by_model");
        // Opus bucket kept its 10, Sonnet bucket has 5. No cross-contamination.
        assertThat(byModel.get(0).get("model").asText()).isEqualTo("claude-opus-4.6");
        assertThat(byModel.get(0).get("total").asLong()).isEqualTo(10);
        assertThat(byModel.get(1).get("model").asText()).isEqualTo("claude-sonnet-4.5");
        assertThat(byModel.get(1).get("total").asLong()).isEqualTo(5);
    }

    @Test
    void recordLlmRequests_omitsMultiplierForNonCopilotProvider() throws Exception {
        // Token-priced providers don't carry a request multiplier — the field is absent
        // from the bucket, and analysts can tell "not applicable" from "1.0".
        var exporter = new RuntimeMetricsExporter();
        exporter.recordLlmRequests(dev.aceclaw.core.llm.RequestAttribution.builder()
                .record(dev.aceclaw.core.llm.RequestSource.MAIN_TURN).build(),
                "anthropic", "claude-opus-4-6");

        exporter.export(tempDir, null);
        JsonNode root = new ObjectMapper().readTree(
                tempDir.resolve(".aceclaw/metrics/continuous-learning/runtime-latest.json").toFile());

        JsonNode bucket = root.get("llm_requests_by_model").get(0);
        assertThat(bucket.get("provider").asText()).isEqualTo("anthropic");
        assertThat(bucket.has("multiplier")).isFalse();
    }

    @Test
    void export_withNoLlmRequests_emitsEmptyBuckets() throws Exception {
        // Fresh baseline: no LLM requests at all. Top-level array is empty; scalar total
        // metric is pending_instrumentation.
        var exporter = new RuntimeMetricsExporter();
        exporter.recordTurn();
        exporter.export(tempDir, null);

        JsonNode root = new ObjectMapper().readTree(
                tempDir.resolve(".aceclaw/metrics/continuous-learning/runtime-latest.json").toFile());

        assertThat(root.get("llm_requests_by_model").isArray()).isTrue();
        assertThat(root.get("llm_requests_by_model").size()).isZero();
        assertThat(root.get("metrics").get("llm_requests_total").get("status").asText())
                .isEqualTo("pending_instrumentation");
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
