package dev.aceclaw.core.agent;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * Thread-safe collector of per-tool execution metrics.
 *
 * <p>Designed to be session-scoped: create one per agent session and pass it
 * via {@link AgentLoopConfig}. The agent loop records each tool invocation,
 * and consumers (e.g. the self-improvement engine) can query accumulated stats.
 */
public final class ToolMetricsCollector {

    private final ConcurrentHashMap<String, AtomicToolStats> stats = new ConcurrentHashMap<>();

    /**
     * Records a single tool invocation.
     *
     * @param toolName   the tool's registered name
     * @param success    true if the invocation succeeded, false on error
     * @param durationMs execution time in milliseconds
     */
    public void record(String toolName, boolean success, long durationMs) {
        var toolStats = stats.computeIfAbsent(toolName, _ -> new AtomicToolStats());
        toolStats.invocations.incrementAndGet();
        if (success) {
            toolStats.successes.incrementAndGet();
        } else {
            toolStats.errors.incrementAndGet();
        }
        toolStats.totalMs.addAndGet(durationMs);
        toolStats.lastUsedEpochMs.set(System.currentTimeMillis());
    }

    /**
     * Returns metrics for a specific tool, or empty if the tool was never invoked.
     */
    public Optional<ToolMetrics> getMetrics(String toolName) {
        var toolStats = stats.get(toolName);
        if (toolStats == null) {
            return Optional.empty();
        }
        return Optional.of(toMetrics(toolName, toolStats));
    }

    /**
     * Returns a snapshot of metrics for all recorded tools.
     */
    public Map<String, ToolMetrics> allMetrics() {
        return stats.entrySet().stream()
                .collect(Collectors.toUnmodifiableMap(
                        Map.Entry::getKey,
                        e -> toMetrics(e.getKey(), e.getValue())));
    }

    private static ToolMetrics toMetrics(String toolName, AtomicToolStats s) {
        return new ToolMetrics(
                toolName,
                s.invocations.get(),
                s.successes.get(),
                s.errors.get(),
                s.totalMs.get(),
                Instant.ofEpochMilli(s.lastUsedEpochMs.get()));
    }

    /**
     * Mutable, thread-safe counters for a single tool.
     */
    private static final class AtomicToolStats {
        final AtomicInteger invocations = new AtomicInteger();
        final AtomicInteger successes = new AtomicInteger();
        final AtomicInteger errors = new AtomicInteger();
        final AtomicLong totalMs = new AtomicLong();
        final AtomicLong lastUsedEpochMs = new AtomicLong();
    }
}
