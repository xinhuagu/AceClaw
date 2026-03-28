package dev.aceclaw.daemon.cron;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;
import java.util.List;
import java.util.Set;

/**
 * A persistent cron job definition, serialized to/from {@code jobs.json}.
 *
 * <p>Each job specifies a cron expression for scheduling, a natural-language prompt
 * for the agent, and guardrails (allowed tools, timeout, max iterations).
 * Jobs are workspace-scoped: each job records the canonical workspace path that owns it.
 *
 * @param id            unique job identifier (e.g. "daily-cleanup")
 * @param name          human-readable job name
 * @param workspace     canonical workspace path that owns this job (null for legacy global jobs)
 * @param expression    5-field cron expression (e.g. "0 2 * * *")
 * @param prompt        natural-language prompt for the agent
 * @param allowedTools  set of tool names auto-approved for this job (empty = read-only only)
 * @param timeoutSeconds max seconds for a single execution (default 300)
 * @param maxIterations max agent loop iterations per execution (default 15)
 * @param enabled       whether this job is active
 * @param retryBackoff  retry backoff delays in seconds (e.g. [30, 60, 300])
 * @param lastRunAt     last successful execution time (null if never run)
 * @param lastOutput    last successful output summary (null if never succeeded)
 * @param lastError     last error message (null if last run succeeded)
 * @param consecutiveFailures number of consecutive failures (reset on success)
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CronJob(
        String id,
        String name,
        String workspace,
        String expression,
        String prompt,
        Set<String> allowedTools,
        int timeoutSeconds,
        int maxIterations,
        boolean enabled,
        List<Integer> retryBackoff,
        Instant lastRunAt,
        String lastOutput,
        String lastError,
        int consecutiveFailures
) {

    /** Compact constructor: defensive copies for mutable collections. */
    public CronJob {
        allowedTools = allowedTools != null ? Set.copyOf(allowedTools) : Set.of();
        retryBackoff = retryBackoff != null ? List.copyOf(retryBackoff) : DEFAULT_RETRY_BACKOFF;
    }

    /** Default timeout for cron job execution. */
    public static final int DEFAULT_TIMEOUT_SECONDS = 300;

    /** Default max agent loop iterations. */
    public static final int DEFAULT_MAX_ITERATIONS = 15;

    /** Default retry backoff delays in seconds. */
    public static final List<Integer> DEFAULT_RETRY_BACKOFF = List.of(30, 60, 300);

    /** Circuit breaker threshold: stop retrying after this many consecutive failures. */
    public static final int CIRCUIT_BREAKER_THRESHOLD = 5;

    /**
     * Creates a CronJob with sensible defaults for optional fields.
     */
    /**
     * Creates a CronJob with sensible defaults, scoped to a workspace.
     */
    public static CronJob create(String id, String name, String workspace,
                                  String expression, String prompt) {
        return new CronJob(id, name, workspace, expression, prompt,
                Set.of(), DEFAULT_TIMEOUT_SECONDS, DEFAULT_MAX_ITERATIONS,
                true, DEFAULT_RETRY_BACKOFF, null, null, null, 0);
    }

    /**
     * Creates a CronJob with no workspace (global/legacy). Use the 5-arg overload for workspace-scoped jobs.
     */
    public static CronJob create(String id, String name, String expression, String prompt) {
        return create(id, name, null, expression, prompt);
    }

    /**
     * Returns a copy with updated last-run info after a successful execution.
     */
    public CronJob withSuccess(Instant runAt) {
        return withSuccess(runAt, null);
    }

    /**
     * Returns a copy with updated last-run info after a successful execution.
     */
    public CronJob withSuccess(Instant runAt, String output) {
        return new CronJob(id, name, workspace, expression, prompt, allowedTools,
                timeoutSeconds, maxIterations, enabled, retryBackoff,
                runAt, output, null, 0);
    }

    /**
     * Returns a copy with updated error info after a failed execution.
     */
    public CronJob withFailure(String error) {
        return new CronJob(id, name, workspace, expression, prompt, allowedTools,
                timeoutSeconds, maxIterations, enabled, retryBackoff,
                lastRunAt, lastOutput, error, consecutiveFailures + 1);
    }

    /**
     * Returns a copy with the enabled flag toggled.
     */
    public CronJob withEnabled(boolean enabled) {
        return new CronJob(id, name, workspace, expression, prompt, allowedTools,
                timeoutSeconds, maxIterations, enabled, retryBackoff,
                lastRunAt, lastOutput, lastError, consecutiveFailures);
    }

    /**
     * Returns true if the circuit breaker should trip (too many consecutive failures).
     */
    public boolean isCircuitBroken() {
        return consecutiveFailures >= CIRCUIT_BREAKER_THRESHOLD;
    }
}
