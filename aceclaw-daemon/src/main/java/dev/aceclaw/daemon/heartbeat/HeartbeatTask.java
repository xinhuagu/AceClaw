package dev.aceclaw.daemon.heartbeat;

import java.util.Set;

/**
 * A parsed task from a HEARTBEAT.md file.
 *
 * @param name           task name (from {@code ## heading})
 * @param schedule       5-field cron expression (required)
 * @param timeoutSeconds per-task timeout in seconds (default 300)
 * @param allowedTools   tools beyond read-only that are auto-approved (empty = read-only only)
 * @param prompt         natural-language prompt for the agent
 */
public record HeartbeatTask(
        String name,
        String schedule,
        int timeoutSeconds,
        Set<String> allowedTools,
        String prompt
) {

    /** Default timeout for heartbeat task execution. */
    public static final int DEFAULT_TIMEOUT_SECONDS = 300;

    /** Compact constructor: defensive copy for mutable collections. */
    public HeartbeatTask {
        allowedTools = allowedTools != null ? Set.copyOf(allowedTools) : Set.of();
    }
}
