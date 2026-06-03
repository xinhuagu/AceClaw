package dev.aceclaw.daemon;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.aceclaw.core.agent.AgentLoopConfig;
import dev.aceclaw.daemon.cron.CronTool;
import dev.aceclaw.core.agent.CancellationAware;
import dev.aceclaw.core.agent.CancellationToken;
import dev.aceclaw.core.agent.CompactionConfig;
import dev.aceclaw.core.agent.ContextEstimator;
import dev.aceclaw.core.agent.DoomLoopDetector;
import dev.aceclaw.core.agent.ProgressDetector;
import dev.aceclaw.core.agent.SkillMetrics;
import dev.aceclaw.core.agent.SkillOutcome;
import dev.aceclaw.core.agent.SkillOutcomeTracker;
import dev.aceclaw.core.agent.WatchdogTimer;
import dev.aceclaw.core.agent.CompactionResult;
import dev.aceclaw.core.agent.HookEvent;
import dev.aceclaw.core.agent.HookExecutor;
import dev.aceclaw.core.agent.HookResult;
import dev.aceclaw.core.agent.MessageCompactor;
import dev.aceclaw.core.agent.StreamingAgentLoop;
import dev.aceclaw.core.agent.Tool;
import dev.aceclaw.core.agent.ToolMetricsCollector;
import dev.aceclaw.core.agent.ToolMetrics;
import dev.aceclaw.core.agent.ToolRegistry;
import dev.aceclaw.core.llm.ContentBlock;
import dev.aceclaw.core.llm.LlmException;
import dev.aceclaw.core.llm.Message;
import dev.aceclaw.core.llm.RequestAttribution;
import dev.aceclaw.core.llm.StopReason;
import dev.aceclaw.core.llm.StreamEvent;
import dev.aceclaw.core.llm.StreamEventHandler;
import dev.aceclaw.core.planner.AdaptiveReplanner;
import dev.aceclaw.core.planner.ComplexityEstimator;
import dev.aceclaw.core.planner.LLMTaskPlanner;
import dev.aceclaw.core.planner.PlanCheckpoint;
import dev.aceclaw.core.planner.PlanCheckpointStore;
import dev.aceclaw.core.planner.PlanExecutionResult;
import dev.aceclaw.core.planner.PlanStatus;
import dev.aceclaw.learning.LearningExplanation;
import dev.aceclaw.learning.LearningExplanationRecorder;
import dev.aceclaw.learning.skill.SkillMemoryFeedback;
import dev.aceclaw.learning.skill.SkillMetricsStore;
import dev.aceclaw.learning.skill.SkillRefinementEngine;
import dev.aceclaw.learning.validation.LearningValidation;
import dev.aceclaw.learning.validation.LearningValidationRecorder;
import dev.aceclaw.core.planner.PlannedStep;
import dev.aceclaw.core.planner.SequentialPlanExecutor;
import dev.aceclaw.core.planner.StepResult;
import dev.aceclaw.core.planner.StepStatus;
import dev.aceclaw.core.planner.TaskPlan;
import dev.aceclaw.core.planner.TurnCheckpoint;
import dev.aceclaw.memory.AutoMemoryStore;
import dev.aceclaw.memory.CandidatePromptAssembler;
import dev.aceclaw.memory.CandidateStore;
import dev.aceclaw.memory.DailyJournal;
import dev.aceclaw.memory.MarkdownMemoryStore;
import dev.aceclaw.memory.MemoryEntry;
import dev.aceclaw.security.PermissionDecision;
import dev.aceclaw.security.PermissionLevel;
import dev.aceclaw.security.PermissionManager;
import dev.aceclaw.security.PermissionRequest;
import dev.aceclaw.tools.AppleScriptTool;
import dev.aceclaw.tools.BashExecTool;
import dev.aceclaw.tools.EditFileTool;
import dev.aceclaw.tools.GlobSearchTool;
import dev.aceclaw.tools.GrepSearchTool;
import dev.aceclaw.tools.ListDirTool;
import dev.aceclaw.tools.MemoryTool;
import dev.aceclaw.tools.ReadFileTool;
import dev.aceclaw.tools.ScreenCaptureTool;
import dev.aceclaw.tools.SkillTool;
import dev.aceclaw.tools.TaskTool;
import dev.aceclaw.tools.WebFetchTool;
import dev.aceclaw.tools.WriteFileTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Locale;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;
import java.util.function.Function;

/**
 * Handles {@code agent.prompt} JSON-RPC requests with streaming support.
 *
 * <p>Uses {@link StreamingAgentLoop} to run the agent's ReAct loop while
 * forwarding LLM stream events (text deltas, tool use) as JSON-RPC notifications
 * to the client via a {@link StreamContext}.
 *
 * <p>Integrates with {@link PermissionManager} to check each tool call before
 * execution. When user approval is needed, sends a {@code permission.request}
 * notification and reads back the client's {@code permission.response}.
 */
public final class StreamingAgentHandler {

    private static final Logger log = LoggerFactory.getLogger(StreamingAgentHandler.class);

    private final SessionManager sessionManager;
    private final StreamingAgentLoop agentLoop;
    private final ToolRegistry toolRegistry;
    private final PermissionManager permissionManager;
    private final ObjectMapper objectMapper;
    private final ConcurrentHashMap<String, ToolMetricsCollector> sessionMetrics =
            new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, DoomLoopDetector> sessionDoomLoops =
            new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ProgressDetector> sessionProgressDetectors =
            new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, List<String>> sessionInjectedCandidateIds =
            new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, CompletableFuture<Void>> sessionPostProcessing =
            new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicBoolean> sessionRuntimePruneScheduled =
            new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Path, SkillOutcomeTracker> projectSkillTrackers =
            new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ReentrantLock> skillOutcomeLocks =
            new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, List<String>> sessionRecentSuccessfulSkills =
            new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AntiPatternGateOverride> antiPatternGateOverrides =
            new ConcurrentHashMap<>();
    private final SkillMetricsStore skillMetricsStore = new SkillMetricsStore();
    private volatile RuntimeMetricsExporter runtimeMetricsExporter;
    private volatile dev.aceclaw.memory.InjectionAuditLog injectionAuditLog;
    /**
     * WebSocket bridge for browser dashboard (issue #431). Null when disabled —
     * the per-request context falls back to direct CLI delivery in that case.
     */
    private volatile WebSocketBridge webSocketBridge;
    /** Sessions for which {@code stream.session_started} has already been emitted. */
    private final Set<String> sessionsStartedSignaled = ConcurrentHashMap.newKeySet();
    /**
     * Daemon-wide registry of pending permission requests keyed by
     * requestId (issue #433). The CLI's per-context monitor and the
     * WebSocket bridge's inbound handler both route
     * {@code permission.response} messages through here, so either
     * channel can win the response. {@link CancelAwareStreamContext}
     * mirrors its per-context {@code pendingPermissions} writes into
     * this map; when one channel completes a future, the other path
     * sees a no-op (CompletableFuture.complete is idempotent + the
     * remove is atomic). Per-context shutdown removes its own entries
     * without affecting other concurrent prompts.
     *
     * <p>Each entry holds the originating session id alongside the
     * future. {@link #routePermissionResponse} validates that a
     * browser-sent response carries a matching {@code sessionId} —
     * without this check, any WS client that observed the broadcast
     * {@code permission.request} (which fans out to all connected
     * clients) could replay its {@code requestId} and approve/deny
     * tools on a session it doesn't own. The CLI path bypasses the
     * sessionId check because UDS connections are inherently
     * per-session at the transport layer.
     */
    private final ConcurrentHashMap<String, PendingPermission> permissionRegistry =
            new ConcurrentHashMap<>();


    /** Per-session turn locks for serializing main turns within a session. */
    private final ConcurrentHashMap<String, ReentrantLock> sessionTurnLocks =
            new ConcurrentHashMap<>();


    /**
     * Creates a streaming agent handler.
     *
     * @param sessionManager    the session manager for looking up sessions
     * @param agentLoop         the streaming agent loop to execute prompts
     * @param toolRegistry      the tool registry for permission-aware tool wrapping
     * @param permissionManager the permission manager for tool access control
     * @param objectMapper      Jackson mapper for building JSON responses
     */
    public StreamingAgentHandler(
            SessionManager sessionManager,
            StreamingAgentLoop agentLoop,
            ToolRegistry toolRegistry,
            PermissionManager permissionManager,
            ObjectMapper objectMapper) {
        this.sessionManager = sessionManager;
        this.agentLoop = agentLoop;
        this.toolRegistry = toolRegistry;
        this.permissionManager = permissionManager;
        this.objectMapper = objectMapper;
    }

    /**
     * Registers the {@code agent.prompt} streaming handler with the given router.
     */
    public void register(RequestRouter router) {
        router.registerStreaming("agent.prompt", this::handlePrompt);
    }

    /**
     * Wires the WebSocket bridge for browser-facing event fan-out (issue #431).
     * Pass {@code null} to disable; once set, every JSON-RPC notification emitted
     * during {@code agent.prompt} is also broadcast to all connected WS clients.
     */
    public void setWebSocketBridge(WebSocketBridge bridge) {
        this.webSocketBridge = bridge;
    }

    /**
     * Routes a {@code permission.response} message arriving on the
     * WebSocket bridge into the per-context {@link CompletableFuture}
     * the requesting tool is blocked on (issue #433). Returns
     * {@code true} if the response landed on a still-pending request,
     * {@code false} if the requestId is unknown (already completed by
     * the CLI, timed out, or never existed).
     *
     * <p>The CLI socket monitor and this method share a single
     * {@link #permissionRegistry} keyed by requestId, so first response
     * wins regardless of channel. {@link CompletableFuture#complete}
     * is idempotent on the future's side — late duplicates are safe.
     *
     * <p>Public for the WS inbound dispatcher in {@code AceClawDaemon}
     * to call. Not part of the CLI's UDS protocol.
     */
    public boolean routePermissionResponse(JsonNode message) {
        if (message == null) return false;
        var params = message.get("params");
        if (params == null) return false;
        var ridNode = params.get("requestId");
        if (ridNode == null) return false;
        var requestId = ridNode.asText("");
        if (requestId.isEmpty()) return false;
        var pending = permissionRegistry.get(requestId);
        if (pending == null) {
            log.warn("WS permission.response: no pending request for requestId={}, "
                    + "dropping (already completed by CLI, timed out, or unknown)",
                    requestId);
            return false;
        }
        // Cross-session guard: WS clients see ALL permission.request
        // broadcasts (the bridge fans out to every connected tab), so
        // a tab viewing session B could otherwise replay session A's
        // requestId and approve/deny on A's behalf. The browser must
        // include the sessionId it observed on the request envelope;
        // we reject mismatches and missing values.
        var sidNode = params.get("sessionId");
        var responseSessionId = sidNode == null ? null : sidNode.asText("");
        if (responseSessionId == null || responseSessionId.isEmpty()) {
            log.warn("WS permission.response missing sessionId for requestId={}, dropping",
                    requestId);
            return false;
        }
        if (!pending.sessionId().equals(responseSessionId)) {
            log.warn("WS permission.response sessionId mismatch for requestId={}: "
                    + "expected {}, got {} — dropping (cross-session attempt)",
                    requestId, pending.sessionId(), responseSessionId);
            return false;
        }
        // Atomic remove-if-still-present; if the CLI got there first,
        // remove returns false and we leave the pre-existing completion
        // alone.
        if (!permissionRegistry.remove(requestId, pending)) return false;
        boolean completed = pending.future().complete(message);
        log.debug("WS routePermissionResponse: requestId={}, sessionId={}, completed={}",
                requestId, responseSessionId, completed);
        if (completed) {
            // Notify the originating CLI's UDS context so its TUI can
            // dismiss the in-progress y/n prompt — without this signal
            // the daemon-side tool resumes but the CLI prompt sits
            // there waiting for stdin that has no effect anymore. The
            // notification carries the resolved answer so the CLI can
            // show "Approved via dashboard" rather than a generic
            // dismissal. Best-effort: failures here don't roll back
            // the resolution that already took the daemon-side path.
            try {
                var approvedNode = params.get("approved");
                boolean approved = approvedNode != null && approvedNode.asBoolean(false);
                var cancelParams = objectMapper.createObjectNode();
                cancelParams.put("requestId", requestId);
                cancelParams.put("approved", approved);
                cancelParams.put("via", "websocket");
                pending.context().sendNotification("permission.cancelled", cancelParams);
                log.debug("Sent permission.cancelled to originating CLI (requestId={})",
                        requestId);
            } catch (IOException e) {
                log.warn("Failed to notify originating CLI of WS-resolved permission "
                        + "(requestId={}): {}", requestId, e.getMessage());
            }
        }
        return completed;
    }

    /**
     * Emits a notification that should reach BROWSER clients only — never the
     * CLI's Unix-domain socket protocol. Used for the lifecycle events added in
     * #431 ({@code stream.session_started}, {@code stream.turn_started},
     * {@code stream.turn_completed}, {@code stream.plan_step_fallback}) which
     * the existing CLI does not consume and should not have to tolerate.
     *
     * <p>The boundary lives at the emission site rather than in
     * {@link EventMultiplexer}: that keeps {@code sendNotification()} faithful
     * to the original CLI protocol and {@code broadcast()} the browser
     * projection, instead of pushing per-method routing policy into transport.
     *
     * <p>No-op when the bridge is disabled — these events are not emitted at
     * all in that case, so the CLI never sees them and there is no CLI
     * compatibility concern.
     *
     * <p>Package-private for unit tests; production callers are inside this
     * class.
     */
    void emitBrowserOnly(String sessionId, String method, Object params) {
        var bridge = this.webSocketBridge;
        if (bridge == null) {
            return;
        }
        bridge.broadcast(sessionId, method, params);
    }

