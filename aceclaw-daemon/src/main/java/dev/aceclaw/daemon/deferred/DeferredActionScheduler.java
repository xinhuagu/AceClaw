package dev.aceclaw.daemon.deferred;

import dev.aceclaw.core.agent.AgentLoopConfig;
import dev.aceclaw.core.agent.CancellationToken;
import dev.aceclaw.core.agent.StreamingAgentLoop;
import dev.aceclaw.core.agent.ToolRegistry;
import dev.aceclaw.core.agent.Turn;
import dev.aceclaw.core.llm.ContentBlock;
import dev.aceclaw.core.llm.LlmClient;
import dev.aceclaw.core.llm.Message;
import dev.aceclaw.core.llm.StreamEventHandler;
import dev.aceclaw.daemon.AgentSession;
import dev.aceclaw.daemon.SessionManager;
import dev.aceclaw.infra.event.DeferEvent;
import dev.aceclaw.infra.event.EventBus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Background scheduler that checks for due deferred actions and executes them.
 *
 * <p>Runs on a single scheduled thread that ticks every N seconds (default 5),
 * checking which actions are due. When an action is due, it executes immediately
 * on an isolated virtual thread with a snapshot of the session's conversation
 * history. This avoids contention with the session's main turn lock.
 *
 * <p>Limits:
 * <ul>
 *   <li>Per-session: max 3 pending actions</li>
 *   <li>Global: max 10 pending actions</li>
 *   <li>Delay: 5-3600 seconds</li>
 *   <li>Retries: max 5</li>
 * </ul>
 */
public final class DeferredActionScheduler {

    private static final Logger log = LoggerFactory.getLogger(DeferredActionScheduler.class);

    /** Maximum pending actions per session. */
    static final int MAX_PER_SESSION = 3;
    /** Maximum pending actions globally. */
    static final int MAX_GLOBAL = 10;
    /** Minimum delay in seconds. */
    static final int MIN_DELAY_SECONDS = 5;
    /** Maximum delay in seconds. */
    static final int MAX_DELAY_SECONDS = 3600;
    /** Maximum retries per action. */
    static final int MAX_RETRIES = 5;
    /** Default expiry offset from runAt. */
    private static final Duration DEFAULT_EXPIRY_OFFSET = Duration.ofMinutes(30);
    /** Max output chars stored in action. */
    private static final int MAX_OUTPUT_CHARS = 400;

    private final DeferredActionStore store;
    private final SessionManager sessionManager;
    private final LlmClient llmClient;
    private final ToolRegistry toolRegistry;
    private final String model;
    private final String systemPrompt;
    private final int maxTokens;
    private final int thinkingBudget;
    private final EventBus eventBus;
    private final int tickSeconds;

    /** Active virtual worker threads for graceful shutdown coordination. */
    private final java.util.Set<Thread> activeWorkers =
            java.util.Collections.newSetFromMap(new ConcurrentHashMap<>());

    private ScheduledExecutorService scheduler;
    private volatile boolean running;

    public DeferredActionScheduler(
            DeferredActionStore store,
            SessionManager sessionManager,
            LlmClient llmClient,
            ToolRegistry toolRegistry,
            String model,
            String systemPrompt,
            int maxTokens,
            int thinkingBudget,
            EventBus eventBus,
            int tickSeconds) {
        this.store = Objects.requireNonNull(store, "store");
        this.sessionManager = Objects.requireNonNull(sessionManager, "sessionManager");
        this.llmClient = llmClient;
        this.toolRegistry = toolRegistry;
        this.model = model;
        this.systemPrompt = systemPrompt;
        this.maxTokens = maxTokens;
        this.thinkingBudget = thinkingBudget;
        this.eventBus = eventBus;
        this.tickSeconds = tickSeconds > 0 ? tickSeconds : 5;
    }

