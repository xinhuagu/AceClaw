package dev.aceclaw.daemon.cron;

import dev.aceclaw.core.agent.AgentLoopConfig;
import dev.aceclaw.core.agent.StreamingAgentLoop;
import dev.aceclaw.core.agent.ToolRegistry;
import dev.aceclaw.core.llm.LlmClient;
import dev.aceclaw.core.llm.LlmException;
import dev.aceclaw.core.llm.StreamEventHandler;
import dev.aceclaw.infra.event.EventBus;
import dev.aceclaw.infra.event.SchedulerEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Background scheduler that evaluates cron expressions and executes jobs.
 *
 * <p>Runs on a single scheduled thread that ticks every N seconds (default 60),
 * checking which jobs are due. Jobs are executed one at a time on virtual threads
 * with per-job timeouts, following the same pattern as {@code BootExecutor}.
 *
 * <p>Guardrails:
 * <ul>
 *   <li>One job at a time — prevents resource exhaustion</li>
 *   <li>Per-job timeout — enforced via {@link CompletableFuture#get(long, TimeUnit)}</li>
 *   <li>Retry with backoff — configurable per job (default [30, 60, 300] seconds)</li>
 *   <li>Circuit breaker — disables job after N consecutive failures (default 5)</li>
 *   <li>Per-job permission checker — restricts tools to the job's allowlist</li>
 * </ul>
 */
public final class CronScheduler {

    private static final Logger log = LoggerFactory.getLogger(CronScheduler.class);

    private final JobStore jobStore;
    private final LlmClient llmClient;
    private final ToolRegistry toolRegistry;
    private final String model;
    private final String systemPrompt;
    private final int maxTokens;
    private final int thinkingBudget;
    private final EventBus eventBus;
    private final int tickSeconds;

    private ScheduledExecutorService scheduler;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean jobRunning = new AtomicBoolean(false);

    /**
     * Creates a CronScheduler.
     *
     * @param jobStore       persistent job store
     * @param llmClient      LLM client (should be circuit-breaker-wrapped)
     * @param toolRegistry   shared tool registry
     * @param model          model identifier
     * @param systemPrompt   system prompt for cron executions
     * @param maxTokens      max output tokens per LLM call
     * @param thinkingBudget thinking budget tokens (0 = disabled)
     * @param eventBus       event bus for publishing scheduler events (nullable)
     * @param tickSeconds    scheduler tick interval in seconds
     */
    public CronScheduler(JobStore jobStore, LlmClient llmClient, ToolRegistry toolRegistry,
                          String model, String systemPrompt,
                          int maxTokens, int thinkingBudget,
                          EventBus eventBus, int tickSeconds) {
        this.jobStore = jobStore;
        this.llmClient = llmClient;
        this.toolRegistry = toolRegistry;
        this.model = model;
        this.systemPrompt = systemPrompt;
        this.maxTokens = maxTokens;
        this.thinkingBudget = thinkingBudget;
        this.eventBus = eventBus;
        this.tickSeconds = tickSeconds > 0 ? tickSeconds : 60;
    }

    /**
     * Starts the scheduler. The first tick is delayed by one tick interval.
     */
    public void start() {
        if (running.getAndSet(true)) {
            log.warn("CronScheduler already running");
            return;
        }

        try {
            jobStore.load();
        } catch (IOException e) {
            log.error("Failed to load cron jobs: {}", e.getMessage(), e);
        }

        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            var t = new Thread(r, "cron-scheduler");
            t.setDaemon(true);
            return t;
        });

        scheduler.scheduleAtFixedRate(this::tick, tickSeconds, tickSeconds, TimeUnit.SECONDS);
        log.info("CronScheduler started: {} job(s), tick every {}s",
                jobStore.size(), tickSeconds);
    }

    /**
     * Stops the scheduler gracefully. Waits up to 30 seconds for in-progress jobs.
     */
    public void stop() {
        if (!running.getAndSet(false)) {
            return;
        }
        if (scheduler != null) {
            scheduler.shutdown();
            try {
                if (!scheduler.awaitTermination(30, TimeUnit.SECONDS)) {
                    scheduler.shutdownNow();
                    log.warn("CronScheduler forced shutdown after 30s timeout");
                }
            } catch (InterruptedException e) {
                scheduler.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
        log.info("CronScheduler stopped");
    }

    /**
     * Returns whether the scheduler is currently running.
     */
    public boolean isRunning() {
        return running.get();
    }

    /**
     * Returns the job store for external management.
     */
    public JobStore jobStore() {
        return jobStore;
    }

    /**
     * Scheduler tick: checks all enabled jobs for due execution.
     * Runs on the scheduler thread; job execution is delegated to a virtual thread.
     */
    void tick() {
        if (!running.get()) return;

        try {
            Instant now = Instant.now();
            var enabledJobs = jobStore.enabled();

            for (CronJob job : enabledJobs) {
                if (!running.get()) break;

                // Skip if circuit breaker tripped
                if (job.isCircuitBroken()) {
                    log.debug("Skipping job '{}': circuit breaker tripped ({} consecutive failures)",
                            job.id(), job.consecutiveFailures());
                    continue;
                }

                // Check if job is due
                try {
                    var cronExpr = CronExpression.parse(job.expression());
                    Instant lastRun = job.lastRunAt() != null ? job.lastRunAt() : Instant.EPOCH;
                    Instant nextFire = cronExpr.nextFireTime(lastRun);

                    if (nextFire != null && !nextFire.isAfter(now)) {
                        executeJob(job);
                    }
                } catch (IllegalArgumentException e) {
                    log.warn("Invalid cron expression for job '{}': {}", job.id(), e.getMessage());
                }
            }
        } catch (Exception e) {
            log.error("Scheduler tick error: {}", e.getMessage(), e);
        }
    }

    /**
     * Executes a single cron job. Blocks until completion (one job at a time).
     */
    private void executeJob(CronJob job) {
        // Guard: one job at a time
        if (!jobRunning.compareAndSet(false, true)) {
            publishEvent(new SchedulerEvent.JobSkipped(
                    job.id(), "Previous job still running", Instant.now()));
            log.debug("Skipping job '{}': another job is still running", job.id());
            return;
        }

        try {
            publishEvent(new SchedulerEvent.JobTriggered(
                    job.id(), job.expression(), Instant.now()));
            log.info("Executing cron job '{}': {}", job.id(), job.name());

            long startNanos = System.nanoTime();
            String result = runJobWithRetry(job);
            long durationMs = Duration.ofNanos(System.nanoTime() - startNanos).toMillis();

            // Mark success
            jobStore.put(job.withSuccess(Instant.now()));
            saveQuietly();

            publishEvent(new SchedulerEvent.JobCompleted(
                    job.id(), durationMs,
                    result.length() > 200 ? result.substring(0, 200) + "..." : result,
                    Instant.now()));
            log.info("Cron job '{}' completed in {}ms", job.id(), durationMs);

        } catch (Exception e) {
            // Mark failure
            var failedJob = job.withFailure(e.getMessage());
            jobStore.put(failedJob);
            saveQuietly();

            publishEvent(new SchedulerEvent.JobFailed(
                    job.id(), e.getMessage(),
                    failedJob.consecutiveFailures(),
                    CronJob.CIRCUIT_BREAKER_THRESHOLD,
                    Instant.now()));
            log.error("Cron job '{}' failed (attempt {}): {}",
                    job.id(), failedJob.consecutiveFailures(), e.getMessage());

        } finally {
            jobRunning.set(false);
        }
    }

    /**
     * Runs a job with retry and backoff on failure.
     */
    private String runJobWithRetry(CronJob job) throws Exception {
        var backoff = job.retryBackoff();
        int maxAttempts = (backoff != null ? backoff.size() : 0) + 1;
        Exception lastError = null;

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                return runJobOnce(job);
            } catch (Exception e) {
                lastError = e;
                if (attempt < maxAttempts) {
                    int delaySec = backoff.get(attempt - 1);
                    log.warn("Cron job '{}' attempt {}/{} failed, retrying in {}s: {}",
                            job.id(), attempt, maxAttempts, delaySec, e.getMessage());
                    try {
                        Thread.sleep(delaySec * 1000L);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw e;
                    }
                }
            }
        }
        throw lastError;
    }

    /**
     * Runs a job once with timeout enforcement.
     */
    private String runJobOnce(CronJob job) throws Exception {
        // Build per-job permission checker
        var permChecker = new CronPermissionChecker(job.id(), job.allowedTools());
        var loopConfig = AgentLoopConfig.builder()
                .sessionId("cron-" + job.id())
                .permissionChecker(permChecker)
                .build();
        var agentLoop = new StreamingAgentLoop(
                llmClient, toolRegistry, model, systemPrompt,
                maxTokens, thinkingBudget, null, loopConfig);

        String cronPrompt = "[CRON] Executing scheduled task '" + job.name() + "' (id: " + job.id() + "):\n\n"
                + job.prompt();

        var executor = Executors.newVirtualThreadPerTaskExecutor();
        try {
            var future = CompletableFuture.supplyAsync(() -> {
                try {
                    var turn = agentLoop.runTurn(cronPrompt, new ArrayList<>(),
                            new SilentStreamHandler());
                    return turn.text();
                } catch (LlmException e) {
                    throw new CompletionException(e);
                }
            }, executor);

            try {
                return future.get(job.timeoutSeconds(), TimeUnit.SECONDS);
            } catch (TimeoutException e) {
                future.cancel(true);
                throw new TimeoutException("Cron job '" + job.id() + "' timed out after "
                        + job.timeoutSeconds() + "s");
            } catch (ExecutionException e) {
                Throwable cause = e.getCause();
                if (cause instanceof Exception ex) {
                    throw ex;
                }
                throw new RuntimeException(cause);
            }
        } finally {
            executor.close();
        }
    }

    private void publishEvent(SchedulerEvent event) {
        if (eventBus != null) {
            eventBus.publish(event);
        }
    }

    private void saveQuietly() {
        try {
            jobStore.save();
        } catch (IOException e) {
            log.error("Failed to save job store: {}", e.getMessage(), e);
        }
    }

    /** No-op stream handler for cron execution (no user to display to). */
    private static final class SilentStreamHandler implements StreamEventHandler {
        // All defaults are no-ops
    }
}
