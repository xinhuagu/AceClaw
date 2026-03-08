package dev.aceclaw.daemon;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.aceclaw.core.agent.AgentLoopConfig;
import dev.aceclaw.core.agent.CancellationAware;
import dev.aceclaw.core.agent.CancellationToken;
import dev.aceclaw.core.agent.CompactionConfig;
import dev.aceclaw.core.agent.ContextEstimator;
import dev.aceclaw.core.agent.DoomLoopDetector;
import dev.aceclaw.core.agent.ProgressDetector;
import dev.aceclaw.core.agent.WatchdogTimer;
import dev.aceclaw.core.agent.CompactionResult;
import dev.aceclaw.core.agent.HookEvent;
import dev.aceclaw.core.agent.HookExecutor;
import dev.aceclaw.core.agent.HookResult;
import dev.aceclaw.core.agent.MessageCompactor;
import dev.aceclaw.core.agent.StreamingAgentLoop;
import dev.aceclaw.core.agent.Tool;
import dev.aceclaw.core.agent.ToolMetricsCollector;
import dev.aceclaw.core.agent.ToolRegistry;
import dev.aceclaw.core.llm.ContentBlock;
import dev.aceclaw.core.llm.LlmException;
import dev.aceclaw.core.llm.Message;
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
import dev.aceclaw.core.planner.PlannedStep;
import dev.aceclaw.core.planner.SequentialPlanExecutor;
import dev.aceclaw.core.planner.StepResult;
import dev.aceclaw.core.planner.StepStatus;
import dev.aceclaw.core.planner.TaskPlan;
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
import java.util.concurrent.locks.ReentrantLock;

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

    /** Permission level assignments for known tools. */
    private static final Map<String, PermissionLevel> TOOL_PERMISSION_LEVELS = Map.ofEntries(
            Map.entry("read_file", PermissionLevel.READ),
            Map.entry("glob", PermissionLevel.READ),
            Map.entry("grep", PermissionLevel.READ),
            Map.entry("list_directory", PermissionLevel.READ),
            Map.entry("web_fetch", PermissionLevel.READ),
            Map.entry("web_search", PermissionLevel.READ),
            Map.entry("screen_capture", PermissionLevel.READ),
            Map.entry("memory", PermissionLevel.READ),
            Map.entry("task", PermissionLevel.READ),
            Map.entry("task_output", PermissionLevel.READ),
            Map.entry("write_file", PermissionLevel.WRITE),
            Map.entry("edit_file", PermissionLevel.WRITE),
            Map.entry("bash", PermissionLevel.EXECUTE),
            Map.entry("browser", PermissionLevel.EXECUTE),
            Map.entry("applescript", PermissionLevel.EXECUTE),
            Map.entry("cron", PermissionLevel.READ),
            Map.entry("defer_check", PermissionLevel.READ)
    );

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
    private final ConcurrentHashMap<String, AntiPatternGateOverride> antiPatternGateOverrides =
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

    private Object handlePrompt(JsonNode params, StreamContext context) throws Exception {
        var sessionId = requireString(params, "sessionId");
        var prompt = requireString(params, "prompt");

        var session = sessionManager.getSession(sessionId);
        if (session == null) {
            throw new IllegalArgumentException("Session not found: " + sessionId);
        }
        if (!session.isActive()) {
            throw new IllegalArgumentException("Session is not active: " + sessionId);
        }

        log.info("Streaming agent prompt: sessionId={}, promptLength={}", sessionId, prompt.length());

        // Convert session conversation history to LLM messages
        var conversationHistory = toMessages(session.messages());

        // Set up cancellation support: the CancelAwareStreamContext runs a monitor
        // thread that reads from the socket and dispatches agent.cancel and
        // permission.response messages accordingly.
        var cancellationToken = new CancellationToken();
        var cancelContext = new CancelAwareStreamContext(context, cancellationToken, objectMapper);

        // Create a StreamEventHandler that forwards events via the cancel-aware context
        var eventHandler = new StreamingNotificationHandler(cancelContext, objectMapper);

        // Wrap tools with permission checking and hooks for this request
        var permissionAwareRegistry = createPermissionAwareRegistry(cancelContext, sessionId);

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

        var promptAssembly = assembleSystemPromptForRequest(sessionId, session, prompt);
        var effectiveCompactor = createRequestCompactor(sessionId, promptAssembly.prompt());

        // Create a temporary agent loop with the permission-aware registry, compaction and metrics
        var agentConfig = AgentLoopConfig.builder()
                .sessionId(sessionId)
                .metricsCollector(metricsCollector)
                .maxIterations(maxIterations)
                .watchdog(watchdog)
                .doomLoopDetector(doomLoop)
                .progressDetector(progress)
                .build();
        var permissionAwareLoop = new StreamingAgentLoop(
                getLlmClient(), permissionAwareRegistry,
                getModelForSession(sessionId), promptAssembly.prompt(),
                maxTokens, thinkingBudget, contextWindowTokens, effectiveCompactor, agentConfig);

        // Wire stream event handler to SkillTool for sub-agent event forwarding
        // and session ID to DeferCheckTool
        for (var tool : toolRegistry.all()) {
            if (tool instanceof SkillTool st) {
                st.setCurrentHandler(eventHandler);
            }
            if (tool instanceof dev.aceclaw.daemon.deferred.DeferCheckTool dct) {
                dct.setCurrentSessionId(sessionId);
            }
        }

        // Acquire per-session turn lock (coordinates with DeferredActionScheduler)
        var turnLock = sessionTurnLocks.computeIfAbsent(sessionId, _ -> new ReentrantLock());
        turnLock.lock();
        try {
            // Start the cancel monitor thread to read from the socket
            cancelContext.startMonitor();

            // Check for resumable plan checkpoint before planning or execution
            if (planCheckpointStore != null) {
                var resumeResult = tryResumeFromCheckpoint(
                        sessionId, session, cancelContext, eventHandler,
                        permissionAwareLoop, cancellationToken, metricsCollector, watchdog);
                if (resumeResult != null) {
                    sendBudgetExhaustedNotificationIfNeeded(watchdog, cancelContext, sessionId, cancellationToken);
                    return resumeResult;
                }
            }

            // Check if this task warrants upfront planning
            if (plannerEnabled) {
                var estimator = new ComplexityEstimator(plannerThreshold);
                var complexityScore = estimator.estimate(prompt);

                if (complexityScore.shouldPlan()) {
                    log.info("Complex task detected (score={}, signals={}), generating plan",
                            complexityScore.score(), complexityScore.signals());
                    var planResult = executePlannedPrompt(prompt, session, sessionId, cancelContext,
                            eventHandler, permissionAwareLoop, cancellationToken, metricsCollector, watchdog);
                    sendBudgetExhaustedNotificationIfNeeded(watchdog, cancelContext, sessionId, cancellationToken);
                    return planResult;
                }
            }

            var adaptive = runTurnWithAdaptiveContinuation(
                    permissionAwareLoop, prompt, conversationHistory, eventHandler, cancellationToken);

            // Send cancelled / budget-exhausted notifications if applicable
            sendCancelledNotificationIfNeeded(cancellationToken, cancelContext, sessionId);
            sendBudgetExhaustedNotificationIfNeeded(watchdog, cancelContext, sessionId, cancellationToken);

            return buildTurnResult(adaptive.turn(), session, sessionId, prompt, cancellationToken, metricsCollector,
                    adaptive);

        } catch (dev.aceclaw.core.llm.LlmException e) {
            // Translate LLM errors to user-friendly messages
            log.error("LLM error during prompt: statusCode={}, message={}",
                    e.statusCode(), e.getMessage(), e);

            session.addMessage(new AgentSession.ConversationMessage.User(prompt));

            String userMessage = formatLlmError(e);
            throw new IllegalStateException(userMessage);
        } finally {
            if (watchdog != null) {
                watchdog.close();
            }
            cancelContext.stopMonitor();
            turnLock.unlock();

            // Clear handler and session references to avoid stale state between requests
            for (var tool : toolRegistry.all()) {
                if (tool instanceof SkillTool st) {
                    st.setCurrentHandler(null);
                }
                if (tool instanceof dev.aceclaw.daemon.deferred.DeferCheckTool dct) {
                    dct.setCurrentSessionId(null);
                }
            }
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
            ToolMetricsCollector metricsCollector, WatchdogTimer watchdog) throws Exception {

        // 1. Generate plan
        var planner = new LLMTaskPlanner(getLlmClient(), getModelForSession(sessionId));
        var toolDefs = toolRegistry.toDefinitions();

        TaskPlan plan;
        try {
            plan = planner.plan(prompt, toolDefs);
        } catch (Exception e) {
            log.warn("Plan generation failed, falling back to direct execution: {}", e.getMessage());
            // Fall back to direct execution
            var conversationHistory = toMessages(session.messages());
            var adaptive = runTurnWithAdaptiveContinuation(
                    permissionAwareLoop, prompt, conversationHistory, eventHandler, cancellationToken);
            sendCancelledNotificationIfNeeded(cancellationToken, cancelContext, sessionId);
            return buildTurnResult(adaptive.turn(), session, sessionId, prompt, cancellationToken, metricsCollector,
                    adaptive);
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
        var listener = new SequentialPlanExecutor.PlanEventListener() {
            @Override
            public void onStepStarted(PlannedStep step, int stepIndex, int totalSteps) {
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
                try {
                    var p = objectMapper.createObjectNode();
                    p.put("planId", escalatedPlan.planId());
                    p.put("reason", reason);
                    cancelContext.sendNotification("stream.plan_escalated", p);
                } catch (IOException e) {
                    log.warn("Failed to send plan_escalated notification: {}", e.getMessage());
                }
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
        recordInjectedCandidateOutcomes(
                sessionId, planResult.success(), cancellationToken.isCancelled(), plannedStopReason);

        // 6. Build result
        var result = objectMapper.createObjectNode();
        result.put("sessionId", sessionId);
        result.put("response", responseSummary);
        result.put("stopReason", "END_TURN");
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
        result.set("usage", usageNode);

        log.info("Planned task complete: sessionId={}, success={}, steps={}/{}, tokens={}",
                sessionId, planResult.success(), planResult.plan().completedSteps(),
                planResult.plan().steps().size(), planResult.totalTokensUsed());

        return result;
    }

    /**
     * Builds the standard turn result object (shared between direct and fallback-from-plan paths).
     */
    private Object buildTurnResult(dev.aceclaw.core.agent.Turn turn, AgentSession session,
                                    String sessionId, String prompt,
                                    CancellationToken cancellationToken,
                                    ToolMetricsCollector metricsCollector,
                                    AdaptiveTurnResult adaptive) {
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

        // Self-improvement
        if (selfImprovementEngine != null) {
            final var turnRef = turn;
            final var historyRef = List.copyOf(session.messages());
            final var sessionIdRef = sessionId;
            final var projectPathRef = session.projectPath();
            // Take an immutable snapshot of metrics before handing off to the virtual thread
            final var metricsSnapshot = metricsCollector != null
                    ? metricsCollector.allMetrics() : Map.<String, dev.aceclaw.core.agent.ToolMetrics>of();
            var shortId = sessionId.length() > 8 ? sessionId.substring(0, 8) : sessionId;
            Thread.ofVirtual().name("self-improve-" + shortId).start(() -> {
                try {
                    var insights = selfImprovementEngine.analyze(turnRef, historyRef, metricsSnapshot);
                    if (!insights.isEmpty()) {
                        int persisted = selfImprovementEngine.persist(insights, sessionIdRef, projectPathRef);
                        log.debug("Self-improvement: {} insights analyzed, {} persisted (session={})",
                                insights.size(), persisted, sessionIdRef);
                    }
                } catch (Exception e) {
                    log.warn("Self-improvement analysis failed: {}", e.getMessage());
                }
            });
        }

        recordInjectedCandidateOutcomes(
                sessionId, turn.finalStopReason() != StopReason.ERROR, cancellationToken.isCancelled(),
                turn.finalStopReason());

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
            if (turn.wasCompacted()) {
                lastCompaction = turn.compactionResult();
            }
            mergedMessages.addAll(turn.newMessages());
            totalInput += turn.totalUsage().inputTokens();
            totalOutput += turn.totalUsage().outputTokens();
            totalCacheCreate += turn.totalUsage().cacheCreationInputTokens();
            totalCacheRead += turn.totalUsage().cacheReadInputTokens();
            conversation.addAll(turn.newMessages());

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
                budgetExhausted, budgetExhaustionReason);
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
    private ToolRegistry createPermissionAwareRegistry(CancelAwareStreamContext context, String sessionId) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(sessionId, "sessionId");
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
        for (var tool : toolRegistry.all()) {
            Tool effectiveTool = materializeSessionScopedTool(
                    tool, sessionProject, sessionReadFileTool, sessionWriteFileTool);
            registry.register(new PermissionAwareTool(
                    effectiveTool, permissionManager, context, objectMapper,
                    hookExecutor, sessionId, cwd, antiPatternGate,
                    () -> getAntiPatternGateOverride(sessionId, effectiveTool.name()),
                    antiPatternGateFeedbackStore,
                    candidateStore));
        }
        return registry;
    }

    private Tool materializeSessionScopedTool(
            Tool original,
            Path sessionProject,
            ReadFileTool sessionReadFileTool,
            WriteFileTool sessionWriteFileTool) {
        Objects.requireNonNull(original, "original");
        Objects.requireNonNull(sessionProject, "sessionProject");
        Objects.requireNonNull(sessionReadFileTool, "sessionReadFileTool");
        Objects.requireNonNull(sessionWriteFileTool, "sessionWriteFileTool");
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
    private Set<String> registeredToolNames = Set.of();
    private boolean hasBraveApiKey;
    private String skillDescriptions = "";
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
    private boolean adaptiveReplanEnabled = true;
    private int maxAgentTurns = 50;
    private int maxAgentWallTimeSec = 600;
    private int maxAgentHardTurns = 0;
    private int maxAgentHardWallTimeSec = 0;
    private int maxPlanStepWallTimeSec = 300;
    private int maxPlanTotalWallTimeSec = 3600;
    private PlanCheckpointStore planCheckpointStore;

    /**
     * Sets the LLM configuration for permission-aware agent loop creation.
     * Must be called before registering with the router.
     */
    public void setLlmConfig(dev.aceclaw.core.llm.LlmClient llmClient, String model, String systemPrompt) {
        this.llmClient = llmClient;
        this.model = model;
        this.systemPrompt = systemPrompt;
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
        if (workingDir != null) {
            this.antiPatternGateFeedbackStore = new AntiPatternGateFeedbackStore(workingDir);
        }
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
                                         Set<String> registeredToolNames,
                                         boolean hasBraveApiKey,
                                         String skillDescriptions) {
        this.markdownStore = markdownStore;
        this.provider = provider;
        this.systemPromptBudget = systemPromptBudget != null ? systemPromptBudget : SystemPromptBudget.DEFAULT;
        this.registeredToolNames = registeredToolNames != null ? Set.copyOf(registeredToolNames) : Set.of();
        this.hasBraveApiKey = hasBraveApiKey;
        this.skillDescriptions = skillDescriptions != null ? skillDescriptions : "";
    }

    /**
     * Sets the self-improvement engine for post-turn learning analysis.
     */
    public void setSelfImprovementEngine(SelfImprovementEngine selfImprovementEngine) {
        this.selfImprovementEngine = selfImprovementEngine;
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
        sessionMetrics.remove(sessionId);
        sessionInjectedCandidateIds.remove(sessionId);
        sessionDoomLoops.remove(sessionId);
        sessionProgressDetectors.remove(sessionId);
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
            String sessionId, AgentSession session, String prompt) {
        if (session == null || session.projectPath() == null) {
            return new SystemPromptLoader.RequestAssembly(getSystemPrompt(sessionId), List.of(), List.of(), List.of());
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
                registeredToolNames,
                hasBraveApiKey,
                candidateStore,
                config,
                skillDescriptions,
                prompt,
                activePaths);
        if (assembly.injectedCandidateIds().isEmpty()) {
            sessionInjectedCandidateIds.remove(sessionId);
        } else {
            sessionInjectedCandidateIds.put(sessionId, assembly.injectedCandidateIds());
        }
        return assembly;
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
            ToolMetricsCollector metricsCollector, WatchdogTimer watchdog) throws Exception {

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
                    eventHandler, permissionAwareLoop, cancellationToken, metricsCollector, watchdog);
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
            ToolMetricsCollector metricsCollector, WatchdogTimer watchdog) throws Exception {

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
        var notificationListener = new SequentialPlanExecutor.PlanEventListener() {
            @Override
            public void onStepStarted(PlannedStep step, int stepIndex, int totalSteps) {
                try {
                    var p = objectMapper.createObjectNode();
                    p.put("planId", resumedPlanId);
                    p.put("stepId", step.stepId());
                    p.put("stepIndex", cp.nextStepIndex() + stepIndex + 1);
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
                try {
                    var p = objectMapper.createObjectNode();
                    p.put("planId", escalatedPlan.planId());
                    p.put("reason", reason);
                    cancelContext.sendNotification("stream.plan_escalated", p);
                } catch (IOException e) {
                    log.warn("Failed to send plan_escalated notification: {}", e.getMessage());
                }
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
        recordInjectedCandidateOutcomes(
                sessionId, planResult.success(), cancellationToken.isCancelled(), plannedStopReason);

        var result = objectMapper.createObjectNode();
        result.put("sessionId", sessionId);
        result.put("response", responseSummary);
        result.put("stopReason", "END_TURN");
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

    // -- Checkpointing plan event listener ------------------------------------

    /**
     * Decorates a PlanEventListener with checkpoint persistence.
     * Writes a checkpoint after each step completion and marks final status.
     */
    private static final class CheckpointingPlanEventListener
            implements SequentialPlanExecutor.PlanEventListener {

        private final SequentialPlanExecutor.PlanEventListener delegate;
        private final PlanCheckpointStore store;
        private volatile PlanCheckpoint currentCheckpoint;
        private final AgentSession session;
        private final int stepIndexOffset;

        CheckpointingPlanEventListener(
                SequentialPlanExecutor.PlanEventListener delegate,
                PlanCheckpointStore store,
                PlanCheckpoint initialCheckpoint,
                AgentSession session,
                int stepIndexOffset) {
            this.delegate = delegate;
            this.store = store;
            this.currentCheckpoint = initialCheckpoint;
            this.session = session;
            this.stepIndexOffset = stepIndexOffset;
        }

        @Override
        public void onStepStarted(PlannedStep step, int stepIndex, int totalSteps) {
            delegate.onStepStarted(step, stepIndex, totalSteps);
        }

        @Override
        public void onStepCompleted(PlannedStep step, int stepIndex, StepResult result) {
            delegate.onStepCompleted(step, stepIndex, result);

            // Apply offset: stepIndex from executor is 0-based relative to the
            // (possibly partial) plan. For resumed plans, offset maps it back to
            // the absolute index in the original full plan.
            int absoluteIndex = stepIndex + stepIndexOffset;

            // Update and persist checkpoint
            var updatedPlan = currentCheckpoint.plan()
                    .withStepStatus(step.stepId(),
                            result.success() ? StepStatus.COMPLETED : StepStatus.FAILED);

            // Serialize current conversation state
            var conversationJson = serializeSessionMessages(session);

            String hint = result.success()
                    ? "Step " + (absoluteIndex + 1) + " completed successfully"
                    : "Step " + (absoluteIndex + 1) + " failed: "
                            + (result.error() != null ? result.error() : "unknown");

            currentCheckpoint = currentCheckpoint.withStepCompleted(
                    absoluteIndex, result, updatedPlan, conversationJson, hint, List.of());

            try {
                store.save(currentCheckpoint);
            } catch (Exception e) {
                log.warn("Failed to persist plan checkpoint after step {}: {}",
                        stepIndex + 1, e.getMessage());
            }
        }

        @Override
        public void onPlanCompleted(TaskPlan plan, boolean success, long totalDurationMs) {
            delegate.onPlanCompleted(plan, success, totalDurationMs);

            currentCheckpoint = currentCheckpoint.withStatus(
                    success ? PlanCheckpoint.CheckpointStatus.COMPLETED
                            : PlanCheckpoint.CheckpointStatus.FAILED);
            try {
                store.save(currentCheckpoint);
            } catch (Exception e) {
                log.warn("Failed to persist final plan checkpoint: {}", e.getMessage());
            }
        }

        @Override
        public void onPlanReplanned(TaskPlan oldPlan, TaskPlan newPlan, int attempt, String rationale) {
            delegate.onPlanReplanned(oldPlan, newPlan, attempt, rationale);

            // Update checkpoint with the new plan
            currentCheckpoint = new PlanCheckpoint(
                    currentCheckpoint.planId(), currentCheckpoint.sessionId(),
                    currentCheckpoint.workspaceHash(), currentCheckpoint.originalGoal(),
                    newPlan, currentCheckpoint.completedStepResults(),
                    currentCheckpoint.lastCompletedStepIndex(),
                    serializeSessionMessages(session),
                    currentCheckpoint.status(),
                    "Replanned (attempt " + attempt + "): " + rationale,
                    currentCheckpoint.artifacts(),
                    currentCheckpoint.createdAt(), Instant.now());
            try {
                store.save(currentCheckpoint);
            } catch (Exception e) {
                log.warn("Failed to persist plan checkpoint after replan: {}", e.getMessage());
            }
        }

        @Override
        public void onPlanEscalated(TaskPlan plan, String reason) {
            delegate.onPlanEscalated(plan, reason);

            try {
                store.markFailed(currentCheckpoint.planId());
            } catch (Exception e) {
                log.warn("Failed to mark checkpoint as failed after escalation: {}", e.getMessage());
            }
        }

        private static List<String> serializeSessionMessages(AgentSession session) {
            // Simple serialization: role:content for each message
            var result = new ArrayList<String>();
            var messages = session.messages();
            if (messages == null) return result;
            for (var msg : messages) {
                switch (msg) {
                    case AgentSession.ConversationMessage.User u ->
                            result.add("{\"role\":\"user\",\"content\":" + escapeJson(u.content()) + "}");
                    case AgentSession.ConversationMessage.Assistant a ->
                            result.add("{\"role\":\"assistant\",\"content\":" + escapeJson(a.content()) + "}");
                    case AgentSession.ConversationMessage.System s ->
                            result.add("{\"role\":\"system\",\"content\":" + escapeJson(s.content()) + "}");
                }
            }
            return result;
        }

        private static String escapeJson(String text) {
            if (text == null) return "null";
            return "\"" + text.replace("\\", "\\\\")
                    .replace("\"", "\\\"")
                    .replace("\n", "\\n")
                    .replace("\r", "\\r")
                    .replace("\t", "\\t") + "\"";
        }
    }

    // -- StreamEventHandler that forwards events as JSON-RPC notifications --

    /**
     * Forwards stream events from the agent loop to the client as JSON-RPC notifications.
     */
    private static final class StreamingNotificationHandler implements StreamEventHandler {

        private final StreamContext context;
        private final ObjectMapper objectMapper;

        StreamingNotificationHandler(StreamContext context, ObjectMapper objectMapper) {
            this.context = context;
            this.objectMapper = objectMapper;
        }

        @Override
        public void onThinkingDelta(StreamEvent.ThinkingDelta event) {
            try {
                var params = objectMapper.createObjectNode();
                params.put("delta", event.text());
                context.sendNotification("stream.thinking", params);
            } catch (IOException e) {
                log.warn("Failed to send thinking delta notification: {}", e.getMessage());
            }
        }

        @Override
        public void onTextDelta(StreamEvent.TextDelta event) {
            try {
                var params = objectMapper.createObjectNode();
                params.put("delta", event.text());
                context.sendNotification("stream.text", params);
            } catch (IOException e) {
                log.warn("Failed to send text delta notification: {}", e.getMessage());
            }
        }

        @Override
        public void onContentBlockStart(StreamEvent.ContentBlockStart event) {
            if (event.block() instanceof ContentBlock.ToolUse toolUse) {
                try {
                    var params = objectMapper.createObjectNode();
                    params.put("name", toolUse.name());
                    params.put("id", toolUse.id());
                    String summary = summarizeToolInput(toolUse.name(), toolUse.inputJson(), objectMapper);
                    if (!summary.isBlank()) {
                        params.put("summary", summary);
                    }
                    context.sendNotification("stream.tool_use", params);
                } catch (IOException e) {
                    log.warn("Failed to send tool use notification: {}", e.getMessage());
                }
            }
        }

        @Override
        public void onToolCompleted(String toolUseId, String toolName,
                                    long durationMs, boolean isError, String error) {
            try {
                var params = objectMapper.createObjectNode();
                params.put("id", toolUseId);
                params.put("name", toolName);
                params.put("durationMs", durationMs);
                params.put("isError", isError);
                if (error != null && !error.isBlank()) {
                    params.put("error", truncate(error, 160));
                }
                context.sendNotification("stream.tool_completed", params);
            } catch (IOException e) {
                log.warn("Failed to send tool completed notification: {}", e.getMessage());
            }
        }

        @Override
        public void onHeartbeat(StreamEvent.Heartbeat event) {
            try {
                var params = objectMapper.createObjectNode();
                params.put("phase", event.phase());
                context.sendNotification("stream.heartbeat", params);
            } catch (IOException e) {
                log.debug("Failed to send heartbeat notification: {}", e.getMessage());
            }
        }

        @Override
        public void onError(StreamEvent.StreamError event) {
            try {
                var params = objectMapper.createObjectNode();
                params.put("error", event.error().getMessage());
                context.sendNotification("stream.error", params);
            } catch (IOException e) {
                log.warn("Failed to send error notification: {}", e.getMessage());
            }
        }

        @Override
        public void onCompaction(int originalTokens, int compactedTokens, String phase) {
            try {
                var params = objectMapper.createObjectNode();
                params.put("originalTokens", originalTokens);
                params.put("compactedTokens", compactedTokens);
                params.put("phase", phase);
                context.sendNotification("stream.compaction", params);
            } catch (IOException e) {
                log.warn("Failed to send compaction notification: {}", e.getMessage());
            }
        }

        @Override
        public void onSubAgentStart(String agentId, String prompt) {
            try {
                var params = objectMapper.createObjectNode();
                params.put("agentType", agentId);
                params.put("prompt", prompt);
                context.sendNotification("stream.subagent.start", params);
            } catch (IOException e) {
                log.warn("Failed to send subagent start notification: {}", e.getMessage());
            }
        }

        @Override
        public void onSubAgentEnd(String agentId) {
            try {
                var params = objectMapper.createObjectNode();
                params.put("agentType", agentId);
                context.sendNotification("stream.subagent.end", params);
            } catch (IOException e) {
                log.warn("Failed to send subagent end notification: {}", e.getMessage());
            }
        }

        private static String summarizeToolInput(String toolName, String inputJson, ObjectMapper mapper) {
            if (inputJson == null || inputJson.isBlank()) {
                return "";
            }
            try {
                var node = mapper.readTree(inputJson);
                if (!node.isObject()) {
                    return truncate(inputJson, 80);
                }
                String value = switch (toolName) {
                    case "bash" -> firstNonBlank(node, "command", "cmd");
                    case "read_file", "write_file", "edit_file" -> firstNonBlank(node, "file_path", "path");
                    case "grep" -> firstNonBlank(node, "pattern", "query");
                    case "glob" -> firstNonBlank(node, "pattern", "path");
                    case "list_directory" -> firstNonBlank(node, "path");
                    case "web_fetch" -> firstNonBlank(node, "url");
                    case "web_search" -> firstNonBlank(node, "query");
                    case "browser" -> firstNonBlank(node, "action", "url");
                    case "task" -> firstNonBlank(node, "prompt", "description");
                    case "skill" -> firstNonBlank(node, "name", "skill", "prompt");
                    default -> firstNonBlank(node, "path", "file_path", "query", "url", "command");
                };
                if (value == null || value.isBlank()) {
                    return "";
                }
                return truncate(value.replace('\n', ' '), 80);
            } catch (Exception ignored) {
                return "";
            }
        }

        private static String firstNonBlank(com.fasterxml.jackson.databind.JsonNode node, String... fields) {
            for (var field : fields) {
                var value = node.path(field).asText("");
                if (!value.isBlank()) {
                    return value;
                }
            }
            return "";
        }
    }

    // -- Cancel-aware stream context -----------------------------------------

    /**
     * A {@link StreamContext} wrapper that runs a background monitor thread
     * to read from the socket during streaming. This is necessary because
     * the connection thread is blocked in {@code handlePrompt()}, so
     * {@code agent.cancel} notifications buffered in the socket go unread.
     *
     * <p>The monitor thread uses non-blocking NIO with a {@link Selector}
     * to poll the socket with 100ms timeout intervals. This allows the
     * thread to exit cleanly when stopped, without interrupting (which
     * would close the NIO channel).
     *
     * <p>The monitor thread reads messages from the socket and:
     * <ul>
     *   <li>On {@code agent.cancel}: triggers the cancellation token</li>
     *   <li>On {@code permission.response}: enqueues to a blocking queue
     *       so the permission flow can consume it</li>
     *   <li>On connection close: triggers cancellation</li>
     * </ul>
     */
    private static final class CancelAwareStreamContext implements StreamContext {

        private static final int READ_BUFFER_SIZE = 65536;
        private static final long SELECT_TIMEOUT_MS = 100;
        private static final long PERMISSION_RESPONSE_TIMEOUT_MS = 120_000;

        private final StreamContext delegate;
        private final CancellationToken cancellationToken;
        private final ObjectMapper objectMapper;
        private final SocketChannel channel;
        private final StringBuilder lineBuilder;
        private final ConcurrentHashMap<String, CompletableFuture<JsonNode>> pendingPermissions = new ConcurrentHashMap<>();
        private final BlockingQueue<JsonNode> unmatchedResponses = new LinkedBlockingQueue<>();
        private final Object permissionLifecycleLock = new Object();
        private volatile boolean stopped = false;
        private volatile Thread monitorThread;
        private volatile Selector selector;

        CancelAwareStreamContext(StreamContext delegate, CancellationToken cancellationToken,
                                 ObjectMapper objectMapper) {
            this.delegate = delegate;
            this.cancellationToken = cancellationToken;
            this.objectMapper = objectMapper;

            // Extract the channel and lineBuilder from the underlying ChannelStreamContext
            if (delegate instanceof ConnectionBridge.ChannelStreamContext channelCtx) {
                this.channel = channelCtx.channel();
                this.lineBuilder = channelCtx.lineBuilder();
            } else {
                this.channel = null;
                this.lineBuilder = null;
            }
        }

        /**
         * Registers a pending permission request keyed by requestId.
         * Returns a future that will be completed when the matching response arrives.
         */
        CompletableFuture<JsonNode> registerPermissionRequest(String requestId) {
            Objects.requireNonNull(requestId, "requestId");
            var future = new CompletableFuture<JsonNode>();
            synchronized (permissionLifecycleLock) {
                if (stopped) {
                    future.cancel(false);
                    return future;
                }
                var previous = pendingPermissions.putIfAbsent(requestId, future);
                if (previous != null) {
                    future.cancel(false);
                    throw new IllegalStateException("Duplicate permission requestId: " + requestId);
                }
            }
            return future;
        }

        /**
         * Unregisters and cancels a pending permission request if it has not been completed.
         * Safe to call even if the monitor already removed and completed the future.
         */
        void unregisterPermissionRequest(String requestId) {
            Objects.requireNonNull(requestId, "requestId");
            CompletableFuture<JsonNode> future;
            synchronized (permissionLifecycleLock) {
                future = pendingPermissions.remove(requestId);
            }
            if (future != null && !future.isDone()) {
                future.cancel(false);
            }
        }

        /**
         * Starts the background monitor thread that reads from the socket.
         * Switches the channel to non-blocking mode for the duration of monitoring.
         * Must be called before the agent loop starts.
         */
        void startMonitor() {
            if (channel == null) {
                log.debug("Cancel monitor: no socket channel available, skipping monitor");
                return;
            }
            monitorThread = Thread.ofVirtual()
                    .name("aceclaw-cancel-monitor")
                    .start(this::monitorLoop);
        }

        /**
         * Stops the monitor thread cleanly without interrupting.
         * Sets the stopped flag and wakes the selector so the thread exits promptly.
         */
        void stopMonitor() {
            stopped = true;
            // Cancel all pending permission futures so waiting threads unblock
            synchronized (permissionLifecycleLock) {
                pendingPermissions.forEach((_, future) -> future.cancel(false));
                pendingPermissions.clear();
            }
            var sel = selector;
            if (sel != null) {
                sel.wakeup();
            }
            var thread = monitorThread;
            if (thread != null) {
                try {
                    thread.join(2000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                monitorThread = null;
            }
        }

        private void monitorLoop() {
            try {
                channel.configureBlocking(false);
                selector = Selector.open();
                channel.register(selector, SelectionKey.OP_READ);

                var buffer = ByteBuffer.allocate(READ_BUFFER_SIZE);

                while (!stopped && !cancellationToken.isCancelled()) {
                    int ready = selector.select(SELECT_TIMEOUT_MS);
                    if (stopped || cancellationToken.isCancelled()) break;
                    if (ready == 0) continue;

                    selector.selectedKeys().clear();

                    buffer.clear();
                    int bytesRead = channel.read(buffer);
                    if (bytesRead == -1) {
                        log.debug("Cancel monitor: connection closed, triggering cancellation");
                        cancellationToken.cancel();
                        return;
                    }
                    if (bytesRead == 0) continue;

                    buffer.flip();
                    synchronized (lineBuilder) {
                        lineBuilder.append(StandardCharsets.UTF_8.decode(buffer));

                        // Process complete lines
                        int newlineIdx;
                        while ((newlineIdx = lineBuilder.indexOf("\n")) != -1) {
                            var line = lineBuilder.substring(0, newlineIdx).trim();
                            lineBuilder.delete(0, newlineIdx + 1);

                            if (line.isEmpty()) continue;

                            try {
                                var message = objectMapper.readTree(line);
                                String method = message.has("method")
                                        ? message.get("method").asText("") : "";

                                if ("agent.cancel".equals(method)) {
                                    log.info("Cancel monitor: received agent.cancel");
                                    cancellationToken.cancel();
                                    return;
                                } else if ("permission.response".equals(method)) {
                                    log.debug("Cancel monitor: routing permission.response");
                                    var respParams = message.get("params");
                                    var rid = respParams != null && respParams.has("requestId")
                                            ? respParams.get("requestId").asText() : null;
                                    if (rid != null) {
                                        var future = pendingPermissions.remove(rid);
                                        if (future != null) {
                                            future.complete(message);
                                        } else {
                                            log.warn("Cancel monitor: no pending request for requestId={}, dropping stale permission.response", rid);
                                        }
                                    } else {
                                        log.warn("Cancel monitor: permission.response missing requestId, dropping message");
                                    }
                                } else if ("resume.response".equals(method)) {
                                    log.debug("Cancel monitor: routing resume.response to fallback");
                                    unmatchedResponses.offer(message);
                                } else {
                                    log.debug("Cancel monitor: ignoring '{}'", method);
                                }
                            } catch (Exception e) {
                                log.warn("Cancel monitor: failed to parse message: {}", e.getMessage());
                            }
                        }
                    }
                }
            } catch (IOException e) {
                if (!stopped && !cancellationToken.isCancelled()) {
                    log.debug("Cancel monitor: I/O error: {}", e.getMessage());
                    cancellationToken.cancel();
                }
            } finally {
                // Restore blocking mode so subsequent reads work normally
                try {
                    if (selector != null) selector.close();
                    if (channel != null && channel.isOpen()) {
                        channel.configureBlocking(true);
                    }
                } catch (IOException e) {
                    log.warn("Cancel monitor: failed to restore blocking mode: {}", e.getMessage());
                }
                selector = null;
            }
        }

        @Override
        public void sendNotification(String method, Object params) throws IOException {
            delegate.sendNotification(method, params);
        }

        /**
         * Reads the next unmatched response from the fallback queue.
         * Used by ResumeRouter and other non-permission reads.
         * Returns null if cancelled or the monitor thread is no longer running.
         */
        @Override
        public JsonNode readMessage() throws IOException {
            return readMessage(PERMISSION_RESPONSE_TIMEOUT_MS);
        }

        @Override
        public JsonNode readMessage(long timeoutMs) throws IOException {
            long deadline = System.currentTimeMillis() + timeoutMs;
            while (!cancellationToken.isCancelled()) {
                long now = System.currentTimeMillis();
                long remaining = deadline - now;
                if (remaining <= 0) {
                    throw new SocketTimeoutException("response timeout after " + timeoutMs + "ms");
                }
                try {
                    JsonNode msg = unmatchedResponses.poll(Math.min(500, remaining), TimeUnit.MILLISECONDS);
                    if (msg != null) {
                        return msg;
                    }
                    // Check if the monitor thread is still alive
                    var thread = monitorThread;
                    if (thread == null || !thread.isAlive()) {
                        return null;
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return null;
                }
            }
            return null;
        }
    }

    // -- Permission-aware tool wrapper -------------------------------------

    /**
     * Wraps a tool with permission checking. Before executing, checks
     * the permission manager and, if needed, sends a permission request
     * to the client and waits for the response.
     */
    private static final class PermissionAwareTool implements Tool, CancellationAware {

        private final Tool delegate;
        private final PermissionManager permissionManager;
        private final CancelAwareStreamContext context;
        private final ObjectMapper objectMapper;
        private final HookExecutor hookExecutor;
        private final String sessionId;
        private final String cwd;
        private final AntiPatternPreExecutionGate antiPatternGate;
        private final java.util.function.Supplier<AntiPatternGateOverrideStatus> antiPatternOverrideSupplier;
        private final AntiPatternGateFeedbackStore antiPatternGateFeedbackStore;
        private final CandidateStore candidateStore;

        PermissionAwareTool(Tool delegate, PermissionManager permissionManager,
                            CancelAwareStreamContext context, ObjectMapper objectMapper,
                            HookExecutor hookExecutor, String sessionId, String cwd,
                            AntiPatternPreExecutionGate antiPatternGate,
                            java.util.function.Supplier<AntiPatternGateOverrideStatus> antiPatternOverrideSupplier,
                            AntiPatternGateFeedbackStore antiPatternGateFeedbackStore,
                            CandidateStore candidateStore) {
            this.delegate = delegate;
            this.permissionManager = permissionManager;
            this.context = context;
            this.objectMapper = objectMapper;
            this.hookExecutor = hookExecutor;
            this.sessionId = sessionId;
            this.cwd = cwd;
            this.antiPatternGate = antiPatternGate;
            this.antiPatternOverrideSupplier = antiPatternOverrideSupplier;
            this.antiPatternGateFeedbackStore = antiPatternGateFeedbackStore;
            this.candidateStore = candidateStore;
        }

        @Override
        public void setCancellationToken(CancellationToken token) {
            if (delegate instanceof CancellationAware ca) {
                ca.setCancellationToken(token);
            }
        }

        @Override
        public String name() {
            return delegate.name();
        }

        @Override
        public String description() {
            return delegate.description();
        }

        @Override
        public JsonNode inputSchema() {
            return delegate.inputSchema();
        }

        @Override
        public ToolResult execute(String inputJson) throws Exception {
            // --- PreToolUse hooks (before permission check) ---
            String effectiveInputJson = inputJson;
            if (hookExecutor != null) {
                try {
                    var toolInput = objectMapper.readTree(inputJson);
                    if (toolInput == null || !toolInput.isObject()) {
                        toolInput = objectMapper.createObjectNode();
                    }
                    var preEvent = new HookEvent.PreToolUse(sessionId, cwd, delegate.name(), toolInput);
                    var hookResult = hookExecutor.execute(preEvent);

                    switch (hookResult) {
                        case HookResult.Block blocked -> {
                            log.info("Tool {} blocked by PreToolUse hook: {}", delegate.name(), blocked.reason());
                            return new ToolResult("Blocked by hook: " + blocked.reason(), true);
                        }
                        case HookResult.Proceed proceed -> {
                            if (proceed.updatedInput() != null) {
                                effectiveInputJson = objectMapper.writeValueAsString(proceed.updatedInput());
                                log.debug("Tool {} input modified by PreToolUse hook", delegate.name());
                            }
                        }
                        case HookResult.Error err ->
                                log.warn("PreToolUse hook error for {}: {}", delegate.name(), err.message());
                    }
                } catch (Exception e) {
                    log.warn("PreToolUse hook failed for {}: {}", delegate.name(), e.getMessage());
                }
            }

            // --- Permission check ---
            // Determine the permission level for this tool
            // MCP tools default to EXECUTE since they can have side effects
            var level = delegate.name().startsWith("mcp__")
                    ? PermissionLevel.EXECUTE
                    : TOOL_PERMISSION_LEVELS.getOrDefault(delegate.name(), PermissionLevel.EXECUTE);

            // Build a human-readable description of what the tool will do
            var toolDescription = buildToolDescription(delegate.name(), effectiveInputJson);
            final String finalInputJson = effectiveInputJson;

            var permRequest = new PermissionRequest(delegate.name(), toolDescription, level);
            var decision = permissionManager.check(permRequest);
            var overrideStatus = antiPatternOverrideSupplier != null
                    ? antiPatternOverrideSupplier.get()
                    : new AntiPatternGateOverrideStatus(sessionId, delegate.name(), false, 0L, "");
            var evaluatedGateDecision = antiPatternGate != null
                    ? antiPatternGate.evaluate(delegate.name(), finalInputJson, toolDescription)
                    : AntiPatternPreExecutionGate.Decision.allow();
            var antiPatternDecision = !overrideStatus.active()
                    ? evaluatedGateDecision
                    : AntiPatternPreExecutionGate.Decision.allow();

            if (overrideStatus.active()) {
                emitGateNotification("OVERRIDE", overrideStatus, evaluatedGateDecision);
            }

            if (antiPatternDecision.action() == AntiPatternPreExecutionGate.Action.BLOCK) {
                emitGateNotification("BLOCK", overrideStatus, antiPatternDecision);
                recordBlockedRule(antiPatternDecision.ruleId());
                log.info("Anti-pattern gate blocked tool {} (ruleId={})",
                        delegate.name(), antiPatternDecision.ruleId());
                return new ToolResult(
                        "Anti-pattern gate blocked execution: "
                                + "{gate=anti_pattern_preexec, action=BLOCK, ruleId="
                                + antiPatternDecision.ruleId()
                                + ", reason=\"" + antiPatternDecision.reason() + "\""
                                + ", fallback=\"" + antiPatternDecision.fallback() + "\"}",
                        true);
            }
            if (antiPatternDecision.action() == AntiPatternPreExecutionGate.Action.PENALIZE) {
                emitGateNotification("PENALIZE", overrideStatus, antiPatternDecision);
                log.info("Anti-pattern gate penalized tool {} (ruleId={})",
                        delegate.name(), antiPatternDecision.ruleId());
            }

            switch (decision) {
                case PermissionDecision.Approved ignored -> {
                    var result = executeWithPostHooks(finalInputJson);
                    maybeRecordFalsePositiveAndRollback(overrideStatus, evaluatedGateDecision, result);
                    return result;
                }

                case PermissionDecision.Denied denied -> {
                    log.info("Tool {} denied: {}", delegate.name(), denied.reason());
                    return new ToolResult("Permission denied: " + denied.reason(), true);
                }

                case PermissionDecision.NeedsUserApproval approval -> {
                    // Send permission request to the client and await via per-request future
                    var requestId = "perm-" + UUID.randomUUID();
                    long timeoutMs = CancelAwareStreamContext.PERMISSION_RESPONSE_TIMEOUT_MS;

                    var future = context.registerPermissionRequest(requestId);
                    if (future.isCancelled()) {
                        return new ToolResult("Permission denied: request cancelled", true);
                    }
                    try {
                        var params = objectMapper.createObjectNode();
                        params.put("tool", delegate.name());
                        params.put("description", approval.prompt());
                        params.put("requestId", requestId);
                        context.sendNotification("permission.request", params);

                        // Each thread waits on its own future — no cross-delivery possible
                        var responseMsg = future.get(timeoutMs, TimeUnit.MILLISECONDS);

                        // Parse the permission response
                        var responseParams = responseMsg.get("params");
                        if (responseParams == null) {
                            return new ToolResult("Permission denied: invalid response from client", true);
                        }

                        boolean approved = responseParams.has("approved")
                                && responseParams.get("approved").asBoolean(false);
                        boolean remember = responseParams.has("remember")
                                && responseParams.get("remember").asBoolean(false);

                        if (!approved) {
                            log.info("Tool {} denied by user (requestId={})", delegate.name(), requestId);
                            return new ToolResult("Permission denied by user", true);
                        }

                        // If user chose "remember", grant session-level approval
                        if (remember) {
                            permissionManager.approveForSession(delegate.name());
                        }

                        log.info("Tool {} approved by user (requestId={}, remember={})",
                                delegate.name(), requestId, remember);
                        var result = executeWithPostHooks(finalInputJson);
                        maybeRecordFalsePositiveAndRollback(overrideStatus, evaluatedGateDecision, result);
                        return result;

                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        log.info("Tool {} permission request interrupted (requestId={})",
                                delegate.name(), requestId);
                        return new ToolResult("Permission denied: request interrupted", true);
                    } catch (TimeoutException e) {
                        log.info("Tool {} permission response timed out (requestId={})",
                                delegate.name(), requestId);
                        long timeoutSeconds = TimeUnit.MILLISECONDS.toSeconds(timeoutMs);
                        return new ToolResult(
                                "Permission pending timeout: no response from client within "
                                        + timeoutSeconds + "s", true);
                    } catch (CancellationException e) {
                        log.info("Tool {} permission request cancelled (requestId={})",
                                delegate.name(), requestId);
                        return new ToolResult("Permission denied: request cancelled", true);
                    } catch (ExecutionException e) {
                        log.error("Failed to receive permission response for tool {}: {}",
                                delegate.name(), e.getMessage());
                        return new ToolResult("Permission check failed: " + e.getMessage(), true);
                    } catch (IOException e) {
                        log.error("Failed to communicate permission request for tool {}: {}",
                                delegate.name(), e.getMessage());
                        return new ToolResult("Permission check failed: " + e.getMessage(), true);
                    } finally {
                        context.unregisterPermissionRequest(requestId);
                    }
                }
            }
        }

        /**
         * Executes the tool and fires PostToolUse or PostToolUseFailure hooks.
         */
        private ToolResult executeWithPostHooks(String inputJson) throws Exception {
            ToolResult result;
            try {
                result = delegate.execute(inputJson);
            } catch (Exception e) {
                // Fire PostToolUseFailure hook (non-blocking, fire-and-forget)
                var msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                firePostHookAsync(inputJson, null, msg);
                throw e;
            }

            // Fire PostToolUse or PostToolUseFailure based on result
            if (result.isError()) {
                var msg = result.output() != null ? result.output() : "Tool error";
                firePostHookAsync(inputJson, null, msg);
            } else {
                firePostHookAsync(inputJson, result.output(), null);
            }

            return result;
        }

        /**
         * Fires PostToolUse or PostToolUseFailure hooks on a virtual thread (fire-and-forget).
         */
        private void firePostHookAsync(String inputJson, String output, String error) {
            if (hookExecutor == null) return;
            Thread.ofVirtual().name("hook-post-" + delegate.name()).start(() -> {
                try {
                    var toolInput = objectMapper.readTree(inputJson);
                    if (toolInput == null || !toolInput.isObject()) {
                        toolInput = objectMapper.createObjectNode();
                    }
                    HookEvent event;
                    if (error != null) {
                        event = new HookEvent.PostToolUseFailure(
                                sessionId, cwd, delegate.name(), toolInput, error);
                    } else {
                        event = new HookEvent.PostToolUse(
                                sessionId, cwd, delegate.name(), toolInput,
                                output != null ? output : "");
                    }
                    hookExecutor.execute(event);
                } catch (Exception e) {
                    log.warn("Post-tool hook failed for {}: {}", delegate.name(), e.getMessage());
                }
            });
        }

        /**
         * Builds a human-readable description of what the tool will do based on its input.
         */
        private String buildToolDescription(String toolName, String inputJson) {
            try {
                var input = objectMapper.readTree(inputJson);
                return switch (toolName) {
                    case "bash" -> "Execute: " + (input.has("command")
                            ? input.get("command").asText() : "(unknown command)");
                    case "write_file" -> "Write to file: " + (input.has("file_path")
                            ? input.get("file_path").asText() : "(unknown path)");
                    case "edit_file" -> "Edit file: " + (input.has("file_path")
                            ? input.get("file_path").asText() : "(unknown path)");
                    case "read_file" -> "Read file: " + (input.has("file_path")
                            ? input.get("file_path").asText() : "(unknown path)");
                    case "glob" -> "Search files: " + (input.has("pattern")
                            ? input.get("pattern").asText() : "(unknown pattern)");
                    case "grep" -> "Search content: " + (input.has("pattern")
                            ? input.get("pattern").asText() : "(unknown pattern)");
                    case "list_directory" -> "List directory: " + (input.has("path")
                            ? input.get("path").asText() : "(working directory)");
                    case "web_fetch" -> "Fetch URL: " + (input.has("url")
                            ? input.get("url").asText() : "(unknown url)");
                    case "web_search" -> "Web search: " + (input.has("query")
                            ? input.get("query").asText() : "(unknown query)");
                    case "browser" -> "Browser " + (input.has("action")
                            ? input.get("action").asText() : "(unknown action)") +
                            (input.has("url") ? ": " + input.get("url").asText() : "");
                    case "applescript" -> "Execute AppleScript (" +
                            (input.has("script") ? input.get("script").asText().length() + " chars" : "unknown") + ")";
                    case "screen_capture" -> "Capture screenshot" +
                            (input.has("region") ? " (region: " + input.get("region").asText() + ")" : "");
                    default -> {
                        if (toolName.startsWith("mcp__")) {
                            // MCP tools: show server and tool name
                            var parts = toolName.split("__", 3);
                            var server = parts.length > 1 ? parts[1] : "unknown";
                            var tool = parts.length > 2 ? parts[2] : "unknown";
                            yield "MCP [" + server + "] " + tool;
                        } else {
                            yield "Execute tool: " + toolName;
                        }
                    }
                };
            } catch (Exception e) {
                return "Execute tool: " + toolName;
            }
        }

        private void emitGateNotification(String action,
                                          AntiPatternGateOverrideStatus overrideStatus,
                                          AntiPatternPreExecutionGate.Decision gateDecision) {
            try {
                var params = objectMapper.createObjectNode();
                params.put("sessionId", sessionId);
                params.put("tool", delegate.name());
                params.put("gate", "anti_pattern_preexec");
                params.put("action", action);
                if (gateDecision != null) {
                    if (!gateDecision.ruleId().isBlank()) params.put("ruleId", gateDecision.ruleId());
                    if (!gateDecision.reason().isBlank()) params.put("reason", gateDecision.reason());
                    if (!gateDecision.fallback().isBlank()) params.put("fallback", gateDecision.fallback());
                }
                if (overrideStatus != null && overrideStatus.active()) {
                    params.put("override", true);
                    params.put("overrideTtlSeconds", overrideStatus.ttlSecondsRemaining());
                    if (!overrideStatus.reason().isBlank()) {
                        params.put("overrideReason", overrideStatus.reason());
                    }
                } else {
                    params.put("override", false);
                }
                context.sendNotification("stream.gate", params);
            } catch (Exception e) {
                log.debug("Failed to emit stream.gate notification: {}", e.getMessage());
            }
        }

        private void recordBlockedRule(String ruleId) {
            if (antiPatternGateFeedbackStore == null || ruleId == null || ruleId.isBlank()) {
                return;
            }
            antiPatternGateFeedbackStore.recordBlocked(ruleId);
        }

        private void maybeRecordFalsePositiveAndRollback(
                AntiPatternGateOverrideStatus overrideStatus,
                AntiPatternPreExecutionGate.Decision evaluatedGateDecision,
                ToolResult toolResult) {
            if (antiPatternGateFeedbackStore == null || toolResult == null || toolResult.isError()) {
                return;
            }
            if (overrideStatus == null || !overrideStatus.active()) {
                return;
            }
            if (evaluatedGateDecision == null || evaluatedGateDecision.action() != AntiPatternPreExecutionGate.Action.BLOCK) {
                return;
            }
            String ruleId = evaluatedGateDecision.ruleId();
            if (ruleId == null || ruleId.isBlank()) {
                return;
            }
            boolean shouldRollback = antiPatternGateFeedbackStore.recordFalsePositive(ruleId);
            if (shouldRollback) {
                autoRollbackCandidateRule(ruleId);
            }
        }

        private void autoRollbackCandidateRule(String ruleId) {
            if (candidateStore == null || !ruleId.startsWith("candidate:")) {
                return;
            }
            String candidateId = ruleId.substring("candidate:".length());
            if (candidateId.isBlank()) {
                return;
            }
            try {
                var rollback = candidateStore.rollbackPromoted(
                        candidateId, "AUTO_ROLLBACK_FALSE_POSITIVE_RATE");
                if (rollback.isPresent()) {
                    log.info("Auto-rolled back anti-pattern candidate due to false-positive gate rate: {}",
                            candidateId);
                }
            } catch (Exception e) {
                log.warn("Failed auto rollback for candidate {}: {}", candidateId, e.getMessage());
            }
        }
    }

    private record AntiPatternGateOverride(Instant expiresAt, String reason) {}
}