    /**
     * Starts the scheduler. The first tick is delayed by one tick interval.
     */
    public void start() {
        if (running) {
            log.warn("DeferredActionScheduler already running");
            return;
        }
        running = true;

        try {
            store.load();
        } catch (IOException e) {
            log.error("Failed to load deferred actions: {}", e.getMessage(), e);
        }

        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            var t = new Thread(r, "deferred-scheduler");
            t.setDaemon(true);
            return t;
        });

        scheduler.scheduleAtFixedRate(this::tick, tickSeconds, tickSeconds, TimeUnit.SECONDS);
        log.info("DeferredActionScheduler started: {} pending action(s), tick every {}s",
                store.totalPendingCount(), tickSeconds);
    }

    /**
     * Stops the scheduler gracefully.
     */
    public void stop() {
        if (!running) return;
        running = false;
        if (scheduler != null) {
            scheduler.shutdown();
            try {
                if (!scheduler.awaitTermination(10, TimeUnit.SECONDS)) {
                    scheduler.shutdownNow();
                    log.warn("DeferredActionScheduler forced shutdown after 10s timeout");
                }
            } catch (InterruptedException e) {
                scheduler.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
        // Await in-flight virtual worker threads (best-effort, 15s max)
        for (var worker : activeWorkers) {
            try {
                worker.join(Duration.ofSeconds(15));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        activeWorkers.clear();
        log.info("DeferredActionScheduler stopped");
    }

    /**
     * Returns whether the scheduler is currently running.
     */
    public boolean isRunning() {
        return running;
    }

    /**
     * Schedules a new deferred action.
     *
     * @param sessionId    the session requesting the deferral
     * @param delaySeconds seconds to wait before execution
     * @param goal         natural-language goal for the agent
     * @param maxRetries   maximum retry attempts (clamped to MAX_RETRIES)
     * @return the scheduled action
     * @throws IllegalArgumentException if limits are exceeded
     */
    public DeferredAction schedule(String sessionId, int delaySeconds, String goal, int maxRetries) {
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(goal, "goal");

        // Validate limits
        if (delaySeconds < MIN_DELAY_SECONDS || delaySeconds > MAX_DELAY_SECONDS) {
            throw new IllegalArgumentException(
                    "delaySeconds must be between " + MIN_DELAY_SECONDS + " and " + MAX_DELAY_SECONDS
                            + ", got: " + delaySeconds);
        }
        int clampedRetries = Math.min(Math.max(0, maxRetries), MAX_RETRIES);

        // Idempotency check first — duplicates should dedup, not fail with "limit reached"
        String idempotencyKey = computeIdempotencyKey(sessionId, goal);
        var existing = store.findByIdempotencyKey(idempotencyKey);
        if (existing.isPresent()) {
            log.info("Dedup: returning existing action {} for idempotency key", existing.get().actionId());
            return existing.get();
        }

        if (store.pendingCountForSession(sessionId) >= MAX_PER_SESSION) {
            throw new IllegalArgumentException(
                    "Per-session limit reached (" + MAX_PER_SESSION + " pending actions)");
        }
        if (store.totalPendingCount() >= MAX_GLOBAL) {
            throw new IllegalArgumentException(
                    "Global limit reached (" + MAX_GLOBAL + " pending actions)");
        }

        Instant now = Instant.now();
        var action = new DeferredAction(
                UUID.randomUUID().toString(),
                sessionId,
                idempotencyKey,
                now,
                now.plusSeconds(delaySeconds),
                now.plusSeconds(delaySeconds).plus(DEFAULT_EXPIRY_OFFSET),
                goal,
                clampedRetries,
                0,
                DeferredActionState.PENDING,
                null,
                null
        );

        store.put(action);
        saveQuietly();

        publishEvent(new DeferEvent.ActionScheduled(
                action.actionId(), sessionId, goal, action.runAt(), Instant.now()));
        log.info("Scheduled deferred action '{}' for session '{}', runAt={}",
                action.actionId(), sessionId, action.runAt());

        return action;
    }

    /**
     * Cancels a deferred action by id.
     *
     * @return true if the action was found and cancelled
     */
    public boolean cancel(String actionId, String reason) {
        Objects.requireNonNull(actionId, "actionId");
        var opt = store.get(actionId);
        if (opt.isEmpty()) return false;

        var action = opt.get();
        if (action.state() != DeferredActionState.PENDING) return false;

        store.put(action.withState(DeferredActionState.CANCELLED));
        saveQuietly();

        publishEvent(new DeferEvent.ActionCancelled(
                actionId, action.sessionId(),
                reason != null ? reason : "user-cancelled",
                Instant.now()));
        log.info("Cancelled deferred action '{}'", actionId);
        return true;
    }

    /**
     * Returns all pending actions (for status reporting).
     */
    public List<DeferredAction> pendingActions() {
        return store.allPending();
    }

    /**
     * Returns the store for external management.
     */
    public DeferredActionStore store() {
        return store;
    }

    // -- internal --------------------------------------------------------

    /**
     * Scheduler tick: scans PENDING actions for due/expired.
     * Due actions execute immediately on isolated virtual threads with a snapshot
     * of the session's conversation history — no turn lock contention.
     */
    void tick() {
        if (!running) return;

        try {
            Instant now = Instant.now();
            var pending = store.allPending();

            for (DeferredAction action : pending) {
                if (!running) break;

                // Check expiry first
                if (action.isExpired(now)) {
                    store.put(action.withState(DeferredActionState.EXPIRED));
                    saveQuietly();
                    publishEvent(new DeferEvent.ActionExpired(
                            action.actionId(), action.sessionId(), Instant.now()));
                    log.info("Deferred action '{}' expired", action.actionId());
                    continue;
                }

                // Check if due
                if (!action.isDue(now)) continue;

                // Mark as RUNNING in tick() (single-threaded) before spawning
                // to prevent duplicate dispatch on the next tick
                var dispatchedAction = action.withAttempt().withState(DeferredActionState.RUNNING);
                store.put(dispatchedAction);
                saveQuietly();

                publishEvent(new DeferEvent.ActionTriggered(
                        action.actionId(), action.sessionId(), Instant.now()));
                log.info("Dispatching deferred action '{}' (attempt {}, max retries {})",
                        action.actionId(), dispatchedAction.attempts(), dispatchedAction.maxRetries());

                // Spawn virtual thread immediately — no turn lock needed
                final var actionToRun = dispatchedAction;
                var worker = Thread.ofVirtual()
                        .name("deferred-exec-" + action.actionId())
                        .start(() -> executeAction(actionToRun));
                activeWorkers.add(worker);
            }
        } catch (Exception e) {
            log.error("Deferred scheduler tick error: {}", e.getMessage(), e);
        }
    }

    /** Tool names with mutable per-request state that must not be shared concurrently. */
    private static final java.util.Set<String> UNSAFE_TOOL_NAMES = java.util.Set.of("skill", "defer_check");

    /**
     * Executes a single deferred action on the current thread (expected: virtual thread).
     * Uses a read-only snapshot of the session's conversation history, an isolated
     * ToolRegistry (excluding tools with mutable per-request state), and does not
     * modify the session or acquire any turn lock.
     *
     * <p>The action must already be in RUNNING state (set by {@link #tick()}).
     */
    private void executeAction(DeferredAction action) {
        try {
            // Check session still exists
            var session = sessionManager.getSession(action.sessionId());
            if (session == null || !session.isActive()) {
                log.warn("Session '{}' not found or inactive, cancelling deferred action '{}'",
                        action.sessionId(), action.actionId());
                store.put(action.withState(DeferredActionState.CANCELLED));
                saveQuietly();
                publishEvent(new DeferEvent.ActionCancelled(
                        action.actionId(), action.sessionId(), "session-gone", Instant.now()));
                return;
            }

            // Snapshot session messages for read-only context (no lock needed)
            var conversationSnapshot = toMessages(List.copyOf(session.messages()));

            // Build an isolated ToolRegistry excluding tools with mutable session state
            // (SkillTool, DeferCheckTool) to prevent cross-contamination with main turns
            var isolatedRegistry = new ToolRegistry();
            for (var tool : toolRegistry.all()) {
                if (!UNSAFE_TOOL_NAMES.contains(tool.name())) {
                    isolatedRegistry.register(tool);
                }
            }

            long startNanos = System.nanoTime();
            try {
                var loopConfig = AgentLoopConfig.builder()
                        .sessionId("deferred-" + action.actionId())
                        .maxIterations(15)
                        .build();
                var agentLoop = new StreamingAgentLoop(
                        llmClient, isolatedRegistry, model, systemPrompt,
                        maxTokens, thinkingBudget, null, loopConfig);

                String deferPrompt = """
                        [DEFERRED ACTION] A previously scheduled check-back is now executing.

                        Goal: %s

                        Review the current state and take appropriate action. Report your findings.
                        """.formatted(action.goal());

                var turn = agentLoop.runTurn(deferPrompt, conversationSnapshot,
                        new SilentStreamHandler(), new CancellationToken());

                String output = summarizeTurnOutput(turn);
                long durationMs = Duration.ofNanos(System.nanoTime() - startNanos).toMillis();

                // Result stays in DeferredAction store + EventFeed — not written to session
                store.put(action.withSuccess(truncate(output, MAX_OUTPUT_CHARS)));
                saveQuietly();

                publishEvent(new DeferEvent.ActionCompleted(
                        action.actionId(), action.sessionId(), durationMs,
                        truncate(output, 200), Instant.now()));
                log.info("Deferred action '{}' completed in {}ms", action.actionId(), durationMs);

            } catch (Exception e) {
                long durationMs = Duration.ofNanos(System.nanoTime() - startNanos).toMillis();
                log.error("Deferred action '{}' failed (attempt {}/{}): {}",
                        action.actionId(), action.attempts(), action.maxRetries(),
                        e.getMessage());

                var failedAction = action.withFailure(e.getMessage());
                store.put(failedAction);
                saveQuietly();

                publishEvent(new DeferEvent.ActionFailed(
                        action.actionId(), action.sessionId(), e.getMessage(),
                        failedAction.attempts(), failedAction.maxRetries(), Instant.now()));

                // If still retryable (state went back to PENDING), update runAt for backoff
                if (failedAction.state() == DeferredActionState.PENDING) {
                    int backoffSeconds = 30 * failedAction.attempts();
                    var retryAction = new DeferredAction(
                            failedAction.actionId(), failedAction.sessionId(),
                            failedAction.idempotencyKey(), failedAction.createdAt(),
                            Instant.now().plusSeconds(backoffSeconds),
                            failedAction.expiresAt(), failedAction.goal(),
                            failedAction.maxRetries(), failedAction.attempts(),
                            DeferredActionState.PENDING, failedAction.lastError(),
                            failedAction.lastOutput());
                    store.put(retryAction);
                    saveQuietly();
                    log.info("Deferred action '{}' scheduled for retry in {}s",
                            action.actionId(), backoffSeconds);
                }
            }
        } finally {
            activeWorkers.remove(Thread.currentThread());
        }
    }

    /**
     * Converts session conversation messages to LLM messages.
     */
    private static List<Message> toMessages(List<AgentSession.ConversationMessage> history) {
        var safe = history != null ? history : List.<AgentSession.ConversationMessage>of();
        var result = new ArrayList<Message>();
        for (var msg : safe) {
            switch (msg) {
                case AgentSession.ConversationMessage.User u ->
                        result.add(Message.user(u.content()));
                case AgentSession.ConversationMessage.Assistant a ->
                        result.add(Message.assistant(a.content()));
                case AgentSession.ConversationMessage.System s ->
                        result.add(Message.user("[SYSTEM] " + s.content()));
            }
        }
        return result;
    }

    private static String summarizeTurnOutput(Turn turn) {
        if (turn == null) return "";
        String text = turn.text();
        if (text != null && !text.isBlank()) return text.strip();

        // Fallback: last successful tool result
        var messages = turn.newMessages();
        for (int i = messages.size() - 1; i >= 0; i--) {
            Message msg = messages.get(i);
            if (!(msg instanceof Message.UserMessage user)) continue;
            var blocks = user.content();
            for (int j = blocks.size() - 1; j >= 0; j--) {
                if (blocks.get(j) instanceof ContentBlock.ToolResult tr && !tr.isError()) {
                    String content = tr.content();
                    if (content != null && !content.isBlank()) {
                        return truncate(content.strip(), 1600);
                    }
                }
            }
        }
        return "Deferred action completed with no output text.";
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() > max ? s.substring(0, max) + "..." : s;
    }

    static String computeIdempotencyKey(String sessionId, String goal) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            digest.update(goal.getBytes(StandardCharsets.UTF_8));
            String fullHash = HexFormat.of().formatHex(digest.digest());
            String hash = fullHash.length() > 16 ? fullHash.substring(0, 16) : fullHash;
            return sessionId + ":" + hash;
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is always available
            throw new RuntimeException(e);
        }
    }

    private void publishEvent(DeferEvent event) {
        if (eventBus != null) {
            eventBus.publish(event);
        }
    }

    private void saveQuietly() {
        try {
            store.save();
        } catch (IOException e) {
            log.error("Failed to save deferred action store: {}", e.getMessage(), e);
        }
    }

    /** No-op stream handler for deferred execution (no user to display to). */
    private static final class SilentStreamHandler implements StreamEventHandler {
        // All defaults are no-ops
    }
}