    private Object handlePrompt(JsonNode params, StreamContext context) throws Exception {
        var sessionId = requireString(params, "sessionId");
        var prompt = requireString(params, "prompt");
        // Optional escape hatch for users who want to bypass the
        // ComplexityEstimator and always run the planner — wired to the
        // CLI's /plan slash command. When true, skips the heuristic
        // entirely and goes straight to executePlannedPrompt below.
        var forcePlan = params.has("forcePlan") && params.get("forcePlan").asBoolean(false);

        var session = sessionManager.getSession(sessionId);
        if (session == null) {
            throw new IllegalArgumentException("Session not found: " + sessionId);
        }
        if (!session.isActive()) {
            throw new IllegalArgumentException("Session is not active: " + sessionId);
        }

        // Set workspace context for tools (e.g. CronTool) that need to know the current workspace.
        // Cleared in the finally block below to cover all exit paths (success, plan, resume, error).
        //
        // Lifecycle-notification state (issue #431): turn_started + turn_completed
        // both fire INSIDE the per-session turnLock (so concurrent prompts on
        // the same session see correctly ordered events and no interleaving).
        // requestId / turnNumber / turnStartMillis are declared here only because
        // turn_completed lives in the inner finally and Java needs the variables
        // in scope across the try/finally pair; the actual assignment happens
        // under the lock and the inner-finally emission is gated by
        // requestId != null so a throw before reaching the assignment (e.g. a
        // startMonitor failure) emits neither side.
        CronTool.setWorkspaceContext(session.projectPath().toString());
        CancelAwareStreamContext cancelContext = null;
        String requestId = null;
        int turnNumber = 0;
        long turnStartMillis = 0L;
        try {

        log.info("Streaming agent prompt: sessionId={}, promptLength={}", sessionId, prompt.length());

        recordPromptSkillCorrections(sessionId, session.projectPath(), prompt);

        // Convert session conversation history to LLM messages
        var conversationHistory = toMessages(session.messages());

        // Set up cancellation support: the CancelAwareStreamContext runs a monitor
        // thread that reads from the socket and dispatches agent.cancel and
        // permission.response messages accordingly.
        //
        // When the WebSocket bridge is enabled (issue #431), wrap the raw context
        // with an EventMultiplexer so every JSON-RPC notification fans out to all
        // connected browser clients in addition to the per-request CLI socket.
        var cancellationToken = new CancellationToken();
        var bridge = this.webSocketBridge;
        cancelContext = bridge != null
                ? new CancelAwareStreamContext(
                        context, new EventMultiplexer(context, bridge, sessionId),
                        cancellationToken, objectMapper, permissionRegistry)
                : new CancelAwareStreamContext(context, cancellationToken, objectMapper,
                        permissionRegistry);

        // Create a StreamEventHandler that forwards events via the cancel-aware context
        var eventHandler = new StreamingNotificationHandler(cancelContext, objectMapper);

        // Wait briefly for asynchronous MCP initialization so prompt assembly and
        // request-scoped tool execution both see the same registry contents.
        awaitMcpInitForRequest();

        var requestTools = snapshotCurrentTools();
        var requestToolNames = toolNames(requestTools);

        // #480 PR 3: stamp every capability check this turn with a single
        // PromptId — the root of the Provenance chain. Generated once here
        // so all tool invocations within this prompt share an identifier
        // (audit replay can group by it; sub-agent spawns inherit it).
        var promptId = new dev.aceclaw.security.ids.PromptId(
                "prompt-" + UUID.randomUUID());

        // Wrap tools with permission checking and hooks for this request
        var permissionAwareRegistry = createPermissionAwareRegistry(
                requestTools, cancelContext, sessionId, eventHandler, promptId);

        // Get or create a session-scoped metrics collector so tool stats accumulate across turns
        var metricsCollector = sessionMetrics.computeIfAbsent(sessionId, _ -> new ToolMetricsCollector());

        // Create watchdog timer with soft/hard limits for budget enforcement
        int effectiveHardTurns = maxAgentHardTurns > 0 ? maxAgentHardTurns : maxAgentTurns * 3;
        int effectiveHardWallTimeSec = maxAgentHardWallTimeSec > 0
                ? maxAgentHardWallTimeSec : maxAgentWallTimeSec * 3;
        boolean anyBudgetEnabled = maxAgentTurns > 0 || maxAgentWallTimeSec > 0
                || effectiveHardTurns > 0 || effectiveHardWallTimeSec > 0;
        var watchdog = anyBudgetEnabled
                ? new WatchdogTimer(
                        maxAgentTurns,
                        effectiveHardTurns,
                        maxAgentWallTimeSec > 0 ? Duration.ofSeconds(maxAgentWallTimeSec) : Duration.ZERO,
                        effectiveHardWallTimeSec > 0 ? Duration.ofSeconds(effectiveHardWallTimeSec) : Duration.ZERO,
                        cancellationToken)
                : null;

        // Get or create session-scoped doom loop and progress detectors
        var doomLoop = sessionDoomLoops.computeIfAbsent(sessionId, _ -> new DoomLoopDetector());
        var progress = sessionProgressDetectors.computeIfAbsent(sessionId, _ -> new ProgressDetector());

        var promptAssembly = assembleSystemPromptForRequest(sessionId, session, prompt, requestToolNames);
        var effectiveCompactor = createRequestCompactor(sessionId, promptAssembly.prompt());

        // Create a temporary agent loop with the permission-aware registry, compaction and metrics
        var agentConfig = AgentLoopConfig.builder()
                .sessionId(sessionId)
                .metricsCollector(metricsCollector)
                .maxIterations(maxIterations)
                .watchdog(watchdog)
                .doomLoopDetector(doomLoop)
                .progressDetector(progress)
                .retryConfig(retryConfig)
                .workspaceHash(ResumeRouter.hashWorkspace(session.projectPath()))
                .turnCheckpointSink(turnCheckpointSink)
                .build();
        var permissionAwareLoop = new StreamingAgentLoop(
                getLlmClient(), permissionAwareRegistry,
                getModelForSession(sessionId), promptAssembly.prompt(),
                maxTokens, thinkingBudget, contextWindowTokens, effectiveCompactor, agentConfig);

        // Acquire per-session turn lock (coordinates with DeferredActionScheduler)
        var turnLock = sessionTurnLocks.computeIfAbsent(sessionId, _ -> new ReentrantLock());
        turnLock.lock();
        // Captured for the stream.turn_completed broadcast so the dashboard
        // can render a "max_tokens" / "error" badge on truncated turns.
        // Hoisted above the try so the finally block can read it. Each
        // happy-path branch below overrides this with the Turn's actual
        // finalStopReason; the LlmException catch overrides to ERROR.
        String finalStopReason = "END_TURN";
        try {
            // Start the cancel monitor thread to read from the socket
            cancelContext.startMonitor();

            // ---- Lifecycle notifications (issue #439 + #431) ------------------
            // These fire per agent.prompt request and let browser dashboards
            // anchor a Turn-level node + render session boundaries without
            // inferring them from text-delta gaps. The CLI ignores them.
            //
            // Computed UNDER turnLock so concurrent agent.prompt calls on the
            // same session (e.g. two attached clients) see correctly ordered
            // turnNumbers — the lock serialises both the read of
            // session.messages() and the resulting emission. session_started
            // could safely emit outside (atomic CHM gate), but is kept here
            // for symmetry — there is one place that opens the turn.
            turnStartMillis = System.currentTimeMillis();
            requestId = "turn-" + UUID.randomUUID();
            // turnNumber = Nth user message in this session, matching the existing
            // AgentEvent.TurnStarted semantics in StreamingAgentLoop.
            turnNumber = (int) session.messages().stream()
                    .filter(m -> m instanceof AgentSession.ConversationMessage.User).count() + 1;
            // Browser-only: bypass cancelContext entirely so the CLI's UDS
            // protocol stays unchanged. emitBrowserOnly is a no-op when the
            // bridge is off, so disabled deployments emit nothing at all.
            if (sessionsStartedSignaled.add(sessionId)) {
                var p = objectMapper.createObjectNode();
                p.put("sessionId", sessionId);
                p.put("model", getModelForSession(sessionId));
                p.put("timestamp", Instant.now().toString());
                // Include projectPath so the dashboard sidebar can render
                // the session's directory immediately, instead of falling
                // back to "(unknown)" until the next sessions.list refresh
                // (issue #452 — particularly visible after a Ctrl+C double
                // cancel + new-session-in-same-dir, where the cancelled row
                // still showed the path while the new row read "(unknown)").
                p.put("projectPath", session.projectPath().toString());
                emitBrowserOnly(sessionId, "stream.session_started", p);
            }
            var ts = objectMapper.createObjectNode();
            ts.put("sessionId", sessionId);
            ts.put("requestId", requestId);
            ts.put("turnNumber", turnNumber);
            ts.put("timestamp", Instant.now().toString());
            emitBrowserOnly(sessionId, "stream.turn_started", ts);

            // Explicit /continue command: "继续", "continue", "resume", "/resume"
            // (case-insensitive, trim). Skips the resume.offer round-trip and
            // routes directly to either a plan or turn checkpoint (issue #501).
            if (isExplicitContinue(prompt) && planCheckpointStore != null) {
                var explicitResult = handleExplicitContinue(
                        sessionId, session, prompt, cancelContext, eventHandler,
                        permissionAwareLoop, cancellationToken, metricsCollector,
                        watchdog, requestToolNames, conversationHistory);
                finalStopReason = extractStopReason(explicitResult, finalStopReason);
                sendBudgetExhaustedNotificationIfNeeded(watchdog, cancelContext, sessionId, cancellationToken);
                return explicitResult;
            }

            // Check for resumable plan checkpoint before planning or execution
            if (planCheckpointStore != null) {
                var resumeResult = tryResumeFromCheckpoint(
                        sessionId, session, cancelContext, eventHandler,
                        permissionAwareLoop, cancellationToken, metricsCollector, watchdog, requestToolNames);
                if (resumeResult != null) {
                    finalStopReason = extractStopReason(resumeResult, finalStopReason);
                    sendBudgetExhaustedNotificationIfNeeded(watchdog, cancelContext, sessionId, cancellationToken);
                    return resumeResult;
                }
            }

            // Check if this task warrants upfront planning. Two paths
            // can trigger the planner:
            //   1. forcePlan param (from CLI's /plan slash command) —
            //      user explicitly asked for planning, skip the
            //      heuristic. plannerEnabled still gates this so a
            //      daemon configured with planner turned off entirely
            //      still respects that.
            //   2. ComplexityEstimator heuristic — score >= threshold.
            if (plannerEnabled) {
                boolean shouldPlan = forcePlan;
                if (forcePlan) {
                    log.info("Forced planning requested via forcePlan=true, generating plan");
                } else {
                    var estimator = new ComplexityEstimator(plannerThreshold);
                    var complexityScore = estimator.estimate(prompt);
                    shouldPlan = complexityScore.shouldPlan();
                    if (shouldPlan) {
                        log.info("Complex task detected (score={}, signals={}), generating plan",
                                complexityScore.score(), complexityScore.signals());
                    }
                }
                if (shouldPlan) {
                    var planResult = executePlannedPrompt(prompt, session, sessionId, cancelContext,
                            eventHandler, permissionAwareLoop, cancellationToken,
                            metricsCollector, watchdog, requestToolNames);
                    finalStopReason = extractStopReason(planResult, finalStopReason);
                    sendBudgetExhaustedNotificationIfNeeded(watchdog, cancelContext, sessionId, cancellationToken);
                    return planResult;
                }
            }

            var adaptive = runTurnWithAdaptiveContinuation(
                    permissionAwareLoop, prompt, conversationHistory, eventHandler, cancellationToken);
            finalStopReason = adaptive.turn().finalStopReason().name();

            // Send cancelled / budget-exhausted notifications if applicable
            sendCancelledNotificationIfNeeded(cancellationToken, cancelContext, sessionId);
            sendBudgetExhaustedNotificationIfNeeded(watchdog, cancelContext, sessionId, cancellationToken);

            return buildTurnResult(adaptive.turn(), session, sessionId, prompt, cancellationToken, metricsCollector,
                    adaptive, requestToolNames);

        } catch (dev.aceclaw.core.llm.LlmException e) {
            // Translate LLM errors to user-friendly messages
            finalStopReason = "ERROR";
            log.error("LLM error during prompt: statusCode={}, message={}",
                    e.statusCode(), e.getMessage(), e);

            session.addMessage(new AgentSession.ConversationMessage.User(prompt));

            String userMessage = formatLlmError(e);
            throw new IllegalStateException(userMessage);
        } catch (RuntimeException | Error e) {
            // Catch-all for non-LLM failures: anything thrown after
            // requestId is assigned MUST be reported as ERROR on the
            // turn_completed broadcast so the dashboard's truncated-
            // turn badge fires. Without this branch the finally block
            // would emit the END_TURN default for an aborted turn.
            // Rethrow unchanged — error semantics elsewhere unchanged.
            finalStopReason = "ERROR";
            throw e;
        } finally {
            // Emit stream.turn_completed BEFORE releasing the lock so the
            // start→end window is fully serialised against other prompts on
            // the same session. If we unlocked first, a concurrent agent.prompt
            // on the same session could grab the lock and emit its own
            // turn_started between this request's unlock and turn_completed,
            // garbling event ordering on the browser dashboard.
            //
            // Browser-only: bypasses cancelContext, so the CLI never sees
            // turn_completed. Guarded by requestId != null so we never emit a
            // closer for a Turn we never opened — the assignment lives inside
            // this try block, so anything thrown before reaching it (e.g.
            // startMonitor failure) leaves requestId null and no notification
            // fires.
            if (requestId != null) {
                var tcp = objectMapper.createObjectNode();
                tcp.put("sessionId", sessionId);
                tcp.put("requestId", requestId);
                tcp.put("turnNumber", turnNumber);
                tcp.put("durationMs", System.currentTimeMillis() - turnStartMillis);
                tcp.put("toolCount", cancelContext.toolUseCount());
                tcp.put("timestamp", Instant.now().toString());
                // The dashboard renders a small badge on truncated turns
                // (MAX_TOKENS / ERROR / etc) so the user can tell at a glance
                // why a turn ended without the usual final-text response.
                tcp.put("stopReason", finalStopReason);
                emitBrowserOnly(sessionId, "stream.turn_completed", tcp);
            }
            if (watchdog != null) {
                watchdog.close();
            }
            cancelContext.stopMonitor();
            turnLock.unlock();

        }

        } finally {
            CronTool.clearWorkspaceContext();
        }
    }

    /**
     * Executes a complex prompt via the planner: generates a plan, streams it to the user,
     * then executes each step sequentially through the agent loop.
     */
    private Object executePlannedPrompt(
            String prompt, AgentSession session, String sessionId,
            StreamContext cancelContext, StreamEventHandler eventHandler,
            StreamingAgentLoop permissionAwareLoop, CancellationToken cancellationToken,
            ToolMetricsCollector metricsCollector, WatchdogTimer watchdog,
            Set<String> requestToolNames) throws Exception {

        // 1. Generate plan
        var planner = new LLMTaskPlanner(getLlmClient(), getModelForSession(sessionId));
        var toolDefs = toolRegistry.toDefinitions();

        // Capture the planner's own LLM request separately from step/replan attribution so
        // the final /usage payload can report PLANNER vs MAIN_TURN vs REPLAN distinctly.
        // PlanExecutionResult.requestAttribution() does NOT include this — it's issued
        // before the executor runs. See #419 PR A.2.
        var plannerAttribution = RequestAttribution.builder();
        TaskPlan plan;
        try {
            plan = planner.plan(prompt, toolDefs, plannerAttribution);
        } catch (Exception e) {
            log.warn("Plan generation failed, falling back to direct execution: {}", e.getMessage());
            // Fall back to direct execution
            var conversationHistory = toMessages(session.messages());
            var adaptive = runTurnWithAdaptiveContinuation(
                    permissionAwareLoop, prompt, conversationHistory, eventHandler, cancellationToken);
            sendCancelledNotificationIfNeeded(cancellationToken, cancelContext, sessionId);
            return buildTurnResult(adaptive.turn(), session, sessionId, prompt, cancellationToken, metricsCollector,
                    adaptive, requestToolNames);
        }

        log.info("Plan generated: {} steps for goal: {}", plan.steps().size(),
                truncate(prompt, 80));

        // 2. Send plan_created notification to client
        try {
            var params = objectMapper.createObjectNode();
            params.put("planId", plan.planId());
            params.put("stepCount", plan.steps().size());
            params.put("goal", truncate(prompt, 200));
            var stepsArray = objectMapper.createArrayNode();
            for (int i = 0; i < plan.steps().size(); i++) {
                var step = plan.steps().get(i);
                var stepNode = objectMapper.createObjectNode();
                stepNode.put("index", i + 1);
                stepNode.put("name", step.name());
                stepNode.put("description", step.description());
                stepsArray.add(stepNode);
            }
            params.set("steps", stepsArray);
            cancelContext.sendNotification("stream.plan_created", params);
        } catch (IOException e) {
            log.warn("Failed to send plan_created notification: {}", e.getMessage());
        }

        // 3. Execute the plan
        var conversationHistory = toMessages(session.messages());
        // Track the running plan step so stream.tool_use notifications can
        // carry an explicit parentStepId (#485 PR 3/3). null when eventHandler
        // is not the production StreamingNotificationHandler (e.g. test
        // doubles) — in that case, parentStepId tagging is silently skipped.
        StreamingNotificationHandler streamHandler =
                eventHandler instanceof StreamingNotificationHandler h ? h : null;
        var listener = new SequentialPlanExecutor.PlanEventListener() {
            @Override
            public void onStepStarted(PlannedStep step, int stepIndex, int totalSteps) {
                if (streamHandler != null) {
                    // Composed form mirrors the dashboard's stepNodeId(planId, 1-based)
                    // so the reducer can look up the step node by id directly.
                    streamHandler.setCurrentStepId(plan.planId() + ":step:" + (stepIndex + 1));
                }
                try {
                    var p = objectMapper.createObjectNode();
                    p.put("planId", plan.planId());
                    p.put("stepId", step.stepId());
                    p.put("stepIndex", stepIndex + 1);
                    p.put("totalSteps", totalSteps);
                    p.put("stepName", step.name());
                    cancelContext.sendNotification("stream.plan_step_started", p);
                } catch (IOException e) {
                    log.warn("Failed to send plan_step_started notification: {}", e.getMessage());
                }
            }

            @Override
            public void onStepCompleted(PlannedStep step, int stepIndex, StepResult result) {
                try {
                    var p = objectMapper.createObjectNode();
                    p.put("planId", plan.planId());
                    p.put("stepId", step.stepId());
                    p.put("stepIndex", stepIndex + 1);
                    p.put("stepName", step.name());
                    p.put("success", result.success());
                    p.put("durationMs", result.durationMs());
                    p.put("tokensUsed", result.tokensUsed());
                    cancelContext.sendNotification("stream.plan_step_completed", p);
                } catch (IOException e) {
                    log.warn("Failed to send plan_step_completed notification: {}", e.getMessage());
                }
            }

            @Override
            public void onPlanCompleted(TaskPlan completedPlan, boolean success, long totalDurationMs) {
                if (streamHandler != null) {
                    streamHandler.setCurrentStepId(null);
                }
                try {
                    var p = objectMapper.createObjectNode();
                    p.put("planId", completedPlan.planId());
                    p.put("success", success);
                    p.put("totalDurationMs", totalDurationMs);
                    p.put("stepsCompleted", completedPlan.completedSteps());
                    p.put("totalSteps", completedPlan.steps().size());
                    cancelContext.sendNotification("stream.plan_completed", p);
                } catch (IOException e) {
                    log.warn("Failed to send plan_completed notification: {}", e.getMessage());
                }
            }

            @Override
            public void onPlanReplanned(TaskPlan oldPlan, TaskPlan newPlan, int attempt, String rationale) {
                try {
                    var p = objectMapper.createObjectNode();
                    p.put("planId", newPlan.planId());
                    p.put("replanAttempt", attempt);
                    p.put("newStepCount", newPlan.steps().size());
                    p.put("rationale", rationale);
                    cancelContext.sendNotification("stream.plan_replanned", p);
                } catch (IOException e) {
                    log.warn("Failed to send plan_replanned notification: {}", e.getMessage());
                }
            }

            @Override
            public void onPlanEscalated(TaskPlan escalatedPlan, String reason) {
                if (streamHandler != null) {
                    streamHandler.setCurrentStepId(null);
                }
                try {
                    var p = objectMapper.createObjectNode();
                    p.put("planId", escalatedPlan.planId());
                    p.put("reason", reason);
                    cancelContext.sendNotification("stream.plan_escalated", p);
                } catch (IOException e) {
                    log.warn("Failed to send plan_escalated notification: {}", e.getMessage());
                }
            }

            @Override
            public void onStepFallback(PlannedStep step, int stepIndex,
                                       String fallbackApproach, int attempt) {
                // Browser-only: CLI does not consume plan_step_fallback today,
                // so route directly to the WS bridge instead of through
                // cancelContext.sendNotification.
                var p = objectMapper.createObjectNode();
                p.put("sessionId", sessionId);
                p.put("planId", plan.planId());
                p.put("stepId", step.stepId());
                p.put("stepIndex", stepIndex + 1);
                p.put("fallbackApproach", fallbackApproach);
                p.put("attempt", attempt);
                emitBrowserOnly(sessionId, "stream.plan_step_fallback", p);
            }
        };

        // Wrap listener with checkpointing if store is available
        SequentialPlanExecutor.PlanEventListener effectiveListener = listener;
        if (planCheckpointStore != null) {
            var wsHash = ResumeRouter.hashWorkspace(session.projectPath());
            var initialCheckpoint = new PlanCheckpoint(
                    plan.planId(), sessionId, wsHash, prompt, plan,
                    List.of(), -1, serializeConversation(session.messages()),
                    PlanCheckpoint.CheckpointStatus.ACTIVE, null, List.of(),
                    Instant.now(), Instant.now());
            try {
                planCheckpointStore.save(initialCheckpoint);
            } catch (Exception e) {
                log.warn("Failed to save initial plan checkpoint: {}", e.getMessage());
            }
            effectiveListener = new CheckpointingPlanEventListener(
                    listener, planCheckpointStore, initialCheckpoint, session, 0);
        }

        AdaptiveReplanner replanner = createReplannerIfEnabled(sessionId);
        var perStepWall = maxPlanStepWallTimeSec > 0
                ? Duration.ofSeconds(maxPlanStepWallTimeSec) : null;
        var totalPlanWall = maxPlanTotalWallTimeSec > 0
                ? Duration.ofSeconds(maxPlanTotalWallTimeSec) : null;
        var executor = new SequentialPlanExecutor(effectiveListener, replanner,
                watchdog, perStepWall, totalPlanWall);
        var planResult = executor.execute(plan, permissionAwareLoop, conversationHistory,
                eventHandler, cancellationToken);

        // Send cancelled notification if needed
        sendCancelledNotificationIfNeeded(cancellationToken, cancelContext, sessionId);

        // 4. Store messages in the session
        session.addMessage(new AgentSession.ConversationMessage.User(prompt));
        var responseSummary = buildPlanResponseSummary(planResult);
        if (!responseSummary.isEmpty()) {
            session.addMessage(new AgentSession.ConversationMessage.Assistant(responseSummary));
        }

        // 5. Log to journal
        if (dailyJournal != null) {
            dailyJournal.append("Planned task (" + planResult.plan().steps().size() + " steps, "
                    + (planResult.success() ? "success" : "failed") + "): "
                    + truncate(prompt, 100) + " | Tokens: " + planResult.totalTokensUsed());
        }

        var plannedStopReason = planResult.success() ? StopReason.END_TURN : StopReason.ERROR;
        schedulePostRequestLearning(
                sessionId,
                session.projectPath(),
                syntheticTurn(planResult, plannedStopReason),
                List.copyOf(session.messages()),
                metricsCollector != null ? metricsCollector.allMetrics() : Map.of(),
                requestToolNames);
        recordInjectedCandidateOutcomes(
                sessionId, planResult.success(), cancellationToken.isCancelled(), plannedStopReason);
        recordSkillOutcomes(
                sessionId,
                session.projectPath(),
                syntheticTurn(planResult, plannedStopReason),
                cancellationToken,
                null);
        int failedSteps = (int) planResult.stepResults().stream().filter(s -> !s.success()).count();
        boolean planFirstTry = planResult.success() && failedSteps == 0;
        // Fold the upfront PLANNER request into the plan's aggregated attribution before
        // reporting: planResult.requestAttribution() covers step turns + REPLAN calls; the
        // initial planner call happens above and must be merged in here so the per-source
        // map, the llmRequests scalar, and runtime metrics all stay consistent.
        var planUsage = RequestAttribution.builder()
                .merge(planResult.requestAttribution())
                .merge(plannerAttribution.build())
                .build();
        recordRuntimeMetrics(sessionId, planResult.success(), planFirstTry,
                failedSteps, plannedStopReason, metricsCollector, session.projectPath(),
                planUsage);

        // 6. Build result
        var result = objectMapper.createObjectNode();
        result.put("sessionId", sessionId);
        result.put("response", responseSummary);
        // Use the planner-derived stopReason (END_TURN on success, ERROR
        // on plan failure) instead of a hardcoded END_TURN — both the
        // CLI and the dashboard's stream.turn_completed broadcast read
        // this so mismatches confuse "did the plan really succeed?".
        result.put("stopReason", plannedStopReason.name());
        result.put("planned", true);
        result.put("planSuccess", planResult.success());
        result.put("planSteps", planResult.plan().steps().size());
        result.put("planStepsCompleted", planResult.plan().completedSteps());
        appendInjectedCandidateIds(result, sessionId);
        if (cancellationToken.isCancelled()) {
            result.put("cancelled", true);
        }

        int totalInput = planResult.stepResults().stream().mapToInt(StepResult::inputTokens).sum();
        int totalOutput = planResult.stepResults().stream().mapToInt(StepResult::outputTokens).sum();
        var usageNode = objectMapper.createObjectNode();
        usageNode.put("inputTokens", totalInput);
        usageNode.put("outputTokens", totalOutput);
        usageNode.put("totalTokens", planResult.totalTokensUsed());
        usageNode.put("llmRequests", planUsage.total());
        writeLlmRequestsBySource(usageNode, planUsage);
        result.set("usage", usageNode);

        log.info("Planned task complete: sessionId={}, success={}, steps={}/{}, tokens={}",
                sessionId, planResult.success(), planResult.plan().completedSteps(),
                planResult.plan().steps().size(), planResult.totalTokensUsed());

        return result;
    }

