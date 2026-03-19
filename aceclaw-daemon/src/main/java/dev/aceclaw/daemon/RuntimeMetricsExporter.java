package dev.aceclaw.daemon;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.aceclaw.core.agent.ToolMetrics;
import dev.aceclaw.core.agent.ToolMetricsCollector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Exports structured runtime outcome counters to a persistent JSON file
 * for consumption by the continuous-learning baseline collection script.
 *
 * <p>Output: {@code .aceclaw/metrics/continuous-learning/runtime-latest.json}
 *
 * <p>Counters are accumulated across daemon lifetime (reset on restart).
 * Each metric includes {@code value}, {@code sample_size}, and {@code status}.
 */
public final class RuntimeMetricsExporter {

    private static final Logger log = LoggerFactory.getLogger(RuntimeMetricsExporter.class);
    private static final String METRICS_DIR = ".aceclaw/metrics/continuous-learning";
    private static final String RUNTIME_FILE = "runtime-latest.json";

    private final ObjectMapper mapper;

    // Task-level counters
    private final AtomicInteger taskTotal = new AtomicInteger();
    private final AtomicInteger taskSuccess = new AtomicInteger();
    private final AtomicInteger taskFirstTrySuccess = new AtomicInteger();
    private final AtomicLong retryCountTotal = new AtomicLong();

    // Permission counters
    private final AtomicInteger permissionRequests = new AtomicInteger();
    private final AtomicInteger permissionBlocks = new AtomicInteger();

    // Timeout counters
    private final AtomicInteger turnTotal = new AtomicInteger();
    private final AtomicInteger timeoutCount = new AtomicInteger();

    public RuntimeMetricsExporter() {
        this.mapper = new ObjectMapper();
    }

    /**
     * Records the outcome of a task (agent prompt request).
     *
     * @param success       whether the task completed successfully
     * @param firstTry      whether it succeeded on the first attempt (no replan/retry)
     * @param retryCount    number of retries or replan attempts during this task
     */
    public void recordTaskOutcome(boolean success, boolean firstTry, int retryCount) {
        taskTotal.incrementAndGet();
        if (success) {
            taskSuccess.incrementAndGet();
            if (firstTry) {
                taskFirstTrySuccess.incrementAndGet();
            }
        }
        retryCountTotal.addAndGet(Math.max(0, retryCount));
    }

    /**
     * Records a permission decision.
     *
     * @param blocked true if the permission was denied/blocked
     */
    public void recordPermissionDecision(boolean blocked) {
        permissionRequests.incrementAndGet();
        if (blocked) {
            permissionBlocks.incrementAndGet();
        }
    }

    /**
     * Records a timeout/budget exhaustion event.
     */
    public void recordTimeout() {
        timeoutCount.incrementAndGet();
    }

    /**
     * Records that a turn was executed.
     */
    public void recordTurn() {
        turnTotal.incrementAndGet();
    }

    /**
     * Exports all accumulated metrics to the runtime-latest.json file.
     *
     * @param projectRoot the project root directory
     * @param toolMetrics the session tool metrics collector (may be null)
     */
    public void export(Path projectRoot, ToolMetricsCollector toolMetrics) {
        try {
            ObjectNode root = mapper.createObjectNode();
            root.put("exported_at", Instant.now().toString());

            ObjectNode metrics = root.putObject("metrics");

            // Task success rate
            int tasks = taskTotal.get();
            int successes = taskSuccess.get();
            addMetric(metrics, "task_success_rate",
                    tasks > 0 ? (double) successes / tasks : Double.NaN, tasks);

            // First try success rate
            addMetric(metrics, "first_try_success_rate",
                    tasks > 0 ? (double) taskFirstTrySuccess.get() / tasks : Double.NaN, tasks);

            // Retry count per task (average)
            addMetric(metrics, "retry_count_per_task",
                    tasks > 0 ? (double) retryCountTotal.get() / tasks : Double.NaN, tasks);

            // Tool execution metrics (from ToolMetricsCollector)
            if (toolMetrics != null) {
                Map<String, ToolMetrics> allTools = toolMetrics.allMetrics();
                int totalToolInvocations = 0;
                int totalToolSuccess = 0;
                int totalToolErrors = 0;
                for (var tm : allTools.values()) {
                    totalToolInvocations += tm.totalInvocations();
                    totalToolSuccess += tm.successCount();
                    totalToolErrors += tm.errorCount();
                }
                addMetric(metrics, "tool_execution_success_rate",
                        totalToolInvocations > 0 ? (double) totalToolSuccess / totalToolInvocations : Double.NaN,
                        totalToolInvocations);
                addMetric(metrics, "tool_error_rate",
                        totalToolInvocations > 0 ? (double) totalToolErrors / totalToolInvocations : Double.NaN,
                        totalToolInvocations);
            } else {
                addMetric(metrics, "tool_execution_success_rate", Double.NaN, 0);
                addMetric(metrics, "tool_error_rate", Double.NaN, 0);
            }

            // Permission block rate
            int permReqs = permissionRequests.get();
            addMetric(metrics, "permission_block_rate",
                    permReqs > 0 ? (double) permissionBlocks.get() / permReqs : Double.NaN, permReqs);

            // Timeout rate
            int turns = turnTotal.get();
            addMetric(metrics, "timeout_rate",
                    turns > 0 ? (double) timeoutCount.get() / turns : Double.NaN, turns);

            // Write atomically
            Path metricsDir = projectRoot.resolve(METRICS_DIR);
            Files.createDirectories(metricsDir);
            Path target = metricsDir.resolve(RUNTIME_FILE);
            Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
            Files.writeString(tmp, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(root) + "\n",
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);

            log.debug("Exported runtime metrics to {}", target);
        } catch (IOException e) {
            log.warn("Failed to export runtime metrics: {}", e.getMessage());
        }
    }

    private void addMetric(ObjectNode parent, String name, double value, int sampleSize) {
        ObjectNode metric = parent.putObject(name);
        if (Double.isNaN(value) || sampleSize == 0) {
            metric.putNull("value");
            metric.put("sample_size", sampleSize);
            metric.put("status", "pending_instrumentation");
        } else {
            metric.put("value", Math.round(value * 10000.0) / 10000.0); // 4 decimal precision
            metric.put("sample_size", sampleSize);
            metric.put("status", "measured");
        }
    }

    /**
     * Returns the current snapshot of counters for testing.
     */
    public Snapshot snapshot() {
        return new Snapshot(
                taskTotal.get(), taskSuccess.get(), taskFirstTrySuccess.get(),
                retryCountTotal.get(), permissionRequests.get(), permissionBlocks.get(),
                turnTotal.get(), timeoutCount.get());
    }

    public record Snapshot(
            int taskTotal, int taskSuccess, int taskFirstTrySuccess,
            long retryCountTotal, int permissionRequests, int permissionBlocks,
            int turnTotal, int timeoutCount
    ) {}
}
