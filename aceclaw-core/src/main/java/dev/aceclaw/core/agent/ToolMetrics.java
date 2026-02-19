package dev.aceclaw.core.agent;

import java.time.Instant;

/**
 * Immutable snapshot of per-tool execution statistics.
 *
 * @param toolName          the tool's registered name
 * @param totalInvocations  total number of times the tool was invoked
 * @param successCount      number of successful invocations
 * @param errorCount        number of failed invocations
 * @param totalExecutionMs  cumulative execution time in milliseconds
 * @param lastUsed          timestamp of the most recent invocation
 */
public record ToolMetrics(
        String toolName,
        int totalInvocations,
        int successCount,
        int errorCount,
        long totalExecutionMs,
        Instant lastUsed
) {

    /**
     * Average execution time in milliseconds, or 0 if no invocations recorded.
     */
    public double avgExecutionMs() {
        return totalInvocations == 0 ? 0.0 : (double) totalExecutionMs / totalInvocations;
    }

    /**
     * Success rate as a fraction in [0.0, 1.0], or 0 if no invocations recorded.
     */
    public double successRate() {
        return totalInvocations == 0 ? 0.0 : (double) successCount / totalInvocations;
    }
}