    /**
     * Writes the per-source breakdown of LLM requests onto the JSON-RPC usage node.
     *
     * <p>Keyed by the lowercase {@link dev.aceclaw.core.llm.RequestSource} name
     * ({@code "main_turn"}, {@code "planner"}, ...). Omitted when the attribution has
     * no recorded requests so old CLIs and empty payloads don't see a needless field.
     *
     * <p>Invariant: {@code sum(llmRequestsBySource.values()) == llmRequests}. The CLI can
     * rely on this when reconciling the scalar with the map.
     */
    private void writeLlmRequestsBySource(com.fasterxml.jackson.databind.node.ObjectNode usageNode,
                                          RequestAttribution attribution) {
        if (attribution == null || attribution.total() == 0) {
            return;
        }
        var bySourceNode = objectMapper.createObjectNode();
        attribution.bySource().forEach((source, count) ->
                bySourceNode.put(source.name().toLowerCase(Locale.ROOT), count));
        usageNode.set("llmRequestsBySource", bySourceNode);
    }

    /**
     * Builds the standard turn result object (shared between direct and fallback-from-plan paths).
     */
    private Object buildTurnResult(dev.aceclaw.core.agent.Turn turn, AgentSession session,
                                    String sessionId, String prompt,
                                    CancellationToken cancellationToken,
                                    ToolMetricsCollector metricsCollector,
                                    AdaptiveTurnResult adaptive,
                                    Set<String> requestToolNames) {

        // Handle compaction
        if (turn.wasCompacted()) {
            handleCompactionResult(session, turn.compactionResult());
        }

        // Store messages
        session.addMessage(new AgentSession.ConversationMessage.User(prompt));
        var responseText = turn.text();
        if (!responseText.isEmpty()) {
            session.addMessage(new AgentSession.ConversationMessage.Assistant(responseText));
        }

        // Journal
        if (dailyJournal != null) {
            logTurnToJournal(prompt, turn);
        }

        schedulePostRequestLearning(
                sessionId,
                session.projectPath(),
                turn,
                List.copyOf(session.messages()),
                metricsCollector != null ? metricsCollector.allMetrics() : Map.of(),
                requestToolNames);

        boolean turnSuccess = turn.finalStopReason() != StopReason.ERROR;
        recordInjectedCandidateOutcomes(
                sessionId, turnSuccess, cancellationToken.isCancelled(),
                turn.finalStopReason());
        recordSkillOutcomes(sessionId, session.projectPath(), turn, cancellationToken, adaptive);
        // Direct turn: firstTry = success without adaptive continuation segments
        boolean directFirstTry = turnSuccess && (adaptive == null || adaptive.continuationCount() == 0);
        int directRetryCount = adaptive != null ? adaptive.continuationCount() : 0;
        recordRuntimeMetrics(sessionId, turnSuccess, directFirstTry,
                directRetryCount, turn.finalStopReason(), metricsCollector, session.projectPath(),
                turn.requestAttribution());

        // Build result
        var result = objectMapper.createObjectNode();
        result.put("sessionId", sessionId);
        result.put("response", responseText);
        result.put("stopReason", turn.finalStopReason().name());
        appendInjectedCandidateIds(result, sessionId);
        if (cancellationToken.isCancelled()) {
            result.put("cancelled", true);
        }

        var usageNode = objectMapper.createObjectNode();
        usageNode.put("inputTokens", turn.totalUsage().inputTokens());
        usageNode.put("outputTokens", turn.totalUsage().outputTokens());
        usageNode.put("totalTokens", turn.totalUsage().totalTokens());
        usageNode.put("llmRequests", turn.llmRequestCount());
        writeLlmRequestsBySource(usageNode, turn.requestAttribution());
        result.set("usage", usageNode);

        if (turn.wasCompacted()) {
            result.put("compacted", true);
            result.put("compactionPhase", turn.compactionResult().phaseReached().name());
        }
        if (adaptive != null) {
            var continuationNode = objectMapper.createObjectNode();
            continuationNode.put("enabled", true);
            continuationNode.put("segment_index", adaptive.segmentIndex());
            continuationNode.put("continuation_count", adaptive.continuationCount());
            continuationNode.put("reason", adaptive.reason());
            continuationNode.put("stopped_by_budget", adaptive.stoppedByBudget());
            result.set("continuation", continuationNode);

            var metricsNode = objectMapper.createObjectNode();
            metricsNode.put("turns_used", adaptive.segmentIndex());
            metricsNode.put("continuation_count", adaptive.continuationCount());
            metricsNode.put("no_progress_stops", "no_progress_stop".equals(adaptive.reason()) ? 1 : 0);
            result.set("metrics", metricsNode);
        }

        log.info("Streaming turn complete: sessionId={}, stopReason={}, tokens={}, cancelled={}, compacted={}",
                sessionId, turn.finalStopReason(), turn.totalUsage().totalTokens(),
                cancellationToken.isCancelled(), turn.wasCompacted());

        return result;
    }

    private void schedulePostRequestLearning(String sessionId,
                                             Path projectPath,
                                             dev.aceclaw.core.agent.Turn turn,
                                             List<AgentSession.ConversationMessage> sessionHistory,
                                             Map<String, ToolMetrics> metricsSnapshot,
                                             Set<String> requestToolNames) {
        if ((selfImprovementEngine == null && dynamicSkillGenerator == null)
                || turn == null || turn.newMessages().isEmpty()) {
            return;
        }

        var historyRef = List.copyOf(sessionHistory);
        var metricsRef = metricsSnapshot == null ? Map.<String, ToolMetrics>of() : Map.copyOf(metricsSnapshot);
        var toolNamesRef = requestToolNames == null ? Set.<String>of() : Set.copyOf(requestToolNames);

        sessionPostProcessing.compute(sessionId, (ignored, previous) -> {
            var base = previous == null
                    ? CompletableFuture.<Void>completedFuture(null)
                    : previous.exceptionally(error -> null);
            var next = base.thenRunAsync(
                    () -> runPostRequestLearning(sessionId, projectPath, turn, historyRef, metricsRef, toolNamesRef),
                    command -> Thread.ofVirtual().name("post-learn-" + shortenSessionId(sessionId)).start(command));
            next.whenComplete((unused, error) -> sessionPostProcessing.remove(sessionId, next));
            return next;
        });
    }

    // Package-private for testing: locks the caller-side contract that persist() is invoked
    // on every post-request cycle, including when insights is empty. See #411.
    void runPostRequestLearning(String sessionId,
                                        Path projectPath,
                                        dev.aceclaw.core.agent.Turn turn,
                                        List<AgentSession.ConversationMessage> sessionHistory,
                                        Map<String, ToolMetrics> metricsSnapshot,
                                        Set<String> requestToolNames) {
        var insights = List.<dev.aceclaw.memory.Insight>of();
        if (selfImprovementEngine != null) {
            try {
                insights = selfImprovementEngine.analyze(turn, sessionHistory, metricsSnapshot);
                // Always call persist(), even with an empty insights list. persist() carries the
                // draft re-evaluation trigger that refreshes the validation snapshot and reruns
                // the release pipeline. Skipping persist on empty insights meant trivial turns
                // (e.g. a "hi" with no extractable insights) left the validation snapshot stale,
                // which silently blocked promotion indicators from advancing.
                int persisted = selfImprovementEngine.persist(insights, sessionId, projectPath);
                if (!insights.isEmpty()) {
                    log.debug("Self-improvement: {} insights analyzed, {} persisted (session={})",
                            insights.size(), persisted, sessionId);
                }
            } catch (Exception e) {
                log.warn("Self-improvement analysis failed: {}", e.getMessage());
            }
        }
        if (dynamicSkillGenerator != null) {
            try {
                dynamicSkillGenerator.maybeGenerate(
                        sessionId, projectPath, turn, sessionHistory, insights, requestToolNames);
            } catch (Exception e) {
                log.warn("Dynamic runtime skill generation failed: {}", e.getMessage());
            }
        }
    }

    private dev.aceclaw.core.agent.Turn syntheticTurn(PlanExecutionResult planResult, StopReason stopReason) {
        int totalInput = planResult.stepResults().stream().mapToInt(StepResult::inputTokens).sum();
        int totalOutput = planResult.stepResults().stream().mapToInt(StepResult::outputTokens).sum();
        int totalLlmRequests = planResult.stepResults().stream().mapToInt(StepResult::llmRequestCount).sum();
        return new dev.aceclaw.core.agent.Turn(planResult.messages(), stopReason,
                new dev.aceclaw.core.llm.Usage(totalInput, totalOutput), totalLlmRequests);
    }

    private static String shortenSessionId(String sessionId) {
        return sessionId.length() > 8 ? sessionId.substring(0, 8) : sessionId;
    }

    /**
     * Reads the {@code stopReason} field from a planner / resume result
     * payload so the {@code stream.turn_completed} broadcast reflects
     * the real plan outcome (END_TURN on success, ERROR on failure)
     * instead of the END_TURN default. Falls back to {@code current}
     * when the result isn't a JSON object or doesn't carry the field —
     * older code paths that haven't been updated keep working.
     */
    private static String extractStopReason(Object result, String current) {
        if (result instanceof com.fasterxml.jackson.databind.node.ObjectNode obj
                && obj.has("stopReason")) {
            return obj.get("stopReason").asText(current);
        }
        return current;
    }

    private void recordPromptSkillCorrections(String sessionId, Path projectPath, String prompt) {
        var recent = sessionRecentSuccessfulSkills.remove(sessionId);
        if (recent == null || recent.isEmpty() || !SessionEndExtractor.looksLikeCorrection(prompt)) {
            return;
        }

        var tracker = projectSkillTrackers.computeIfAbsent(projectPath, skillMetricsStore::load);
        String correction = truncate(prompt, 200);
        for (var skillName : recent) {
            var outcome = new SkillOutcome.UserCorrected(Instant.now(), correction);
            recordSkillOutcomeAtomically(projectPath, tracker, skillName, outcome);
            recordRuntimeSkillOutcome(sessionId, projectPath, skillName, outcome);
        }
    }

    private void recordSkillOutcomes(
            String sessionId,
            Path projectPath,
            dev.aceclaw.core.agent.Turn turn,
            CancellationToken cancellationToken,
            AdaptiveTurnResult adaptive) {
        if (turn == null || turn.newMessages().isEmpty()) {
            sessionRecentSuccessfulSkills.remove(sessionId);
            return;
        }

        var skillInvocations = extractSkillInvocations(turn);
        if (skillInvocations.isEmpty()) {
            sessionRecentSuccessfulSkills.remove(sessionId);
            return;
        }

        int turnsUsed = adaptive != null ? Math.max(1, adaptive.segmentIndex()) : 1;
        Instant now = Instant.now();
        var tracker = projectSkillTrackers.computeIfAbsent(projectPath, skillMetricsStore::load);
        var successfulSkills = new ArrayList<String>();

        for (var invocation : skillInvocations) {
            SkillOutcome outcome;
            if (invocation.isError()) {
                outcome = new SkillOutcome.Failure(now, truncate(invocation.resultContent(), 200));
            } else if (cancellationToken.isCancelled()) {
                outcome = new SkillOutcome.Failure(now, "turn cancelled");
            } else if (turn.finalStopReason() == StopReason.ERROR) {
                outcome = new SkillOutcome.Failure(now, "turn ended with error");
            } else if (turn.budgetExhausted()) {
                outcome = new SkillOutcome.Failure(now,
                        "budget exhausted: " + (turn.budgetExhaustionReason() == null
                                ? "unknown" : turn.budgetExhaustionReason()));
            } else {
                outcome = new SkillOutcome.Success(now, turnsUsed);
                successfulSkills.add(invocation.skillName());
            }
            recordSkillOutcomeAtomically(projectPath, tracker, invocation.skillName(), outcome);
            recordRuntimeSkillOutcome(sessionId, projectPath, invocation.skillName(), outcome);
        }

        if (successfulSkills.isEmpty()) {
            sessionRecentSuccessfulSkills.remove(sessionId);
        } else {
            sessionRecentSuccessfulSkills.put(sessionId, List.copyOf(successfulSkills));
        }
    }

    private void recordSkillOutcomeAtomically(Path projectPath,
                                              SkillOutcomeTracker tracker,
                                              String skillName,
                                              SkillOutcome outcome) {
        SkillOutcomeSnapshot snapshot;
        var lock = skillOutcomeLocks.computeIfAbsent(skillOutcomeLockKey(projectPath, skillName), _ -> new ReentrantLock());
        lock.lock();
        try {
            tracker.record(skillName, outcome);
            var metrics = persistSkillMetrics(projectPath, skillName, tracker);
            emitSkillMemoryFeedback(projectPath, skillName, metrics, outcome);
            snapshot = metrics == null
                    ? null
                    : new SkillOutcomeSnapshot(tracker.outcomes(skillName));
        } finally {
            lock.unlock();
        }
        maybeRefineSkill(projectPath, skillName, tracker, snapshot);
    }

    private void recordRuntimeSkillOutcome(String sessionId,
                                           Path projectPath,
                                           String skillName,
                                           SkillOutcome outcome) {
        if (dynamicSkillGenerator == null) {
            return;
        }
        try {
            dynamicSkillGenerator.onOutcome(sessionId, projectPath, skillName, outcome);
        } catch (Exception e) {
            log.warn("Failed to update runtime skill governance for {}: {}", skillName, e.getMessage());
        }
    }

    private SkillMetrics persistSkillMetrics(Path projectPath, String skillName, SkillOutcomeTracker tracker) {
        var metrics = tracker.getMetrics(skillName).orElse(null);
        if (metrics == null) {
            return null;
        }
        try {
            skillMetricsStore.persist(projectPath, skillName, tracker, metrics);
        } catch (Exception e) {
            log.warn("Failed to persist skill metrics for {}: {}", skillName, e.getMessage());
        }
        return metrics;
    }

    private void emitSkillMemoryFeedback(Path projectPath,
                                         String skillName,
                                         SkillMetrics metrics,
                                         SkillOutcome outcome) {
        if (skillMemoryFeedback == null || metrics == null) {
            return;
        }
        try {
            skillMemoryFeedback.onOutcome(skillName, outcome, metrics, projectPath);
        } catch (Exception e) {
            log.warn("Failed to write skill-memory feedback for {}: {}", skillName, e.getMessage());
        }
    }

