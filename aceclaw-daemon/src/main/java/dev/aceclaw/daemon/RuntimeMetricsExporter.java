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
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Exports structured runtime outcome counters to a persistent JSON file
 * for consumption by the continuous-learning baseline collection script.
 *
 * <p>Output: {@code .aceclaw/metrics/continuous-learning/runtime-latest.json}
 *
 * <p>Counters are accumulated across daemon lifetime (reset on restart).
 * Each metric includes {@code value}, {@code sample_size}, and {@code status}.
 *
 * <p>Thread-safety: all counter mutations and snapshot reads are protected
 * by a single lock to ensure consistent ratios (e.g., success &lt;= total).
 */
public final class RuntimeMetricsExporter {

    private static final Logger log = LoggerFactory.getLogger(RuntimeMetricsExporter.class);
    private static final String METRICS_DIR = ".aceclaw/metrics/continuous-learning";
    private static final String RUNTIME_FILE = "runtime-latest.json";

    private final ObjectMapper mapper;
    private final ReentrantLock lock = new ReentrantLock();

    // Task-level counters — guarded by lock
    private int taskTotal;
    private int taskSuccess;
    private int taskFirstTrySuccess;
    private long retryCountTotal;

    // Permission counters — guarded by lock
    private int permissionRequests;
    private int permissionBlocks;

    // Timeout counters — guarded by lock
    private int turnTotal;
    private int timeoutCount;

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
        lock.lock();
        try {
            taskTotal++;
            if (success) {
                taskSuccess++;
                if (firstTry) {
                    taskFirstTrySuccess++;
                }
            }
            retryCountTotal += Math.max(0, retryCount);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Records a permission decision.
     *
     * @param blocked true if the permission was denied/blocked
     */
    public void recordPermissionDecision(boolean blocked) {
        lock.lock();
        try {
            permissionRequests++;
            if (blocked) {
                permissionBlocks++;
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * Records a timeout/budget exhaustion event.
     */
    public void recordTimeout() {
        lock.lock();
        try {
            timeoutCount++;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Records that a turn was executed.
     */
    public void recordTurn() {
        lock.lock();
        try {
            turnTotal++;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Exports all accumulated metrics to the runtime-latest.json file.
     *
     * @param projectRoot the project root directory (must not be null)
     * @param toolMetrics the session tool metrics collector (may be null)
     */
    public void export(Path projectRoot, ToolMetricsCollector toolMetrics) {
        Objects.requireNonNull(projectRoot, "projectRoot");
        try {
            // Take a consistent snapshot under lock
            Snapshot snap = snapshot();

            ObjectNode root = mapper.createObjectNode();
            root.put("exported_at", Instant.now().toString());

            ObjectNode metrics = root.putObject("metrics");

            // Task success rate
            addMetric(metrics, "task_success_rate",
                    snap.taskTotal > 0 ? (double) snap.taskSuccess / snap.taskTotal : Double.NaN,
                    snap.taskTotal);

            // First try success rate
            addMetric(metrics, "first_try_success_rate",
                    snap.taskTotal > 0 ? (double) snap.taskFirstTrySuccess / snap.taskTotal : Double.NaN,
                    snap.taskTotal);

            // Retry count per task (average)
            addMetric(metrics, "retry_count_per_task",
                    snap.taskTotal > 0 ? (double) snap.retryCountTotal / snap.taskTotal : Double.NaN,
                    snap.taskTotal);

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
            addMetric(metrics, "permission_block_rate",
                    snap.permissionRequests > 0
                            ? (double) snap.permissionBlocks / snap.permissionRequests : Double.NaN,
                    snap.permissionRequests);

            // Timeout rate
            addMetric(metrics, "timeout_rate",
                    snap.turnTotal > 0 ? (double) snap.timeoutCount / snap.turnTotal : Double.NaN,
                    snap.turnTotal);

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
     * Returns a consistent snapshot of all counters under lock.
     */
    public Snapshot snapshot() {
        lock.lock();
        try {
            return new Snapshot(
                    taskTotal, taskSuccess, taskFirstTrySuccess,
                    retryCountTotal, permissionRequests, permissionBlocks,
                    turnTotal, timeoutCount);
        } finally {
            lock.unlock();
        }
    }

    public record Snapshot(
            int taskTotal, int taskSuccess, int taskFirstTrySuccess,
            long retryCountTotal, int permissionRequests, int permissionBlocks,
            int turnTotal, int timeoutCount
    ) {}
}
