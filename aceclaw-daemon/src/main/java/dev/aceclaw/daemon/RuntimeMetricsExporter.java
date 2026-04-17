package dev.aceclaw.daemon;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.aceclaw.core.agent.ToolMetrics;
import dev.aceclaw.core.agent.ToolMetricsCollector;
import dev.aceclaw.core.llm.RequestAttribution;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Exports structured runtime outcome counters to a persistent JSON file
 * for consumption by the continuous-learning baseline collection script.
 *
 * <p>Output: {@code .aceclaw/metrics/continuous-learning/runtime-latest.json}
 *
 * <p>Counters are accumulated across daemon lifetime (reset on restart). LLM
 * request counts are additionally partitioned by {@code (provider, model)} so a
 * mid-session {@code /model} switch doesn't misattribute prior history to the new
 * model. Each per-(provider, model) bucket carries its own total + per-source
 * breakdown + Copilot-normalised cost multiplier.
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

    // LLM request counters — guarded by lock. Keyed by (provider, model) so /model
    // switches don't cross-contaminate the per-model baseline. Per-bucket totals +
    // per-source breakdowns are self-consistent within their bucket; a grand total
    // across all buckets is derived at export time for convenience.
    private final LinkedHashMap<ModelKey, ModelCounts> llmRequestsByModel = new LinkedHashMap<>();

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
     * Folds a turn or plan's {@link RequestAttribution} into the cumulative per-source
     * LLM request counters for the given provider + model bucket. Each distinct
     * {@code (provider, model)} observed during a daemon lifetime gets its own bucket,
     * so a mid-session {@code /model} switch doesn't re-label history.
     *
     * <p>Silently accepts {@code null} or empty attribution — callers on older code paths
     * don't have to thread attribution through to get the rest of the runtime metrics.
     *
     * @param attribution the per-source request counts from this turn or plan
     * @param provider    the LLM provider identifier for these requests; may be {@code null}
     *                    (recorded under an {@code unknown} provider bucket)
     * @param model       the model identifier for these requests; may be {@code null}
     *                    (recorded under an {@code unknown} model bucket)
     */
    public void recordLlmRequests(RequestAttribution attribution, String provider, String model) {
        if (attribution == null || attribution.total() == 0) return;
        lock.lock();
        try {
            var key = ModelKey.of(provider, model);
            var bucket = llmRequestsByModel.computeIfAbsent(key, k -> new ModelCounts());
            attribution.bySource().forEach((source, count) -> {
                String sourceKey = source.name().toLowerCase(Locale.ROOT);
                bucket.bySource.merge(sourceKey, (long) count, Long::sum);
                bucket.total += count;
            });
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
            Snapshot snap = snapshot();

            ObjectNode root = mapper.createObjectNode();
            root.put("exported_at", Instant.now().toString());
            root.put("rate_card_date", CopilotRequestMultipliers.RATE_CARD_DATE);

            ObjectNode metrics = root.putObject("metrics");

            addMetric(metrics, "task_success_rate",
                    snap.taskTotal > 0 ? (double) snap.taskSuccess / snap.taskTotal : Double.NaN,
                    snap.taskTotal);
            addMetric(metrics, "first_try_success_rate",
                    snap.taskTotal > 0 ? (double) snap.taskFirstTrySuccess / snap.taskTotal : Double.NaN,
                    snap.taskTotal);
            addMetric(metrics, "retry_count_per_task",
                    snap.taskTotal > 0 ? (double) snap.retryCountTotal / snap.taskTotal : Double.NaN,
                    snap.taskTotal);

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

            addMetric(metrics, "permission_block_rate",
                    snap.permissionRequests > 0
                            ? (double) snap.permissionBlocks / snap.permissionRequests : Double.NaN,
                    snap.permissionRequests);
            addMetric(metrics, "timeout_rate",
                    snap.turnTotal > 0 ? (double) snap.timeoutCount / snap.turnTotal : Double.NaN,
                    snap.turnTotal);

            // Per-(provider, model) breakdown. Each bucket carries its own total + per-source
            // distribution + Copilot multiplier so downstream analysis can compute normalized
            // cost units without re-labeling history after a /model switch.
            long grandTotal = snap.llmRequestsByModel.values().stream()
                    .mapToLong(ModelCounts::total).sum();
            ArrayNode perModel = root.putArray("llm_requests_by_model");
            snap.llmRequestsByModel.forEach((key, counts) -> {
                ObjectNode bucket = perModel.addObject();
                bucket.put("provider", key.provider());
                bucket.put("model", key.model());
                Double multiplier = CopilotRequestMultipliers.forProviderAndModel(
                        key.provider(), key.model());
                if (multiplier != null) {
                    bucket.put("multiplier", multiplier);
                }
                bucket.put("total", counts.total());
                ObjectNode bySource = bucket.putObject("by_source");
                counts.bySource().forEach(bySource::put);
            });
            addRequestCountMetric(metrics, "llm_requests_total", grandTotal, grandTotal);

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

    /**
     * Variant of {@link #addMetric} for absolute counts (not ratios). Emits the raw value
     * as an integer so downstream parsers don't see a trailing {@code .0} from double
     * rounding, and marks the metric measured as long as the sample size is non-zero.
     */
    private void addRequestCountMetric(ObjectNode parent, String name, long value, long sampleSize) {
        ObjectNode metric = parent.putObject(name);
        if (sampleSize <= 0) {
            metric.putNull("value");
            metric.put("sample_size", 0);
            metric.put("status", "pending_instrumentation");
        } else {
            metric.put("value", value);
            metric.put("sample_size", sampleSize);
            metric.put("status", "measured");
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
            var copied = new LinkedHashMap<ModelKey, ModelCounts>();
            llmRequestsByModel.forEach((k, v) -> copied.put(k, v.copy()));
            return new Snapshot(
                    taskTotal, taskSuccess, taskFirstTrySuccess,
                    retryCountTotal, permissionRequests, permissionBlocks,
                    turnTotal, timeoutCount,
                    copied);
        } finally {
            lock.unlock();
        }
    }

    /** Identifies a distinct {@code (provider, model)} pair for bucketing request counts. */
    public record ModelKey(String provider, String model) {
        public ModelKey {
            provider = (provider == null || provider.isBlank()) ? "unknown" : provider;
            model = (model == null || model.isBlank()) ? "unknown" : model;
        }

        static ModelKey of(String provider, String model) {
            return new ModelKey(provider, model);
        }
    }

    /** Mutable per-bucket counters. Copied on snapshot; never leaked outside the lock. */
    static final class ModelCounts {
        long total;
        final LinkedHashMap<String, Long> bySource = new LinkedHashMap<>();

        ModelCounts copy() {
            var c = new ModelCounts();
            c.total = this.total;
            c.bySource.putAll(this.bySource);
            return c;
        }

        long total() { return total; }
        Map<String, Long> bySource() { return Collections.unmodifiableMap(bySource); }
    }

    public record Snapshot(
            int taskTotal, int taskSuccess, int taskFirstTrySuccess,
            long retryCountTotal, int permissionRequests, int permissionBlocks,
            int turnTotal, int timeoutCount,
            Map<ModelKey, ModelCounts> llmRequestsByModel
    ) {
        public Snapshot {
            // Preserve insertion order so JSON output lists models in a stable sequence
            // (Map.copyOf offers no order guarantee; LinkedHashMap via unmodifiableMap does).
            if (llmRequestsByModel == null) {
                llmRequestsByModel = Map.of();
            } else {
                llmRequestsByModel = Collections.unmodifiableMap(
                        new LinkedHashMap<>(llmRequestsByModel));
            }
        }
    }
}