    private void maybeRefineSkill(Path projectPath,
                                  String skillName,
                                  SkillOutcomeTracker tracker,
                                  SkillOutcomeSnapshot snapshot) {
        if (skillRefinementEngine == null || snapshot == null) {
            return;
        }
        try {
            var initialPlan = skillRefinementEngine.prepare(projectPath, skillName, snapshot.outcomes());
            if (initialPlan.action() == SkillRefinementEngine.RefinementAction.NONE) {
                return;
            }

            SkillRefinementEngine.SkillRefinement proposal = null;
            if (initialPlan.action() == SkillRefinementEngine.RefinementAction.REFINED) {
                proposal = skillRefinementEngine.proposeRefinement(initialPlan, snapshot.outcomes());
            }

            var lock = skillOutcomeLocks.computeIfAbsent(skillOutcomeLockKey(projectPath, skillName), _ -> new ReentrantLock());
            SkillRefinementEngine.RefinementOutcome outcome;
            lock.lock();
            try {
                var currentOutcomes = tracker.outcomes(skillName);
                var currentPlan = skillRefinementEngine.prepare(projectPath, skillName, currentOutcomes);
                if (!sameAction(currentPlan.action(), initialPlan.action())) {
                    return;
                }
                outcome = skillRefinementEngine.apply(projectPath, tracker, currentPlan, proposal);
            } finally {
                lock.unlock();
            }

            if (outcome.action() != SkillRefinementEngine.RefinementAction.NONE) {
                emitSkillRollbackFeedback(projectPath, skillName, outcome);
                if (learningExplanationRecorder != null) {
                    learningExplanationRecorder.recordRefinement(
                            projectPath,
                            skillName,
                            outcome.action().name(),
                            outcome.reason());
                }
                if (learningValidationRecorder != null) {
                    learningValidationRecorder.recordRefinementValidation(
                            projectPath,
                            skillName,
                            outcome.action(),
                            outcome.reason());
                }
                log.info("Skill refinement action={} skill={} reason={}",
                        outcome.action(), skillName, outcome.reason());
            }
        } catch (Exception e) {
            log.warn("Failed to refine skill {}: {}", skillName, e.getMessage());
        }
    }

    private void emitSkillRollbackFeedback(Path projectPath,
                                           String skillName,
                                           SkillRefinementEngine.RefinementOutcome outcome) {
        if (skillMemoryFeedback == null
                || outcome.action() != SkillRefinementEngine.RefinementAction.ROLLED_BACK) {
            return;
        }
        try {
            skillMemoryFeedback.onRollback(skillName, outcome.reason(), projectPath);
        } catch (Exception e) {
            log.warn("Failed to write rollback feedback for {}: {}", skillName, e.getMessage());
        }
    }

    private static boolean sameAction(SkillRefinementEngine.RefinementAction left,
                                      SkillRefinementEngine.RefinementAction right) {
        return left == right
                || (left == SkillRefinementEngine.RefinementAction.STABILIZED
                && right == SkillRefinementEngine.RefinementAction.STABILIZED);
    }

    private static String skillOutcomeLockKey(Path projectPath, String skillName) {
        String projectKey = projectPath == null ? "<null>" : projectPath.toAbsolutePath().normalize().toString();
        return projectKey + "::" + skillName;
    }

    private record SkillOutcomeSnapshot(List<SkillOutcome> outcomes) {}

    private List<SkillInvocationResult> extractSkillInvocations(dev.aceclaw.core.agent.Turn turn) {
        var skillNamesByToolUseId = new java.util.LinkedHashMap<String, String>();
        var toolResultsById = new java.util.LinkedHashMap<String, ContentBlock.ToolResult>();

        for (var message : turn.newMessages()) {
            switch (message) {
                case Message.AssistantMessage assistant -> {
                    for (var block : assistant.content()) {
                        if (block instanceof ContentBlock.ToolUse toolUse && "skill".equals(toolUse.name())) {
                            String skillName = extractInvokedSkillName(toolUse.inputJson());
                            if (skillName != null && !skillName.isBlank()) {
                                skillNamesByToolUseId.put(toolUse.id(), skillName);
                            }
                        }
                    }
                }
                case Message.UserMessage user -> {
                    for (var block : user.content()) {
                        if (block instanceof ContentBlock.ToolResult toolResult) {
                            toolResultsById.put(toolResult.toolUseId(), toolResult);
                        }
                    }
                }
            }
        }

        var results = new ArrayList<SkillInvocationResult>();
        for (var entry : skillNamesByToolUseId.entrySet()) {
            var toolResult = toolResultsById.get(entry.getKey());
            if (toolResult == null) {
                continue;
            }
            results.add(new SkillInvocationResult(
                    entry.getValue(),
                    toolResult.isError(),
                    toolResult.content()));
        }
        return List.copyOf(results);
    }

    private String extractInvokedSkillName(String inputJson) {
        try {
            var node = objectMapper.readTree(inputJson);
            return node.path("name").asText("");
        } catch (Exception e) {
            return "";
        }
    }

    private AdaptiveTurnResult runTurnWithAdaptiveContinuation(
            StreamingAgentLoop loop,
            String userPrompt,
            List<Message> initialConversation,
            StreamEventHandler handler,
            CancellationToken cancellationToken) throws LlmException {
        var conversation = new ArrayList<>(initialConversation);
        var mergedMessages = new ArrayList<Message>();
        int totalInput = 0;
        int totalOutput = 0;
        int totalCacheCreate = 0;
        int totalCacheRead = 0;
        int totalLlmRequests = 0;
        // Accumulate per-source attribution across segments so the merged Turn carries a
        // faithful breakdown. Without this, writeLlmRequestsBySource at the buildTurnResult
        // boundary would see empty attribution on any multi-segment turn, dropping the
        // whole point of the per-source map on /status for exactly the turns that ran long
        // enough to need it. See #419 PR B review.
        var totalAttribution = RequestAttribution.builder();
        String reason = "single_segment";
        int segments = 0;
        int continuationCount = 0;
        int noProgressStreak = 0;
        String prevSignature = null;
        boolean stoppedByBudget = false;
        boolean maxIterationsReached = false;
        boolean budgetExhausted = false;
        String budgetExhaustionReason = null;
        CompactionResult lastCompaction = null;
        StopReason lastStopReason = StopReason.END_TURN;
        long startMillis = System.currentTimeMillis();
        String prompt = userPrompt;

        int maxSegments = adaptiveContinuationEnabled ? adaptiveContinuationMaxSegments : 1;
        for (int segment = 1; segment <= maxSegments; segment++) {
            if (cancellationToken != null && cancellationToken.isCancelled()) {
                reason = "cancelled";
                break;
            }
            var turn = loop.runTurn(prompt, conversation, handler, cancellationToken);
            segments = segment;
            continuationCount = Math.max(0, segment - 1);
            lastStopReason = turn.finalStopReason();
            maxIterationsReached = turn.maxIterationsReached();
            if (turn.wasCompacted()) {
                lastCompaction = turn.compactionResult();
            }
            mergedMessages.addAll(turn.newMessages());
            totalInput += turn.totalUsage().inputTokens();
            totalOutput += turn.totalUsage().outputTokens();
            totalCacheCreate += turn.totalUsage().cacheCreationInputTokens();
            totalCacheRead += turn.totalUsage().cacheReadInputTokens();
            totalLlmRequests += turn.llmRequestCount();
            totalAttribution.merge(turn.requestAttribution());
            conversation.addAll(turn.newMessages());

            if (turn.budgetExhausted()) {
                budgetExhausted = true;
                budgetExhaustionReason = turn.budgetExhaustionReason();
                stoppedByBudget = true;
                reason = "watchdog_" + turn.budgetExhaustionReason();
                break;
            }
            if (cancellationToken != null && cancellationToken.isCancelled()) {
                reason = "cancelled";
                break;
            }

            String signature = normalizeSignature(turn.text());
            if (!signature.isEmpty() && signature.equals(prevSignature)) {
                noProgressStreak++;
            } else if (!signature.isEmpty()) {
                noProgressStreak = 0;
                prevSignature = signature;
            }

            if (!adaptiveContinuationEnabled) {
                reason = "adaptive_disabled";
                break;
            }
            if (!turn.maxIterationsReached() && turn.finalStopReason() != StopReason.MAX_TOKENS) {
                reason = "segment_complete";
                break;
            }
            if (adaptiveContinuationMaxTotalTokens > 0
                    && (totalInput + totalOutput) >= adaptiveContinuationMaxTotalTokens) {
                reason = "token_budget_exhausted";
                stoppedByBudget = true;
                break;
            }
            if (adaptiveContinuationMaxWallClockSeconds > 0
                    && (System.currentTimeMillis() - startMillis) >= adaptiveContinuationMaxWallClockSeconds * 1000L) {
                reason = "wall_clock_budget_exhausted";
                stoppedByBudget = true;
                break;
            }
            if (noProgressStreak >= adaptiveContinuationNoProgressThreshold) {
                reason = "no_progress_stop";
                stoppedByBudget = true;
                break;
            }
            if (segment >= maxSegments) {
                reason = "max_segments_reached";
                stoppedByBudget = true;
                break;
            }
            prompt = """
                    Continue the current task from where you stopped.
                    Do not restart from scratch.
                    Focus on the next concrete action and complete remaining steps.
                    """;
        }

        var usage = new dev.aceclaw.core.llm.Usage(totalInput, totalOutput, totalCacheCreate, totalCacheRead);
        var mergedTurn = new dev.aceclaw.core.agent.Turn(
                mergedMessages, lastStopReason, usage, lastCompaction, maxIterationsReached,
                budgetExhausted, budgetExhaustionReason, totalLlmRequests,
                totalAttribution.build());
        return new AdaptiveTurnResult(
                mergedTurn,
                segments <= 0 ? 1 : segments,
                continuationCount,
                reason,
                stoppedByBudget);
    }

    private static String normalizeSignature(String text) {
        if (text == null) {
            return "";
        }
        return text.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
    }

    private record AdaptiveTurnResult(
            dev.aceclaw.core.agent.Turn turn,
            int segmentIndex,
            int continuationCount,
            String reason,
            boolean stoppedByBudget
    ) {}

    private record SkillInvocationResult(
            String skillName,
            boolean isError,
            String resultContent
    ) {}

    /**
     * Builds a human-readable summary of a plan execution result.
     */
    private static String buildPlanResponseSummary(PlanExecutionResult planResult) {
        var sb = new StringBuilder();
        sb.append("Plan execution ").append(planResult.success() ? "completed" : "failed");
        sb.append(" (").append(planResult.plan().completedSteps())
                .append("/").append(planResult.plan().steps().size()).append(" steps).\n\n");

        for (int i = 0; i < planResult.stepResults().size(); i++) {
            var result = planResult.stepResults().get(i);
            var step = planResult.plan().steps().get(i);
            sb.append(result.success() ? "[OK]" : "[FAIL]")
                    .append(" Step ").append(i + 1).append(": ").append(step.name());
            if (result.output() != null && !result.output().isEmpty()) {
                var summary = result.output().length() > 150
                        ? result.output().substring(0, 150) + "..."
                        : result.output();
                sb.append(" - ").append(summary);
            }
            if (result.error() != null) {
                sb.append(" - Error: ").append(result.error());
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    /**
     * Sends a stream.cancelled notification to the client if the token is cancelled.
     */
    private void sendCancelledNotificationIfNeeded(CancellationToken token,
                                                    StreamContext context, String sessionId) {
        if (token != null && token.isCancelled()) {
            try {
                var params = objectMapper.createObjectNode();
                params.put("sessionId", sessionId);
                context.sendNotification("stream.cancelled", params);
            } catch (IOException e) {
                log.warn("Failed to send stream.cancelled notification: {}", e.getMessage());
            }
        }
    }

    /**
     * Sends a stream.budget_exhausted notification to the client if the watchdog budget was exhausted
     * or the soft limit was reached without progress (causing cancellation).
     */
    private void sendBudgetExhaustedNotificationIfNeeded(WatchdogTimer watchdog,
                                                          StreamContext context, String sessionId,
                                                          CancellationToken cancellationToken) {
        // Fire on hard exhaustion OR soft-limit stall stop (where exhaustionReason is not set
        // but the token was cancelled by StreamingAgentLoop due to no progress)
        boolean hardExhausted = watchdog != null && watchdog.isExhausted();
        boolean softStallStop = watchdog != null && !watchdog.isExhausted()
                && watchdog.isSoftLimitReached()
                && cancellationToken != null && cancellationToken.isCancelled();
        if (hardExhausted || softStallStop) {
            try {
                var params = objectMapper.createObjectNode();
                params.put("sessionId", sessionId);
                String reason = watchdog.exhaustionReason();
                if (reason == null) {
                    reason = softStallStop ? "soft_limit_stall" : "unknown";
                }
                params.put("reason", reason);
                params.put("elapsedMs", watchdog.elapsedMs());
                params.put("extensionCount", watchdog.extensionCount());
                params.put("softLimitReached", watchdog.isSoftLimitReached());
                context.sendNotification("stream.budget_exhausted", params);
            } catch (IOException e) {
                log.warn("Failed to send stream.budget_exhausted notification: {}", e.getMessage());
            }
        }
    }

    /**
     * Translates an {@link dev.aceclaw.core.llm.LlmException} into a user-friendly message
     * without exposing stack traces or internal details.
     */
    private static String formatLlmError(dev.aceclaw.core.llm.LlmException e) {
        int status = e.statusCode();
        String message = e.getMessage();
        String safeMessage = (message == null || message.isBlank()) ? "(no additional details)" : message;
        if (status == 401 && message != null && message.contains("api.responses.write")) {
            return "Authentication succeeded but token lacks required scope 'api.responses.write'. "
                    + "Use a full OpenAI API key or provider=openai-codex with a valid Codex OAuth token.";
        }
        if (status == 401) {
            return "Invalid API key. Please check your API key configuration in env vars or ~/.aceclaw/config.json.";
        } else if (status == 429) {
            return "Rate limit exceeded. Please wait a moment and try again.";
        } else if (status == 529) {
            return "The API is temporarily overloaded. Please try again shortly.";
        } else if (status >= 500 && status < 600) {
            return "The LLM service is temporarily unavailable (HTTP " + status + "). Please try again.";
        } else if (status == 400) {
            return "Bad request to LLM API: " + safeMessage;
        } else if (message != null && message.contains("not-configured")) {
            return "API key not configured. Set ANTHROPIC_API_KEY (or OPENAI_API_KEY) or add apiKey to ~/.aceclaw/config.json.";
        } else {
            return "LLM error: " + safeMessage;
        }
    }

    /**
     * Creates a ToolRegistry where each tool is wrapped with permission checking and hooks.
     * Tools that need user approval will use the StreamContext to ask the client.
     */
    private ToolRegistry createPermissionAwareRegistry(
            List<Tool> requestTools,
            CancelAwareStreamContext context,
            String sessionId,
            StreamEventHandler eventHandler,
            dev.aceclaw.security.ids.PromptId promptId) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(eventHandler, "eventHandler");
        Objects.requireNonNull(requestTools, "requestTools");
        Objects.requireNonNull(promptId, "promptId");
        // The current step id is held in StreamingNotificationHandler's
        // AtomicReference; the supplier reads through it lazily so a tool
        // call captures whichever step is active at the moment of the
        // permission check (not the moment the registry was constructed).
        java.util.function.Supplier<String> currentStepIdSupplier =
                (eventHandler instanceof StreamingNotificationHandler snh)
                        ? snh::getCurrentStepId
                        : () -> null;
        var registry = new ToolRegistry();
        var antiPatternGate = AntiPatternPreExecutionGate.fromStores(
                memoryStore,
                candidateStore,
                antiPatternGateFeedbackStore != null
                        ? antiPatternGateFeedbackStore
                        : AntiPatternPreExecutionGate.RuleFeedbackProvider.noop());
        // Prefer session's project path for hook cwd (each session may have a different working directory)
        var session = sessionManager.getSession(sessionId);
        if (session == null || session.projectPath() == null) {
            throw new IllegalStateException("Session project path is required for tool execution: " + sessionId);
        }
        Path sessionProject = session.projectPath().toAbsolutePath().normalize();
        String cwd = sessionProject.toString();
        // Build a session-scoped read/write pair so relative paths always resolve to this session project.
        var sessionWriteFileTool = new WriteFileTool(sessionProject);
        var sessionReadFileTool = new ReadFileTool(sessionProject, sessionWriteFileTool.readFiles());
        for (var tool : requestTools) {
            Tool effectiveTool = materializeSessionScopedTool(
                    tool, sessionProject, sessionReadFileTool, sessionWriteFileTool, sessionId, eventHandler);
            registry.register(new PermissionAwareTool(
                    effectiveTool, permissionManager, context, objectMapper,
                    hookExecutor, sessionId, cwd, antiPatternGate,
                    () -> getAntiPatternGateOverride(sessionId, effectiveTool.name()),
                    antiPatternGateFeedbackStore,
                    candidateStore,
                    runtimeMetricsExporter,
                    promptId,
                    currentStepIdSupplier));
        }
        return registry;
    }

    private Tool materializeSessionScopedTool(
            Tool original,
            Path sessionProject,
            ReadFileTool sessionReadFileTool,
            WriteFileTool sessionWriteFileTool,
            String sessionId,
            StreamEventHandler eventHandler) {
        Objects.requireNonNull(original, "original");
        Objects.requireNonNull(sessionProject, "sessionProject");
        Objects.requireNonNull(sessionReadFileTool, "sessionReadFileTool");
        Objects.requireNonNull(sessionWriteFileTool, "sessionWriteFileTool");
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(eventHandler, "eventHandler");
        return switch (original.name()) {
            case "read_file" -> sessionReadFileTool;
            case "write_file" -> sessionWriteFileTool;
            case "edit_file" -> new EditFileTool(sessionProject);
            case "bash" -> new BashExecTool(sessionProject);
            case "glob" -> new GlobSearchTool(sessionProject);
            case "grep" -> new GrepSearchTool(sessionProject);
            case "list_directory" -> new ListDirTool(sessionProject);
            case "web_fetch" -> new WebFetchTool(sessionProject);
            case "memory" -> memoryStore != null ? new MemoryTool(memoryStore, sessionProject) : original;
            case "applescript" -> new AppleScriptTool(sessionProject);
            case "screen_capture" -> new ScreenCaptureTool(sessionProject);
            case "skill" -> original instanceof SkillTool st ? st.forRequest(sessionId, eventHandler) : original;
            // #457: bind sessionId so SubAgentRunner's loop config carries
            // the parent's id; otherwise the sub-agent's permission check
            // fails-closed for every tool the user approved.
            case "task" -> original instanceof TaskTool tt ? tt.forRequest(sessionId) : original;
            case "defer_check" -> original instanceof dev.aceclaw.daemon.deferred.DeferCheckTool dct
                    ? dct.forSession(sessionId)
                    : original;
            default -> original;
        };
    }

    // Access helpers so the permission-aware loop can use the same LLM config.
    // These reach into the original agentLoop via reflection-free accessors.
    // Since StreamingAgentLoop does not expose these, we store them at construction time.

    private dev.aceclaw.core.llm.LlmClient llmClient;
    private String model;
    private final java.util.concurrent.ConcurrentHashMap<String, String> sessionModelOverrides =
            new java.util.concurrent.ConcurrentHashMap<>();
    private String systemPrompt;
    private int maxTokens = 16384;
    private int thinkingBudget = 10240;
    private int maxIterations = AgentLoopConfig.DEFAULT_MAX_ITERATIONS;
    private boolean adaptiveContinuationEnabled = true;
    private int adaptiveContinuationMaxSegments = 3;
    private int adaptiveContinuationNoProgressThreshold = 2;
    private int adaptiveContinuationMaxTotalTokens = 0;
    private int adaptiveContinuationMaxWallClockSeconds = 0;
    private MessageCompactor compactor;
    private AutoMemoryStore memoryStore;
    private DailyJournal dailyJournal;
    private MarkdownMemoryStore markdownStore;
    private Path workingDir;
    private String provider;
    private SystemPromptBudget systemPromptBudget = SystemPromptBudget.DEFAULT;
    private volatile CompletableFuture<Void> mcpInitFuture = CompletableFuture.completedFuture(null);
    private static final long MCP_REQUEST_TIMEOUT_MS = 5_000;
    private boolean hasBraveApiKey;
    private Function<String, String> skillDescriptionsProvider = ignored -> "";
    private int contextWindowTokens;
    private SelfImprovementEngine selfImprovementEngine;
    private HookExecutor hookExecutor;
    private CandidateStore candidateStore;
    private AntiPatternGateFeedbackStore antiPatternGateFeedbackStore;
    private volatile boolean candidateInjectionEnabled = true;
    private volatile int candidateInjectionMaxCount = 10;
    private volatile int candidateInjectionMaxTokens = 1200;
    private boolean plannerEnabled = true;
    private int plannerThreshold = 5;
    private dev.aceclaw.core.agent.RetryConfig retryConfig = dev.aceclaw.core.agent.RetryConfig.DEFAULT;
    private boolean adaptiveReplanEnabled = true;
    private int maxAgentTurns = 50;
    private int maxAgentWallTimeSec = 600;
    private int maxAgentHardTurns = 0;
    private int maxAgentHardWallTimeSec = 0;
    private int maxPlanStepWallTimeSec = 1800;
    private int maxPlanTotalWallTimeSec = 3600;
    private PlanCheckpointStore planCheckpointStore;
    private dev.aceclaw.core.planner.TurnCheckpointStore turnCheckpointStore;
    private dev.aceclaw.core.agent.TurnCheckpointSink turnCheckpointSink;
    private SkillMemoryFeedback skillMemoryFeedback;
    private SkillRefinementEngine skillRefinementEngine;
    private DynamicSkillGenerator dynamicSkillGenerator;
    private LearningExplanationRecorder learningExplanationRecorder;
    private LearningValidationRecorder learningValidationRecorder;

    /**
     * Sets the LLM configuration for permission-aware agent loop creation.
     * Must be called before registering with the router.
     */
    public void setLlmConfig(dev.aceclaw.core.llm.LlmClient llmClient, String model, String systemPrompt) {
        this.llmClient = llmClient;
        this.model = model;
        this.systemPrompt = systemPrompt;
        this.skillRefinementEngine = llmClient != null && model != null
                ? new SkillRefinementEngine(llmClient, model, skillMetricsStore)
                : null;
    }

    /**
     * Sets the token configuration for permission-aware agent loop creation.
     */
    public void setTokenConfig(int maxTokens, int thinkingBudget, int maxIterations) {
        setTokenConfig(maxTokens, thinkingBudget, maxIterations, 0);
    }

    public void setTokenConfig(int maxTokens, int thinkingBudget, int maxIterations, int contextWindowTokens) {
        this.maxTokens = maxTokens;
        this.thinkingBudget = thinkingBudget;
        this.maxIterations = Math.max(1, maxIterations);
        this.contextWindowTokens = Math.max(0, contextWindowTokens);
    }

    public int getContextWindowTokens() {
        return Math.max(0, contextWindowTokens);
    }

    public void setAdaptiveContinuationConfig(boolean enabled,
                                              int maxSegments,
                                              int noProgressThreshold,
                                              int maxTotalTokens,
                                              int maxWallClockSeconds) {
        this.adaptiveContinuationEnabled = enabled;
        this.adaptiveContinuationMaxSegments = Math.max(1, maxSegments);
        this.adaptiveContinuationNoProgressThreshold = Math.max(1, noProgressThreshold);
        this.adaptiveContinuationMaxTotalTokens = Math.max(0, maxTotalTokens);
        this.adaptiveContinuationMaxWallClockSeconds = Math.max(0, maxWallClockSeconds);
    }

    /**
     * Sets the message compactor for context compaction support.
     */
    public void setCompactor(MessageCompactor compactor) {
        this.compactor = compactor;
    }

    /**
     * Sets the auto-memory store for persisting context extracted during compaction.
     */
    public void setMemoryStore(AutoMemoryStore memoryStore, Path workingDir) {
        this.memoryStore = memoryStore;
        this.workingDir = workingDir;
        this.skillMemoryFeedback = memoryStore != null
                ? new SkillMemoryFeedback(memoryStore, learningExplanationRecorder)
                : null;
        if (workingDir != null) {
            this.antiPatternGateFeedbackStore = new AntiPatternGateFeedbackStore(workingDir);
        }
    }

    public void setLearningExplanationRecorder(LearningExplanationRecorder learningExplanationRecorder) {
        this.learningExplanationRecorder = learningExplanationRecorder;
        this.skillMemoryFeedback = memoryStore != null
                ? new SkillMemoryFeedback(memoryStore, learningExplanationRecorder)
                : null;
    }

    public void setLearningValidationRecorder(LearningValidationRecorder learningValidationRecorder) {
        this.learningValidationRecorder = learningValidationRecorder;
    }

    /**
     * Sets the daily journal for appending compaction events.
     */
    public void setDailyJournal(DailyJournal dailyJournal) {
        this.dailyJournal = dailyJournal;
    }

    public void setContextAssemblyConfig(MarkdownMemoryStore markdownStore,
                                         String provider,
                                         SystemPromptBudget systemPromptBudget,
                                         boolean hasBraveApiKey,
                                         Function<String, String> skillDescriptionsProvider) {
        this.markdownStore = markdownStore;
        this.provider = provider;
        this.systemPromptBudget = systemPromptBudget != null ? systemPromptBudget : SystemPromptBudget.DEFAULT;
        this.hasBraveApiKey = hasBraveApiKey;
        this.skillDescriptionsProvider = skillDescriptionsProvider != null
                ? sessionId -> {
                    var descriptions = skillDescriptionsProvider.apply(sessionId);
                    return descriptions != null ? descriptions : "";
                }
                : ignored -> "";
    }

    /**
     * Sets the MCP initialization future so request-time code can await readiness.
     */
    public void setMcpInitFuture(CompletableFuture<Void> mcpInitFuture) {
        this.mcpInitFuture = mcpInitFuture != null
                ? mcpInitFuture
                : CompletableFuture.completedFuture(null);
    }

    /**
     * Sets the retry configuration for transient API errors.
     */
    public void setRetryConfig(dev.aceclaw.core.agent.RetryConfig retryConfig) {
        this.retryConfig = retryConfig != null ? retryConfig : dev.aceclaw.core.agent.RetryConfig.DEFAULT;
    }

    /**
     * Awaits MCP initialization with a bounded timeout so request-time prompt assembly
     * and request-scoped tool registry creation observe a consistent view of available tools.
     */
    private void awaitMcpInitForRequest() {
        var future = this.mcpInitFuture;
        if (!future.isDone()) {
            try {
                future.get(MCP_REQUEST_TIMEOUT_MS, TimeUnit.MILLISECONDS);
                log.debug("MCP init completed within request timeout");
            } catch (TimeoutException e) {
                log.warn("MCP init not ready within {}ms; proceeding without MCP tools for this request. "
                                + "If this is the first startup or an MCP server is downloading dependencies, "
                                + "retrying the request after startup completes may help.",
                        MCP_REQUEST_TIMEOUT_MS);
            } catch (Exception e) {
                log.warn("MCP init failed: {}", e.getMessage());
            }
        }
    }

    /**
     * Returns an immutable snapshot of the currently registered tools.
     */
    private List<Tool> snapshotCurrentTools() {
        return List.copyOf(toolRegistry.all());
    }

    private Set<String> toolNames(List<Tool> tools) {
        return tools.stream()
                .map(Tool::name)
                .collect(Collectors.toUnmodifiableSet());
    }

    /**
     * Sets the self-improvement engine for post-turn learning analysis.
     */
    public void setSelfImprovementEngine(SelfImprovementEngine selfImprovementEngine) {
        this.selfImprovementEngine = selfImprovementEngine;
    }

    public void setDynamicSkillGenerator(DynamicSkillGenerator dynamicSkillGenerator) {
        this.dynamicSkillGenerator = dynamicSkillGenerator;
    }

    /**
     * Sets the hook executor for running lifecycle hooks (PreToolUse, PostToolUse, etc.).
     */
    public void setHookExecutor(HookExecutor hookExecutor) {
        this.hookExecutor = hookExecutor;
    }

    /**
     * Sets the candidate store for prompt injection of promoted candidates.
     */
    public void setCandidateStore(CandidateStore candidateStore) {
        this.candidateStore = candidateStore;
    }

    public void setRuntimeMetricsExporter(RuntimeMetricsExporter exporter) {
        this.runtimeMetricsExporter = exporter;
    }

    public void setInjectionAuditLog(dev.aceclaw.memory.InjectionAuditLog auditLog) {
        this.injectionAuditLog = auditLog;
    }

    public void setAntiPatternGateFeedbackStore(AntiPatternGateFeedbackStore store) {
        this.antiPatternGateFeedbackStore = store;
    }

    /**
     * Runtime kill-switch for candidate injection.
     */
    public void setCandidateInjectionEnabled(boolean enabled) {
        this.candidateInjectionEnabled = enabled;
    }

    /**
     * Sets the candidate injection configuration.
     *
     * @param maxCount max number of candidates to inject
     * @param maxTokens max token budget for injection
     */
    public void setCandidateInjectionConfig(int maxCount, int maxTokens) {
        this.candidateInjectionMaxCount = Math.max(0, maxCount);
        this.candidateInjectionMaxTokens = Math.max(0, maxTokens);
    }

    /**
     * Sets the planner configuration.
     *
     * @param enabled   whether the planner is enabled
     * @param threshold complexity score threshold for triggering planning
     */
    public void setPlannerConfig(boolean enabled, int threshold) {
        this.plannerEnabled = enabled;
        this.plannerThreshold = Math.max(0, threshold);
    }

    /**
     * Sets whether adaptive replanning is enabled.
     */
    public void setAdaptiveReplanEnabled(boolean enabled) {
        this.adaptiveReplanEnabled = enabled;
    }

    /**
     * Sets the watchdog timer configuration for budget enforcement with soft/hard limits.
     *
     * @param maxAgentTurns          soft turn limit (0 = disabled)
     * @param maxAgentWallTimeSec    soft wall-clock seconds (0 = disabled)
     * @param maxAgentHardTurns      hard turn ceiling (0 = use 3x soft)
     * @param maxAgentHardWallTimeSec hard wall-clock ceiling (0 = use 3x soft)
     */
    public void setWatchdogConfig(int maxAgentTurns, int maxAgentWallTimeSec,
                                   int maxAgentHardTurns, int maxAgentHardWallTimeSec) {
        this.maxAgentTurns = Math.max(0, maxAgentTurns);
        this.maxAgentWallTimeSec = Math.max(0, maxAgentWallTimeSec);
        this.maxAgentHardTurns = Math.max(0, maxAgentHardTurns);
        this.maxAgentHardWallTimeSec = Math.max(0, maxAgentHardWallTimeSec);
    }

    /**
     * Sets the per-step and total wall-clock budgets for multi-step plan execution.
     *
     * @param stepSec  max wall-clock seconds per plan step (0 = disabled)
     * @param totalSec max wall-clock seconds for the entire plan (0 = disabled)
     */
    public void setPlanBudgetConfig(int stepSec, int totalSec) {
        this.maxPlanStepWallTimeSec = Math.max(0, stepSec);
        this.maxPlanTotalWallTimeSec = Math.max(0, totalSec);
    }

    /**
     * Sets the plan checkpoint store for crash-safe plan progress persistence
     * and resume-from-checkpoint support.
     */
    public void setPlanCheckpointStore(PlanCheckpointStore planCheckpointStore) {
        this.planCheckpointStore = planCheckpointStore;
    }

    /**
     * Sets the per-iteration turn checkpoint sink for crash-safe non-plan turn
     * progress persistence (issue #500).
     */
    public void setTurnCheckpointSink(dev.aceclaw.core.agent.TurnCheckpointSink turnCheckpointSink) {
        this.turnCheckpointSink = turnCheckpointSink;
    }

    /**
     * Sets the turn checkpoint store for explicit-continue routing (issue #501).
     * The store is read-side — {@link ResumeRouter} queries it to find
     * resumable turns. Writes go through the {@link #setTurnCheckpointSink
     * TurnCheckpointSink} configured separately.
     */
    public void setTurnCheckpointStore(dev.aceclaw.core.planner.TurnCheckpointStore turnCheckpointStore) {
        this.turnCheckpointStore = turnCheckpointStore;
    }

    /**
     * Creates an AdaptiveReplanner if adaptive replan is enabled, else returns null.
     */
    private AdaptiveReplanner createReplannerIfEnabled(String sessionId) {
        if (!adaptiveReplanEnabled) {
            return null;
        }
        return new AdaptiveReplanner(getLlmClient(), getModelForSession(sessionId));
    }

    private dev.aceclaw.core.llm.LlmClient getLlmClient() {
        return llmClient;
    }

    private String getModel() {
        // When called from handlePrompt, the sessionId is on the call stack.
        // For the agent loop, we need a way to resolve per-session.
        // Default: return first override if only one session, else default model.
        // The handlePrompt method uses getModelForSession directly.
        return model;
    }

    /**
     * Returns the effective model for a specific session (override or default).
     */
    String getModelForSession(String sessionId) {
        var override = sessionModelOverrides.get(sessionId);
        return override != null ? override : model;
    }

    /**
     * Returns the current effective model (override or default).
     * For backward compatibility, returns the first session override if any, else default.
     */
    public String getEffectiveModel() {
        // If there's exactly one session override, return it for compatibility
        var values = sessionModelOverrides.values();
        if (values.size() == 1) {
            return values.iterator().next();
        }
        return model;
    }

    /**
     * Returns the effective model for a given session ID.
     */
    public String getEffectiveModel(String sessionId) {
        return getModelForSession(sessionId);
    }

    /**
     * Sets a per-session model override. Pass null to clear.
     */
    public void setModelOverride(String sessionId, String modelId) {
        if (modelId == null) {
            sessionModelOverrides.remove(sessionId);
        } else {
            sessionModelOverrides.put(sessionId, modelId);
        }
    }

    /**
     * Clears the model override for a session. Call on session close.
     */
    public void clearSessionOverride(String sessionId) {
        sessionModelOverrides.remove(sessionId);
        sessionInjectedCandidateIds.remove(sessionId);
        clearAllAntiPatternGateOverrides(sessionId);
    }

    /**
     * Clears the tool metrics collector for a session. Call on session close to free memory.
     */
    public void clearSessionMetrics(String sessionId) {
        java.util.Objects.requireNonNull(sessionId, "sessionId");
        awaitSessionPostProcessing(sessionId);
        sessionMetrics.remove(sessionId);
        sessionInjectedCandidateIds.remove(sessionId);
        sessionDoomLoops.remove(sessionId);
        sessionProgressDetectors.remove(sessionId);
        sessionPostProcessing.remove(sessionId);
        sessionRuntimePruneScheduled.remove(sessionId);
        sessionsStartedSignaled.remove(sessionId);
    }

    public void awaitSessionPostProcessing(String sessionId) {
        Objects.requireNonNull(sessionId, "sessionId");
        var future = sessionPostProcessing.get(sessionId);
        if (future == null) {
            return;
        }
        try {
            future.get(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Interrupted while waiting for post-request learning for {}", sessionId);
        } catch (TimeoutException e) {
            log.warn("Timed out waiting for post-request learning for {}", sessionId);
        } catch (ExecutionException e) {
            var cause = e.getCause();
            log.warn("Post-request learning failed for {}: {}",
                    sessionId,
                    cause != null ? cause.getMessage() : e.getMessage());
        }
    }

    /**
     * Returns an immutable snapshot of session tool metrics for historical indexing.
     */
    public Map<String, ToolMetrics> snapshotSessionMetrics(String sessionId) {
        java.util.Objects.requireNonNull(sessionId, "sessionId");
        var collector = sessionMetrics.get(sessionId);
        return collector == null ? Map.of() : collector.allMetrics();
    }

    public record AntiPatternGateOverrideStatus(
            String sessionId,
            String tool,
            boolean active,
            long ttlSecondsRemaining,
            String reason
    ) {}

    public void setAntiPatternGateOverride(String sessionId, String toolName, long ttlSeconds, String reason) {
        java.util.Objects.requireNonNull(sessionId, "sessionId");
        java.util.Objects.requireNonNull(toolName, "toolName");
        long ttl = Math.max(1L, ttlSeconds);
        String normalizedReason = (reason == null || reason.isBlank()) ? "manual override" : reason;
        antiPatternGateOverrides.put(
                antiPatternOverrideKey(sessionId, toolName),
                new AntiPatternGateOverride(Instant.now().plusSeconds(ttl), normalizedReason));
    }

    public AntiPatternGateOverrideStatus getAntiPatternGateOverride(String sessionId, String toolName) {
        java.util.Objects.requireNonNull(sessionId, "sessionId");
        java.util.Objects.requireNonNull(toolName, "toolName");
        String key = antiPatternOverrideKey(sessionId, toolName);
        var value = antiPatternGateOverrides.get(key);
        if (value == null) {
            return new AntiPatternGateOverrideStatus(sessionId, toolName, false, 0L, "");
        }
        long remaining = Duration.between(Instant.now(), value.expiresAt()).toSeconds();
        if (remaining <= 0) {
            antiPatternGateOverrides.remove(key, value);
            return new AntiPatternGateOverrideStatus(sessionId, toolName, false, 0L, "");
        }
        return new AntiPatternGateOverrideStatus(sessionId, toolName, true, remaining, value.reason());
    }

    public boolean clearAntiPatternGateOverride(String sessionId, String toolName) {
        java.util.Objects.requireNonNull(sessionId, "sessionId");
        java.util.Objects.requireNonNull(toolName, "toolName");
        return antiPatternGateOverrides.remove(antiPatternOverrideKey(sessionId, toolName)) != null;
    }

    private void clearAllAntiPatternGateOverrides(String sessionId) {
        String prefix = sessionId + '\u0000';
        antiPatternGateOverrides.keySet().removeIf(key -> key.startsWith(prefix));
    }

    private static String antiPatternOverrideKey(String sessionId, String toolName) {
        return sessionId + '\u0000' + toolName;
    }

    /**
     * Returns the configured default model.
     */
    public String getDefaultModel() {
        return model;
    }

    private SystemPromptLoader.RequestAssembly assembleSystemPromptForRequest(
            String sessionId, AgentSession session, String prompt, Set<String> requestToolNames) {
        if (session == null || session.projectPath() == null) {
            return new SystemPromptLoader.RequestAssembly(getSystemPrompt(sessionId), List.of(), List.of(), List.of());
        }
        if (dynamicSkillGenerator != null) {
            scheduleRuntimeSkillPrune(sessionId, session.projectPath());
        }
        var activePaths = inferActiveFilePaths(prompt, session.messages(), session.projectPath());
        var config = new CandidatePromptAssembler.Config(
                candidateInjectionEnabled,
                candidateInjectionMaxCount,
                candidateInjectionMaxTokens,
                Set.of());
        var assembly = SystemPromptLoader.assembleRequest(
                session.projectPath(),
                memoryStore,
                dailyJournal,
                markdownStore,
                getModelForSession(sessionId),
                provider,
                systemPromptBudget,
                requestToolNames,
                hasBraveApiKey,
                candidateStore,
                config,
                skillDescriptionsProvider.apply(sessionId),
                prompt,
                activePaths);
        if (assembly.injectedCandidateIds().isEmpty()) {
            sessionInjectedCandidateIds.remove(sessionId);
        } else {
            sessionInjectedCandidateIds.put(sessionId, assembly.injectedCandidateIds());
            // Record injection event for audit trail
            recordInjectionAuditEvent(sessionId, assembly.injectedCandidateIds(), prompt);
        }
        return assembly;
    }

    public SystemPromptLoader.ContextInspection inspectContext(String sessionId, String queryHint) {
        Objects.requireNonNull(sessionId, "sessionId");
        var session = sessionManager.getSession(sessionId);
        if (session == null) {
            throw new IllegalArgumentException("Session not found: " + sessionId);
        }
        if (!session.isActive()) {
            throw new IllegalArgumentException("Session is not active: " + sessionId);
        }
        String effectiveQuery = queryHint != null ? queryHint : "";
        if (session.projectPath() == null) {
            var activePaths = inferActiveFilePaths(effectiveQuery, session.messages(), null);
            var requestFocus = SystemPromptLoader.analyzeRequestFocus(effectiveQuery, activePaths);
            String prompt = getSystemPrompt(sessionId);
            return new SystemPromptLoader.ContextInspection(
                    prompt,
                    requestFocus,
                    List.of(),
                    requestFocus.activeFilePaths(),
                    List.of(),
                    List.of(),
                    prompt.length(),
                    ContextEstimator.estimateTokens(prompt),
                    systemPromptBudget);
        }
        awaitMcpInitForRequest();
        var requestToolNames = toolNames(snapshotCurrentTools());
        var activePaths = inferActiveFilePaths(effectiveQuery, session.messages(), session.projectPath());
        var config = new CandidatePromptAssembler.Config(
                candidateInjectionEnabled,
                candidateInjectionMaxCount,
                candidateInjectionMaxTokens,
                Set.of());
        return SystemPromptLoader.inspectRequest(
                session.projectPath(),
                memoryStore,
                dailyJournal,
                markdownStore,
                getModelForSession(sessionId),
                provider,
                systemPromptBudget,
                requestToolNames,
                hasBraveApiKey,
                candidateStore,
                config,
                skillDescriptionsProvider.apply(sessionId),
                effectiveQuery,
                activePaths);
    }

    private void scheduleRuntimeSkillPrune(String sessionId, Path projectPath) {
        var scheduled = sessionRuntimePruneScheduled.computeIfAbsent(sessionId, ignored -> new AtomicBoolean(false));
        if (!scheduled.compareAndSet(false, true)) {
            return;
        }
        Thread.ofVirtual().name("runtime-prune-" + shortenSessionId(sessionId)).start(() -> {
            try {
                dynamicSkillGenerator.pruneExpired(sessionId, projectPath);
            } catch (Exception e) {
                log.warn("Runtime skill prune failed for {}: {}", sessionId, e.getMessage());
            } finally {
                scheduled.set(false);
            }
        });
    }

    private MessageCompactor createRequestCompactor(String sessionId, String requestSystemPrompt) {
        if (getLlmClient() == null) {
            return compactor;
        }
        if (contextWindowTokens <= 0) {
            return compactor;
        }
        int systemPromptTokens = ContextEstimator.estimateTokens(requestSystemPrompt);
        var config = new CompactionConfig(
                contextWindowTokens,
                maxTokens,
                systemPromptTokens,
                0.85,
                0.60,
                5);
        return new MessageCompactor(getLlmClient(), getModelForSession(sessionId), config);
    }

    private String getSystemPrompt(String sessionId) {
        if (candidateStore == null || !candidateInjectionEnabled) {
            sessionInjectedCandidateIds.remove(sessionId);
            return systemPrompt;
        }
        // Dynamically append promoted candidates to the base system prompt
        var config = new CandidatePromptAssembler.Config(
                true, candidateInjectionMaxCount, candidateInjectionMaxTokens, java.util.Set.of());
        var assembly = CandidatePromptAssembler.assembleWithMetadata(candidateStore, config);
        if (assembly.section().isEmpty()) {
            sessionInjectedCandidateIds.remove(sessionId);
            return systemPrompt;
        }
        sessionInjectedCandidateIds.put(sessionId, assembly.candidateIds());
        return systemPrompt + assembly.section();
    }

    private void appendInjectedCandidateIds(com.fasterxml.jackson.databind.node.ObjectNode result,
                                            String sessionId) {
        var candidateIds = sessionInjectedCandidateIds.getOrDefault(sessionId, List.of());
        if (candidateIds.isEmpty()) {
            return;
        }
        var array = objectMapper.createArrayNode();
        candidateIds.forEach(array::add);
        result.set("injectedCandidateIds", array);
    }

    private void recordInjectedCandidateOutcomes(String sessionId,
                                                 boolean success,
                                                 boolean cancelled,
                                                 StopReason stopReason) {
        java.util.Objects.requireNonNull(sessionId, "sessionId");
        java.util.Objects.requireNonNull(stopReason, "stopReason");
        if (candidateStore == null) {
            return;
        }
        var candidateIds = sessionInjectedCandidateIds.getOrDefault(sessionId, List.of());
        if (candidateIds.isEmpty()) {
            return;
        }
        boolean effectiveSuccess = success && !cancelled;

        // Record injection outcome for audit trail
        var audit = this.injectionAuditLog;
        if (audit != null) {
            try {
                boolean severeFailure = !effectiveSuccess && stopReason == StopReason.ERROR;
                audit.recordOutcome(new dev.aceclaw.memory.InjectionAuditLog.InjectionOutcome(
                        java.time.Instant.now(), sessionId, candidateIds,
                        effectiveSuccess, severeFailure,
                        buildOutcomeNote(cancelled, stopReason)));
            } catch (Exception e) {
                log.debug("Injection outcome audit failed: {}", e.getMessage());
            }
        }
        boolean severeFailure = !effectiveSuccess && stopReason == StopReason.ERROR;
        var outcome = new CandidateStore.CandidateOutcome(
                effectiveSuccess,
                severeFailure,
                false,
                "runtime:" + sessionId,
                buildOutcomeNote(cancelled, stopReason),
                null,
                null
        );

        int updated = 0;
        for (var candidateId : candidateIds) {
            try {
                if (candidateStore.recordOutcome(candidateId, outcome).isPresent()) {
                    updated++;
                }
            } catch (Exception e) {
                log.warn("Candidate outcome writeback failed: candidateId={}, reason={}",
                        candidateId, e.getMessage());
            }
        }
        if (updated == 0) {
            return;
        }
        try {
            var transitions = candidateStore.evaluateAll();
            if (!transitions.isEmpty()) {
                log.info("Candidate outcome enforcement: {} transitions after turn (session={})",
                        transitions.size(), sessionId);
            }
        } catch (Exception e) {
            log.warn("Candidate outcome enforcement evaluation failed: {}", e.getMessage());
        }
    }

    private static String buildOutcomeNote(boolean cancelled, StopReason stopReason) {
        java.util.Objects.requireNonNull(stopReason, "stopReason");
        if (cancelled) {
            return "runtime-outcome:cancelled";
        }
        return "runtime-outcome:" + stopReason.name().toLowerCase(java.util.Locale.ROOT);
    }

    /**
     * Records runtime metrics for a completed turn and exports to runtime-latest.json.
     */
    /**
     * Records runtime metrics for a completed turn.
     *
     * @param success         whether the task succeeded
     * @param firstTry        true only if succeeded without any replan/retry/fallback
     * @param retryCount      number of retries or replan attempts (0 for first-try success)
     * @param stopReason      the stop reason for timeout detection
     * @param metricsCollector tool metrics for this session (may be null)
     * @param projectPath     project root for exporting runtime-latest.json
     */
    private void recordRuntimeMetrics(String sessionId, boolean success, boolean firstTry,
                                       int retryCount, StopReason stopReason,
                                       ToolMetricsCollector metricsCollector, Path projectPath,
                                       RequestAttribution requestAttribution) {
        var exporter = this.runtimeMetricsExporter;
        if (exporter == null) return;
        try {
            exporter.recordTaskOutcome(success, firstTry, retryCount);
            exporter.recordTurn();
            // Provider + model live at the level of the INDIVIDUAL request batch, not the
            // daemon-lifetime aggregate, so they flow into the counter here rather than at
            // export time. A /model switch mid-session produces new (provider, model)
            // buckets for subsequent turns while leaving earlier history correctly tagged.
            String provider = getLlmClient() != null ? getLlmClient().provider() : null;
            String model = getModelForSession(sessionId);
            exporter.recordLlmRequests(requestAttribution, provider, model);
            if (stopReason == StopReason.MAX_TOKENS) {
                exporter.recordTimeout();
            }
            if (projectPath != null) {
                exporter.export(projectPath, metricsCollector);
            }
        } catch (Exception e) {
            log.debug("Runtime metrics recording failed: {}", e.getMessage());
        }
    }

    /**
     * Records injection audit event when candidates are injected into a turn.
     */
    private void recordInjectionAuditEvent(String sessionId, List<String> candidateIds, String queryHint) {
        var audit = this.injectionAuditLog;
        if (audit == null || candidateStore == null) return;
        try {
            var injected = new java.util.ArrayList<dev.aceclaw.memory.InjectionAuditLog.InjectedCandidate>();
            int totalTokens = 0;
            for (String id : candidateIds) {
                var opt = candidateStore.byId(id);
                if (opt.isEmpty()) continue;
                var c = opt.get();
                int est = dev.aceclaw.core.agent.ContextEstimator.estimateTokens(c.content());
                injected.add(new dev.aceclaw.memory.InjectionAuditLog.InjectedCandidate(
                        c.id(), c.content(), c.toolTag(), c.score(), c.evidenceCount(), c.score(), est));
                totalTokens += est;
            }
            audit.recordInjection(new dev.aceclaw.memory.InjectionAuditLog.InjectionEvent(
                    java.time.Instant.now(), sessionId,
                    queryHint != null ? queryHint.substring(0, Math.min(queryHint.length(), 100)) : "",
                    injected, totalTokens, candidateInjectionMaxTokens,
                    candidateStore.byState(dev.aceclaw.memory.CandidateState.PROMOTED).size(),
                    injected.size()));
        } catch (Exception e) {
            log.debug("Injection audit recording failed: {}", e.getMessage());
        }
    }

    String getSystemPromptForTest(String sessionId) {
        return getSystemPrompt(sessionId);
    }

    static List<String> inferActiveFilePaths(String prompt,
                                             List<AgentSession.ConversationMessage> history,
                                             Path projectPath) {
        var candidates = new java.util.LinkedHashSet<String>();
        capturePaths(candidates, prompt, projectPath);
        if (history != null) {
            int start = Math.max(0, history.size() - 6);
            for (int i = start; i < history.size(); i++) {
                String text = switch (history.get(i)) {
                    case AgentSession.ConversationMessage.User u -> u.content();
                    case AgentSession.ConversationMessage.Assistant a -> a.content();
                    case AgentSession.ConversationMessage.System s -> s.content();
                };
                capturePaths(candidates, text, projectPath);
                if (candidates.size() >= 12) {
                    break;
                }
            }
        }
        return List.copyOf(candidates.stream().limit(12).toList());
    }

    private static final java.util.regex.Pattern FILE_PATH_PATTERN = java.util.regex.Pattern.compile(
            "(?<![A-Za-z0-9_./-])(?:[A-Za-z0-9_.-]+/)+[A-Za-z0-9_.-]+|[A-Za-z0-9_.-]+\\.[A-Za-z0-9]{1,8}");

    private static void capturePaths(java.util.Set<String> sink, String text, Path projectPath) {
        if (text == null || text.isBlank()) {
            return;
        }
        var matcher = FILE_PATH_PATTERN.matcher(text);
        while (matcher.find() && sink.size() < 12) {
            String raw = matcher.group();
            if (raw == null || raw.isBlank()) {
                continue;
            }
            String normalized = raw.replace('\\', '/');
            if (projectPath != null) {
                try {
                    var absolute = Path.of(normalized);
                    if (absolute.isAbsolute() && absolute.normalize().startsWith(projectPath.normalize())) {
                        normalized = projectPath.normalize().relativize(absolute.normalize()).toString()
                                .replace('\\', '/');
                    }
                } catch (Exception ignored) {
                    // Best-effort path extraction only.
                }
            }
            sink.add(normalized);
        }
    }

    void recordInjectedCandidateOutcomesForTest(String sessionId,
                                                boolean success,
                                                boolean cancelled,
                                                StopReason stopReason) {
        recordInjectedCandidateOutcomes(sessionId, success, cancelled, stopReason);
    }

    /**
     * Handles the result of a context compaction during a turn.
     * Replaces session conversation history with compacted messages and
     * persists extracted context items to auto-memory.
     */
    private void handleCompactionResult(AgentSession session,
                                        dev.aceclaw.core.agent.CompactionResult result) {
        // Replace session history with compacted summary messages
        var compactedConversation = new ArrayList<AgentSession.ConversationMessage>();
        for (var msg : result.compactedMessages()) {
            switch (msg) {
                case Message.UserMessage u -> {
                    String text = u.content().stream()
                            .filter(b -> b instanceof ContentBlock.Text)
                            .map(b -> ((ContentBlock.Text) b).text())
                            .reduce("", (a, b) -> a + b);
                    if (!text.isEmpty()) {
                        compactedConversation.add(
                                new AgentSession.ConversationMessage.User(text));
                    }
                }
                case Message.AssistantMessage a -> {
                    String text = a.content().stream()
                            .filter(b -> b instanceof ContentBlock.Text)
                            .map(b -> ((ContentBlock.Text) b).text())
                            .reduce("", (a2, b) -> a2 + b);
                    if (!text.isEmpty()) {
                        compactedConversation.add(
                                new AgentSession.ConversationMessage.Assistant(text));
                    }
                }
            }
        }
        session.replaceMessages(compactedConversation);

        log.info("Session {} history replaced with {} compacted messages (was {})",
                session.id(), compactedConversation.size(),
                result.originalTokenEstimate() + " estimated tokens");

        // Persist extracted context items to auto-memory (Phase 0 memory flush)
        if (memoryStore != null && !result.extractedContext().isEmpty()) {
            for (var item : result.extractedContext()) {
                try {
                    memoryStore.add(
                            MemoryEntry.Category.CODEBASE_INSIGHT,
                            item,
                            List.of("compaction", "auto-extracted"),
                            "compaction:" + session.id(),
                            false,
                            workingDir);
                } catch (Exception e) {
                    log.warn("Failed to persist compaction context to memory: {}", e.getMessage());
                }
            }
            log.info("Persisted {} context items to auto-memory from compaction",
                    result.extractedContext().size());
        }

        // Append compaction event to daily journal
        if (dailyJournal != null) {
            dailyJournal.append("Context compacted: " + result.phaseReached().name() +
                    " (original ~" + result.originalTokenEstimate() +
                    " tokens, extracted " + result.extractedContext().size() + " items)");
        }
    }

    // -- Per-turn journal logging -------------------------------------------

    /**
     * Logs a brief summary of a completed turn to the daily journal.
     * This enables cross-session memory: new sessions see what happened in previous ones.
     */
    private void logTurnToJournal(String userPrompt,
                                  dev.aceclaw.core.agent.Turn turn) {
        try {
            var toolsUsed = extractToolNames(turn.newMessages());
            var promptSummary = truncate(userPrompt, 100);
            var responseSummary = truncate(turn.text(), 150);

            var sb = new StringBuilder();
            sb.append("User: ").append(promptSummary);
            if (!responseSummary.isEmpty()) {
                sb.append(" -> Agent: ").append(responseSummary);
            }
            if (!toolsUsed.isEmpty()) {
                sb.append(" | Tools: ").append(String.join(", ", toolsUsed));
            }
            sb.append(" | Tokens: ").append(turn.totalUsage().totalTokens());

            dailyJournal.append(sb.toString());
        } catch (Exception e) {
            log.warn("Failed to log turn to journal: {}", e.getMessage());
        }
    }

    /**
     * Extracts unique tool names from the turn's messages.
     */
    private static List<String> extractToolNames(List<Message> messages) {
        return messages.stream()
                .filter(m -> m instanceof Message.AssistantMessage)
                .flatMap(m -> ((Message.AssistantMessage) m).content().stream())
                .filter(b -> b instanceof ContentBlock.ToolUse)
                .map(b -> ((ContentBlock.ToolUse) b).name())
                .distinct()
                .toList();
    }

    private static String truncate(String text, int maxLen) {
        if (text == null || text.isEmpty()) return "";
        text = text.strip().replace("\n", " ");
        if (text.length() <= maxLen) return text;
        return text.substring(0, maxLen) + "...";
    }

    // -- Message conversion ------------------------------------------------

    /**
     * Converts the session's conversation messages into LLM {@link Message} format.
     */
    private static List<Message> toMessages(List<AgentSession.ConversationMessage> conversationMessages) {
        var messages = new ArrayList<Message>();
        for (var msg : conversationMessages) {
            switch (msg) {
                case AgentSession.ConversationMessage.User u ->
                        messages.add(Message.user(u.content()));
                case AgentSession.ConversationMessage.Assistant a ->
                        messages.add(Message.assistant(a.content()));
                case AgentSession.ConversationMessage.System ignored -> {
                    // System messages are handled via the system prompt, not in conversation history
                }
            }
        }
        return messages;
    }

    private static String requireString(JsonNode params, String field) {
        if (params == null || !params.has(field) || params.get(field).isNull()) {
            throw new IllegalArgumentException("Missing required parameter: " + field);
        }
        return params.get(field).asText();
    }

    // -- Plan checkpoint resume logic -----------------------------------------

    /**
     * Attempts to resume from a plan checkpoint. Returns the result if resume
     * was accepted and executed, or null if no checkpoint found or user declined.
     */
    private Object tryResumeFromCheckpoint(
            String sessionId, AgentSession session,
            StreamContext cancelContext, StreamEventHandler eventHandler,
            StreamingAgentLoop permissionAwareLoop, CancellationToken cancellationToken,
            ToolMetricsCollector metricsCollector, WatchdogTimer watchdog,
            Set<String> requestToolNames) throws Exception {

        var resumeRouter = new ResumeRouter(planCheckpointStore);
        var routeDecision = resumeRouter.route(sessionId, session.projectPath());
        if (!routeDecision.hasCheckpoint()) {
            return null;
        }

        var cp = routeDecision.checkpoint();
        log.info("Resumable plan checkpoint detected: planId={}, route={}, step {}/{}",
                cp.planId(), routeDecision.route(),
                cp.lastCompletedStepIndex() + 1, cp.plan().steps().size());

        // Send resume.detected notification
        try {
            var p = objectMapper.createObjectNode();
            p.put("planId", cp.planId());
            p.put("completedSteps", cp.lastCompletedStepIndex() + 1);
            p.put("totalSteps", cp.plan().steps().size());
            p.put("route", routeDecision.route());
            p.put("originalGoal", truncate(cp.originalGoal(), 200));
            cancelContext.sendNotification("resume.detected", p);
        } catch (IOException e) {
            log.warn("Failed to send resume.detected notification: {}", e.getMessage());
        }

        // Offer resume to user
        boolean userAccepted = offerResumeAndWaitForResponse(cancelContext, cp);

        if (userAccepted && cp.hasRemainingSteps()) {
            return executeResumedPlan(cp, session, sessionId, cancelContext,
                    eventHandler, permissionAwareLoop, cancellationToken, metricsCollector, watchdog,
                    requestToolNames);
        }

        // User declined or no remaining steps
        String reason = userAccepted ? "no_remaining_steps" : "user_declined";
        log.info("Resume declined: planId={}, reason={}", cp.planId(), reason);
        try {
            var p = objectMapper.createObjectNode();
            p.put("planId", cp.planId());
            p.put("reason", reason);
            cancelContext.sendNotification("resume.fallback", p);
        } catch (IOException e) {
            log.warn("Failed to send resume.fallback notification: {}", e.getMessage());
        }
        planCheckpointStore.markFailed(cp.planId());
        return null; // proceed with normal flow
    }

    // -- Explicit /continue command (issue #501) -------------------------------

    private static final Set<String> EXPLICIT_CONTINUE_KEYWORDS = Set.of(
            "继续", "continue", "resume", "/resume", "/continue");

    /**
     * Returns true if the prompt is a recognized explicit-continue command —
     * one of "继续", "continue", "resume", "/resume", "/continue" after
     * trimming and lowercasing. The Chinese token is matched as-is (no case).
     */
    static boolean isExplicitContinue(String prompt) {
        if (prompt == null) return false;
        String trimmed = prompt.strip();
        if (trimmed.isEmpty()) return false;
        return EXPLICIT_CONTINUE_KEYWORDS.contains(trimmed.toLowerCase(Locale.ROOT));
    }

    /**
     * Handles an explicit /continue command. Routes deterministically (no
     * resume.offer round-trip) to the highest-priority resumable. When both a
     * plan and turn checkpoint coexist, surfaces a numbered choice via
     * {@code resume.choices} and awaits {@code resume.choice_response}.
     *
     * @return a result ObjectNode — either the resumed plan/turn result, or a
     *         friendly "nothing to resume" message that consumes no LLM tokens.
     */
    private Object handleExplicitContinue(
            String sessionId, AgentSession session, String prompt,
            CancelAwareStreamContext cancelContext, StreamEventHandler eventHandler,
            StreamingAgentLoop permissionAwareLoop, CancellationToken cancellationToken,
            ToolMetricsCollector metricsCollector, WatchdogTimer watchdog,
            Set<String> requestToolNames, List<Message> conversationHistory) throws Exception {

        var resumeRouter = new ResumeRouter(planCheckpointStore, turnCheckpointStore);
        var resumables = resumeRouter.findAllResumable(sessionId, session.projectPath());

        if (resumables.isEmpty()) {
            return buildNothingToResumeResult(sessionId, session, prompt);
        }

        Resumable chosen;
        if (resumables.size() == 1) {
            chosen = resumables.getFirst();
        } else {
            // Multiple resumables — surface the choice. Default to the highest
            // priority (plan) on timeout or malformed response so explicit
            // continue always makes forward progress.
            chosen = offerResumeChoicesAndWaitForResponse(cancelContext, resumables);
            if (chosen == null) {
                chosen = resumables.getFirst();
            }
        }

        // Mark any UNCHOSEN siblings as RESUMED so the implicit-detection path
        // (resume.offer) doesn't immediately re-surface them on the next prompt.
        // The user already implicitly declined them by picking the chosen one
        // (review finding #6).
        for (var other : resumables) {
            if (other == chosen) continue;
            try {
                switch (other) {
                    case Resumable.OfPlan p -> planCheckpointStore.markResumed(
                            p.checkpoint().planId());
                    case Resumable.OfTurn t -> {
                        if (turnCheckpointStore != null) {
                            turnCheckpointStore.markResumed(t.checkpoint().turnId());
                        }
                    }
                }
            } catch (Exception e) {
                log.warn("Failed to mark unchosen resumable as RESUMED: {}", e.getMessage());
            }
        }

        return switch (chosen) {
            case Resumable.OfPlan p -> resumePlanFromExplicitContinue(
                    p.checkpoint(), sessionId, session, cancelContext, eventHandler,
                    permissionAwareLoop, cancellationToken, metricsCollector, watchdog,
                    requestToolNames);
            case Resumable.OfTurn t -> executeResumedTurn(
                    t.checkpoint(), sessionId, session, cancelContext, eventHandler,
                    permissionAwareLoop, cancellationToken, metricsCollector, watchdog,
                    requestToolNames, conversationHistory);
        };
    }

    /**
     * Builds the "nothing to resume" no-op result so we never charge the user
     * an LLM call for a literal "继续" with no resumable state. Also records
     * the user's prompt + the synthetic assistant reply in session history
     * (review finding #3) so the dashboard turn counter and journal stay
     * consistent with what the user actually typed.
     */
    private Object buildNothingToResumeResult(String sessionId, AgentSession session, String prompt) {
        String reply = "Nothing to resume — last turn completed or was never recorded.";
        if (session != null && prompt != null) {
            session.addMessage(new AgentSession.ConversationMessage.User(prompt));
            session.addMessage(new AgentSession.ConversationMessage.Assistant(reply));
        }
        var result = objectMapper.createObjectNode();
        result.put("sessionId", sessionId);
        result.put("response", reply);
        result.put("stopReason", StopReason.END_TURN.name());
        result.put("resumeAttempted", true);
        result.put("resumeFound", false);
        var usageNode = objectMapper.createObjectNode();
        usageNode.put("inputTokens", 0);
        usageNode.put("outputTokens", 0);
        usageNode.put("totalTokens", 0);
        usageNode.put("llmRequests", 0);
        result.set("usage", usageNode);
        return result;
    }

    /**
     * Sends a {@code resume.choices} notification listing every resumable and
     * waits for the client's {@code resume.choice_response}. Returns the
     * chosen Resumable, or null on timeout / parse failure (caller falls back
     * to the highest-priority choice).
     */
    private Resumable offerResumeChoicesAndWaitForResponse(
            StreamContext context, List<Resumable> resumables) {
        try {
            var params = objectMapper.createObjectNode();
            var arr = objectMapper.createArrayNode();
            for (int i = 0; i < resumables.size(); i++) {
                var item = objectMapper.createObjectNode();
                item.put("index", i);
                switch (resumables.get(i)) {
                    case Resumable.OfPlan p -> {
                        var cp = p.checkpoint();
                        item.put("kind", "plan");
                        item.put("planId", cp.planId());
                        item.put("originalGoal", truncate(cp.originalGoal(), 200));
                        item.put("completedSteps", cp.lastCompletedStepIndex() + 1);
                        item.put("totalSteps", cp.plan().steps().size());
                        item.put("lastUpdated", cp.updatedAt().toString());
                    }
                    case Resumable.OfTurn t -> {
                        var cp = t.checkpoint();
                        item.put("kind", "turn");
                        item.put("turnId", cp.turnId());
                        item.put("originalPrompt", truncate(cp.originalPrompt(), 200));
                        item.put("completedIterations", cp.completedIterations());
                        item.put("lastUpdated", cp.updatedAt().toString());
                    }
                }
                arr.add(item);
            }
            params.set("choices", arr);
            context.sendNotification("resume.choices", params);
        } catch (IOException e) {
            log.warn("Failed to send resume.choices: {}", e.getMessage());
            return null;
        }

        try {
            var response = context.readMessage(30_000);
            if (response != null && response.has("method")
                    && "resume.choice_response".equals(response.get("method").asText())) {
                var respParams = response.get("params");
                if (respParams != null && respParams.has("index")) {
                    int idx = respParams.get("index").asInt(-1);
                    if (idx >= 0 && idx < resumables.size()) {
                        return resumables.get(idx);
                    }
                }
            }
        } catch (IOException e) {
            log.warn("Failed to read resume.choice_response: {}", e.getMessage());
        }
        return null;
    }

    /**
     * Resumes a plan from explicit /continue. Skips the resume.offer round-trip
     * (the implicit detection path's role) and routes straight into
     * {@link #executeResumedPlan}.
     */
    private Object resumePlanFromExplicitContinue(
            PlanCheckpoint cp, String sessionId, AgentSession session,
            CancelAwareStreamContext cancelContext, StreamEventHandler eventHandler,
            StreamingAgentLoop permissionAwareLoop, CancellationToken cancellationToken,
            ToolMetricsCollector metricsCollector, WatchdogTimer watchdog,
            Set<String> requestToolNames) throws Exception {

        log.info("Explicit continue → plan resume: planId={}, step {}/{}",
                cp.planId(), cp.lastCompletedStepIndex() + 1, cp.plan().steps().size());

        // resume.detected mirrors the implicit-path notification so dashboards
        // can render the same UI for explicit + implicit resume paths. Route
        // uses the underlying tier ("session"/"workspace") plus a separate
        // `trigger` discriminator — keeps the wire format stable while letting
        // observers tell explicit /continue apart from implicit auto-offer
        // (review finding #4).
        var resumeRouter = new ResumeRouter(planCheckpointStore, turnCheckpointStore);
        var routeDecision = resumeRouter.route(sessionId, session.projectPath());
        String routeLabel = routeDecision.checkpoint() != null
                && cp.planId().equals(routeDecision.checkpoint().planId())
                ? routeDecision.route() : "session";
        try {
            var p = objectMapper.createObjectNode();
            p.put("planId", cp.planId());
            p.put("completedSteps", cp.lastCompletedStepIndex() + 1);
            p.put("totalSteps", cp.plan().steps().size());
            p.put("route", routeLabel);
            p.put("trigger", "explicit_continue");
            p.put("originalGoal", truncate(cp.originalGoal(), 200));
            cancelContext.sendNotification("resume.detected", p);
        } catch (IOException e) {
            log.warn("Failed to send resume.detected notification: {}", e.getMessage());
        }

        if (!cp.hasRemainingSteps()) {
            log.info("Explicit continue: plan {} has no remaining steps", cp.planId());
            // markCompleted, not markFailed — the plan finished cleanly; the
            // /continue just had no work to do (review finding #29).
            planCheckpointStore.markCompleted(cp.planId());
            return buildNothingToResumeResult(sessionId, session, "continue");
        }
        return executeResumedPlan(cp, session, sessionId, cancelContext,
                eventHandler, permissionAwareLoop, cancellationToken, metricsCollector,
                watchdog, requestToolNames);
    }

    /**
     * Resumes a single ReAct turn from a {@link TurnCheckpoint}. Replays the
     * checkpoint's conversation snapshot as conversation history and injects
     * the {@code [TURN_RESUME_CONTEXT]} block produced by
     * {@link ResumeRouter#buildTurnResumePrompt} as the new user prompt.
     */
    private Object executeResumedTurn(
            TurnCheckpoint cp, String sessionId, AgentSession session,
            CancelAwareStreamContext cancelContext, StreamEventHandler eventHandler,
            StreamingAgentLoop permissionAwareLoop, CancellationToken cancellationToken,
            ToolMetricsCollector metricsCollector, WatchdogTimer watchdog,
            Set<String> requestToolNames, List<Message> conversationHistory) throws Exception {

        log.info("Explicit continue → turn resume: turnId={}, completedIterations={}",
                cp.turnId(), cp.completedIterations());

        // Mark old turn checkpoint as RESUMED so a subsequent /continue doesn't
        // route back to it. Done before the new turn runs so a crash mid-resume
        // doesn't loop us back into the same stale state.
        if (turnCheckpointStore != null) {
            try {
                turnCheckpointStore.markResumed(cp.turnId());
            } catch (Exception e) {
                log.warn("Failed to mark turn checkpoint as RESUMED ({}): {}",
                        cp.turnId(), e.getMessage());
            }
        }

        // resume.detected: route uses underlying tier + trigger discriminator
        // for parity with the plan path (review finding #4).
        var routeRouter = new ResumeRouter(planCheckpointStore, turnCheckpointStore);
        var routeDec = routeRouter.route(sessionId, session.projectPath());
        String routeLabel;
        if (routeDec.resumable() instanceof Resumable.OfTurn rt
                && cp.turnId().equals(rt.checkpoint().turnId())) {
            routeLabel = routeDec.route();
        } else {
            // chosen via resume.choices — fall back to a label that still
            // disambiguates same-session vs cross-session.
            routeLabel = sessionId.equals(cp.sessionId()) ? "turn_session" : "turn_workspace";
        }
        try {
            var p = objectMapper.createObjectNode();
            p.put("turnId", cp.turnId());
            p.put("completedIterations", cp.completedIterations());
            p.put("route", routeLabel);
            p.put("trigger", "explicit_continue");
            p.put("originalPrompt", truncate(cp.originalPrompt(), 200));
            cancelContext.sendNotification("resume.detected", p);
        } catch (IOException e) {
            log.warn("Failed to send resume.detected (turn) notification: {}", e.getMessage());
        }

        // Do NOT deserialize the checkpoint's conversationSnapshot to replay
        // — the snapshot is encoded by ConversationSnapshot.serialize as
        // {role, blocks:[...]} which deserializeConversation (legacy
        // plan-checkpoint shape) flattens to empty Text blocks, silently
        // losing every tool_use / tool_result block. The
        // [TURN_RESUME_CONTEXT] block built by ResumeRouter.buildTurnResumePrompt
        // parses the snapshot for a per-iteration digest, which gives the
        // LLM enough context without needing to round-trip the raw blocks
        // (review finding #1, #25, #26).
        var historyForTurn = new ArrayList<>(conversationHistory);
        var resumePrompt = ResumeRouter.buildTurnResumePrompt(cp);

        try {
            var p = objectMapper.createObjectNode();
            p.put("turnId", cp.turnId());
            p.put("completedIterations", cp.completedIterations());
            cancelContext.sendNotification("resume.injected", p);
        } catch (IOException e) {
            log.warn("Failed to send resume.injected (turn) notification: {}", e.getMessage());
        }

        var adaptive = runTurnWithAdaptiveContinuation(
                permissionAwareLoop, resumePrompt, historyForTurn, eventHandler, cancellationToken);
        sendCancelledNotificationIfNeeded(cancellationToken, cancelContext, sessionId);

        // Append a synthetic marker for the session history instead of replaying
        // cp.originalPrompt() — that would double-append the prompt when the
        // session is the same one that produced the original turn (review
        // finding #9). The marker mirrors the plan-resume path's behavior
        // (executeResumedPlan adds "[Resumed plan: ...]").
        String marker = "[Resumed turn: " + truncate(cp.originalPrompt(), 100) + "]";
        var result = buildTurnResult(adaptive.turn(), session, sessionId,
                marker, cancellationToken, metricsCollector, adaptive, requestToolNames);
        if (result instanceof com.fasterxml.jackson.databind.node.ObjectNode obj) {
            obj.put("resumed", true);
            obj.put("resumedFromTurnId", cp.turnId());
        }
        return result;
    }

    /**
     * Sends a resume.offer notification and waits for the client's resume.response.
     */
    private boolean offerResumeAndWaitForResponse(StreamContext context, PlanCheckpoint cp) {
        try {
            var params = objectMapper.createObjectNode();
            params.put("planId", cp.planId());
            params.put("originalGoal", truncate(cp.originalGoal(), 200));
            params.put("completedSteps", cp.lastCompletedStepIndex() + 1);
            params.put("totalSteps", cp.plan().steps().size());
            if (cp.hasRemainingSteps()) {
                var nextStep = cp.plan().steps().get(cp.nextStepIndex());
                params.put("nextStepName", nextStep.name());
            }
            params.put("lastUpdated", cp.updatedAt().toString());
            context.sendNotification("resume.offer", params);
        } catch (IOException e) {
            log.warn("Failed to send resume.offer: {}", e.getMessage());
            return false;
        }

        // Wait for client response (30 second timeout)
        try {
            var response = context.readMessage(30_000);
            if (response != null && response.has("method")
                    && "resume.response".equals(response.get("method").asText())) {
                var respParams = response.get("params");
                return respParams != null && respParams.has("accept")
                        && respParams.get("accept").asBoolean(false);
            }
        } catch (IOException e) {
            log.warn("Failed to read resume.response: {}", e.getMessage());
        }
        return false; // timeout or parse failure = decline
    }

    /**
     * Executes a plan from a checkpoint, resuming from the last completed step.
     */
    private Object executeResumedPlan(
            PlanCheckpoint cp, AgentSession session, String sessionId,
            StreamContext cancelContext, StreamEventHandler eventHandler,
            StreamingAgentLoop permissionAwareLoop, CancellationToken cancellationToken,
            ToolMetricsCollector metricsCollector, WatchdogTimer watchdog,
            Set<String> requestToolNames) throws Exception {

        // 1. Mark old checkpoint as RESUMED
        planCheckpointStore.markResumed(cp.planId());

        // 2. Send resume.bound_task notification
        try {
            var p = objectMapper.createObjectNode();
            p.put("planId", cp.planId());
            p.put("originalSessionId", cp.sessionId());
            p.put("newSessionId", sessionId);
            cancelContext.sendNotification("resume.bound_task", p);
        } catch (IOException e) {
            log.warn("Failed to send resume.bound_task notification: {}", e.getMessage());
        }

        // 3. Build partial plan with only remaining steps
        var remainingSteps = cp.remainingSteps();
        var resumedPlanId = cp.planId() + "-resumed";
        var partialPlan = new TaskPlan(
                resumedPlanId,
                cp.originalGoal(),
                remainingSteps,
                new PlanStatus.Executing(
                        cp.lastCompletedStepIndex() + 1, cp.plan().steps().size()),
                Instant.now());

        // 4. Build conversation history from checkpoint snapshot
        var conversationHistory = new ArrayList<>(deserializeConversation(cp.conversationSnapshot()));

        // 5. Inject resume context prompt
        var resumePrompt = ResumeRouter.buildResumePrompt(cp);
        conversationHistory.addFirst(Message.user(resumePrompt));

        // 6. Send resume.injected notification
        try {
            var p = objectMapper.createObjectNode();
            p.put("planId", cp.planId());
            p.put("resumeFromStep", cp.nextStepIndex() + 1);
            p.put("totalSteps", cp.plan().steps().size());
            cancelContext.sendNotification("resume.injected", p);
        } catch (IOException e) {
            log.warn("Failed to send resume.injected notification: {}", e.getMessage());
        }

        // 7. Send plan_created notification for the resumed plan
        try {
            var params = objectMapper.createObjectNode();
            params.put("planId", resumedPlanId);
            params.put("stepCount", remainingSteps.size());
            params.put("goal", truncate(cp.originalGoal(), 200));
            params.put("resumed", true);
            params.put("resumedFromStep", cp.nextStepIndex() + 1);
            var stepsArray = objectMapper.createArrayNode();
            for (int i = 0; i < remainingSteps.size(); i++) {
                var step = remainingSteps.get(i);
                var stepNode = objectMapper.createObjectNode();
                stepNode.put("index", cp.nextStepIndex() + i + 1);
                stepNode.put("name", step.name());
                stepNode.put("description", step.description());
                stepsArray.add(stepNode);
            }
            params.set("steps", stepsArray);
            cancelContext.sendNotification("stream.plan_created", params);
        } catch (IOException e) {
            log.warn("Failed to send plan_created notification for resumed plan: {}", e.getMessage());
        }

        // 8. Create notification listener for the resumed plan
        // Same parentStepId tagging as the fresh-plan path (#485 PR 3/3) — null
        // when eventHandler isn't the production handler.
        StreamingNotificationHandler resumedStreamHandler =
                eventHandler instanceof StreamingNotificationHandler h ? h : null;
        var notificationListener = new SequentialPlanExecutor.PlanEventListener() {
            @Override
            public void onStepStarted(PlannedStep step, int stepIndex, int totalSteps) {
                int oneBasedIndex = cp.nextStepIndex() + stepIndex + 1;
                if (resumedStreamHandler != null) {
                    resumedStreamHandler.setCurrentStepId(resumedPlanId + ":step:" + oneBasedIndex);
                }
                try {
                    var p = objectMapper.createObjectNode();
                    p.put("planId", resumedPlanId);
                    p.put("stepId", step.stepId());
                    p.put("stepIndex", oneBasedIndex);
                    p.put("totalSteps", cp.plan().steps().size());
                    p.put("stepName", step.name());
                    cancelContext.sendNotification("stream.plan_step_started", p);
                } catch (IOException e) {
                    log.warn("Failed to send plan_step_started notification: {}", e.getMessage());
                }
            }

            @Override
            public void onStepCompleted(PlannedStep step, int stepIndex, StepResult result) {
                try {
                    var p = objectMapper.createObjectNode();
                    p.put("planId", resumedPlanId);
                    p.put("stepId", step.stepId());
                    p.put("stepIndex", cp.nextStepIndex() + stepIndex + 1);
                    p.put("stepName", step.name());
                    p.put("success", result.success());
                    p.put("durationMs", result.durationMs());
                    p.put("tokensUsed", result.tokensUsed());
                    cancelContext.sendNotification("stream.plan_step_completed", p);
                } catch (IOException e) {
                    log.warn("Failed to send plan_step_completed notification: {}", e.getMessage());
                }
            }

            @Override
            public void onPlanCompleted(TaskPlan completedPlan, boolean success, long totalDurationMs) {
                if (resumedStreamHandler != null) {
                    resumedStreamHandler.setCurrentStepId(null);
                }
                try {
                    var p = objectMapper.createObjectNode();
                    p.put("planId", resumedPlanId);
                    p.put("success", success);
                    p.put("totalDurationMs", totalDurationMs);
                    p.put("stepsCompleted", completedPlan.completedSteps());
                    p.put("totalSteps", completedPlan.steps().size());
                    p.put("resumed", true);
                    cancelContext.sendNotification("stream.plan_completed", p);
                } catch (IOException e) {
                    log.warn("Failed to send plan_completed notification: {}", e.getMessage());
                }
            }

            @Override
            public void onPlanReplanned(TaskPlan oldPlan, TaskPlan newPlan, int attempt, String rationale) {
                try {
                    var p = objectMapper.createObjectNode();
                    p.put("planId", newPlan.planId());
                    p.put("replanAttempt", attempt);
                    p.put("newStepCount", newPlan.steps().size());
                    p.put("rationale", rationale);
                    p.put("resumed", true);
                    cancelContext.sendNotification("stream.plan_replanned", p);
                } catch (IOException e) {
                    log.warn("Failed to send plan_replanned notification: {}", e.getMessage());
                }
            }

            @Override
            public void onPlanEscalated(TaskPlan escalatedPlan, String reason) {
                if (resumedStreamHandler != null) {
                    resumedStreamHandler.setCurrentStepId(null);
                }
                try {
                    var p = objectMapper.createObjectNode();
                    p.put("planId", escalatedPlan.planId());
                    p.put("reason", reason);
                    cancelContext.sendNotification("stream.plan_escalated", p);
                } catch (IOException e) {
                    log.warn("Failed to send plan_escalated notification: {}", e.getMessage());
                }
            }

            @Override
            public void onStepFallback(PlannedStep step, int stepIndex,
                                       String fallbackApproach, int attempt) {
                // Browser-only — see planned-prompt path above for rationale.
                var p = objectMapper.createObjectNode();
                p.put("sessionId", sessionId);
                p.put("planId", resumedPlanId);
                p.put("stepId", step.stepId());
                // Resume path: stepIndex is local to the remaining-steps slice,
                // so offset by cp.nextStepIndex() to match the original plan's 1-based index.
                p.put("stepIndex", cp.nextStepIndex() + stepIndex + 1);
                p.put("fallbackApproach", fallbackApproach);
                p.put("attempt", attempt);
                emitBrowserOnly(sessionId, "stream.plan_step_fallback", p);
            }
        };

        // 9. Wrap with checkpointing listener
        var wsHash = ResumeRouter.hashWorkspace(session.projectPath());
        var newCheckpoint = new PlanCheckpoint(
                resumedPlanId, sessionId, wsHash, cp.originalGoal(), cp.plan(),
                new ArrayList<>(cp.completedStepResults()),
                cp.lastCompletedStepIndex(),
                cp.conversationSnapshot(),
                PlanCheckpoint.CheckpointStatus.ACTIVE,
                "Resumed from step " + (cp.nextStepIndex() + 1),
                new ArrayList<>(cp.artifacts()),
                cp.createdAt(), Instant.now());
        planCheckpointStore.save(newCheckpoint);

        var checkpointingListener = new CheckpointingPlanEventListener(
                notificationListener, planCheckpointStore, newCheckpoint, session,
                cp.nextStepIndex());

        // 10. Execute remaining steps
        AdaptiveReplanner resumeReplanner = createReplannerIfEnabled(sessionId);
        var perStepWall = maxPlanStepWallTimeSec > 0
                ? Duration.ofSeconds(maxPlanStepWallTimeSec) : null;
        var totalPlanWall = maxPlanTotalWallTimeSec > 0
                ? Duration.ofSeconds(maxPlanTotalWallTimeSec) : null;
        var executor = new SequentialPlanExecutor(checkpointingListener, resumeReplanner,
                watchdog, perStepWall, totalPlanWall);
        var planResult = executor.execute(partialPlan, permissionAwareLoop,
                conversationHistory, eventHandler, cancellationToken);

        sendCancelledNotificationIfNeeded(cancellationToken, cancelContext, sessionId);

        // 11. Store messages and build result
        session.addMessage(new AgentSession.ConversationMessage.User(
                "[Resumed plan: " + truncate(cp.originalGoal(), 100) + "]"));
        var responseSummary = buildPlanResponseSummary(planResult);
        if (!responseSummary.isEmpty()) {
            session.addMessage(new AgentSession.ConversationMessage.Assistant(responseSummary));
        }

        if (dailyJournal != null) {
            dailyJournal.append("Resumed plan (" + remainingSteps.size() + " remaining steps, "
                    + (planResult.success() ? "success" : "failed") + "): "
                    + truncate(cp.originalGoal(), 100) + " | Tokens: " + planResult.totalTokensUsed());
        }

        var plannedStopReason = planResult.success() ? StopReason.END_TURN : StopReason.ERROR;
        schedulePostRequestLearning(
                sessionId,
                session.projectPath(),
                syntheticTurn(planResult, plannedStopReason),
                List.copyOf(session.messages()),
                metricsCollector != null ? metricsCollector.allMetrics() : Map.of(),
                requestToolNames);
        recordInjectedCandidateOutcomes(
                sessionId, planResult.success(), cancellationToken.isCancelled(), plannedStopReason);
        recordSkillOutcomes(
                sessionId,
                session.projectPath(),
                syntheticTurn(planResult, plannedStopReason),
                cancellationToken,
                null);
        int resumeFailedSteps = (int) planResult.stepResults().stream().filter(s -> !s.success()).count();
        boolean resumeFirstTry = planResult.success() && resumeFailedSteps == 0;
        // Resumed plans don't re-run the planner (plan was already generated + checkpointed),
        // so planResult.requestAttribution() is the full picture: step turns + any replans
        // during resumption. Same attribution goes to both runtime metrics and the payload.
        var resumedUsage = planResult.requestAttribution();
        recordRuntimeMetrics(sessionId, planResult.success(), resumeFirstTry,
                resumeFailedSteps, plannedStopReason, metricsCollector, session.projectPath(),
                resumedUsage);

        var result = objectMapper.createObjectNode();
        result.put("sessionId", sessionId);
        result.put("response", responseSummary);
        // Use the resumed-plan stopReason (END_TURN on success, ERROR
        // on plan failure) instead of a hardcoded END_TURN — see the
        // matching comment in executePlannedPrompt.
        result.put("stopReason", plannedStopReason.name());
        result.put("planned", true);
        result.put("resumed", true);
        result.put("planSuccess", planResult.success());
        result.put("planSteps", cp.plan().steps().size());
        result.put("planStepsCompleted",
                cp.lastCompletedStepIndex() + 1 + planResult.plan().completedSteps());
        appendInjectedCandidateIds(result, sessionId);
        if (cancellationToken.isCancelled()) {
            result.put("cancelled", true);
        }

        int totalInput = planResult.stepResults().stream().mapToInt(StepResult::inputTokens).sum();
        int totalOutput = planResult.stepResults().stream().mapToInt(StepResult::outputTokens).sum();
        var usageNode = objectMapper.createObjectNode();
        usageNode.put("inputTokens", totalInput);
        usageNode.put("outputTokens", totalOutput);
        usageNode.put("totalTokens", planResult.totalTokensUsed());
        usageNode.put("llmRequests", resumedUsage.total());
        writeLlmRequestsBySource(usageNode, resumedUsage);
        result.set("usage", usageNode);

        log.info("Resumed plan complete: sessionId={}, success={}, steps={}/{}, tokens={}",
                sessionId, planResult.success(),
                cp.lastCompletedStepIndex() + 1 + planResult.plan().completedSteps(),
                cp.plan().steps().size(), planResult.totalTokensUsed());

        return result;
    }

    /**
     * Serializes conversation messages to JSON strings for checkpoint persistence.
     */
    private List<String> serializeConversation(List<AgentSession.ConversationMessage> messages) {
        var result = new ArrayList<String>();
        if (messages == null) return result;
        for (var msg : messages) {
            try {
                result.add(objectMapper.writeValueAsString(msg));
            } catch (Exception e) {
                log.debug("Failed to serialize conversation message: {}", e.getMessage());
            }
        }
        return result;
    }

    /**
     * Deserializes conversation snapshot JSON strings back to LLM messages.
     * Parses the simple {"role":"...", "content":"..."} format produced by
     * CheckpointingPlanEventListener.
     */
    private List<Message> deserializeConversation(List<String> jsonMessages) {
        var result = new ArrayList<Message>();
        if (jsonMessages == null) return result;
        for (var json : jsonMessages) {
            try {
                var node = objectMapper.readTree(json);
                if (node == null) {
                    log.debug("Null JSON node for conversation message, skipping");
                    continue;
                }
                String role = node.has("role") ? node.get("role").asText() : "user";
                String content = node.has("content") ? node.get("content").asText() : "";
                switch (role) {
                    case "assistant" -> result.add(Message.assistant(content));
                    case "system" -> result.add(Message.user("[system] " + content));
                    default -> result.add(Message.user(content));
                }
            } catch (Exception e) {
                log.debug("Failed to deserialize conversation message: {}", e.getMessage());
            }
        }
        return result;
    }


    // -- StreamEventHandler that forwards events as JSON-RPC notifications --

    /**
     * Builds a {@link StreamEventHandler} for daemon-internal subsystems
     * (cron scheduler, deferred actions, …) that need to push their
     * agent-loop events to the dashboard but have no CLI client to
     * funnel through. The handler translates raw LLM stream events into
     * the same {@code stream.*} JSON-RPC notifications a normal user
     * session would emit, and broadcasts them under {@code sessionId} via
     * the WS bridge so the dashboard's existing ExecutionTree picks them
     * up — a cron run becomes a session with one turn per fire (#459).
     *
     * <p>Returns the SAME translation logic the user-session path uses
     * so both surfaces speak literally one wire format.
     *
     * <p>Note: this handler only carries the LOW-LEVEL agent-loop events
     * (thinking, tool_use, text, tool_completed). Callers must emit the
     * lifecycle wrapper events ({@link #emitSessionStarted},
     * {@link #emitTurnStarted}, {@link #emitTurnCompleted}) themselves at
     * the run boundaries — without those, the dashboard reducer has no
     * session/turn parent to attach the inner nodes to and tools end up
     * floating at the tree root.
     */
    public static StreamEventHandler newBroadcastingStreamHandler(
            WebSocketBridge bridge, String sessionId, ObjectMapper mapper) {
        var ctx = new BroadcastOnlyStreamContext(bridge, sessionId);
        return new StreamingNotificationHandler(ctx, mapper);
    }

    /**
     * Broadcasts {@code stream.session_started} for a daemon-internal
     * session (cron, deferred action, …). Idempotency is the caller's
     * responsibility — emit once per session lifetime (e.g., dedupe
     * against a {@code Set<String>} of already-signaled ids).
     *
     * <p>Same wire shape as the user-session path uses, so the dashboard
     * reducer creates the session root node identically.
     */
    public static void emitSessionStarted(WebSocketBridge bridge, String sessionId,
                                          String model, ObjectMapper mapper) {
        if (bridge == null) return;
        var p = mapper.createObjectNode();
        p.put("sessionId", sessionId);
        p.put("model", model);
        p.put("timestamp", java.time.Instant.now().toString());
        bridge.broadcast(sessionId, "stream.session_started", p);
    }

    /**
     * Broadcasts {@code stream.turn_started} for a daemon-internal
     * session. Call once before each {@code agentLoop.runTurn(...)} so
     * the reducer creates a fresh turn node and the agent-loop events
     * that follow attach under it.
     */
    public static void emitTurnStarted(WebSocketBridge bridge, String sessionId,
                                       String requestId, int turnNumber, ObjectMapper mapper) {
        if (bridge == null) return;
        var ts = mapper.createObjectNode();
        ts.put("sessionId", sessionId);
        ts.put("requestId", requestId);
        ts.put("turnNumber", turnNumber);
        ts.put("timestamp", java.time.Instant.now().toString());
        bridge.broadcast(sessionId, "stream.turn_started", ts);
    }

    /**
     * Broadcasts {@code stream.turn_completed} for a daemon-internal
     * session. Call once after each {@code agentLoop.runTurn(...)}
     * (success OR failure path) so the reducer can mark the turn
     * complete and stop drawing it as in-progress.
     */
    public static void emitTurnCompleted(WebSocketBridge bridge, String sessionId,
                                         String requestId, int turnNumber,
                                         long durationMs, int toolCount,
                                         String stopReason, ObjectMapper mapper) {
        if (bridge == null) return;
        var tc = mapper.createObjectNode();
        tc.put("sessionId", sessionId);
        tc.put("requestId", requestId);
        tc.put("turnNumber", turnNumber);
        tc.put("durationMs", durationMs);
        tc.put("toolCount", toolCount);
        tc.put("timestamp", java.time.Instant.now().toString());
        if (stopReason != null) {
            tc.put("stopReason", stopReason);
        }
        bridge.broadcast(sessionId, "stream.turn_completed", tc);
    }


    private record AntiPatternGateOverride(Instant expiresAt, String reason) {}
}
