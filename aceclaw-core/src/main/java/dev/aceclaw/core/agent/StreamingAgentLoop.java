package dev.aceclaw.core.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.aceclaw.core.llm.*;
import dev.aceclaw.infra.event.AgentEvent;
import dev.aceclaw.infra.event.ToolEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.StructuredTaskScope;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Streaming variant of the ReAct execution engine.
 *
 * <p>Same logic as {@link AgentLoop} but uses {@link LlmClient#streamMessage(LlmRequest)}
 * instead of {@link LlmClient#sendMessage(LlmRequest)}. A {@link StreamEventHandler} callback
 * is invoked during streaming so callers can render tokens in real-time.
 *
 * <p>After each stream completes, the full response is accumulated and the stop reason
 * is checked. Tool use is handled identically to the non-streaming variant.
 *
 * <p>Supports automatic context compaction via an optional {@link MessageCompactor}.
 * When the context approaches the token limit, the compactor prunes old tool results
 * and thinking blocks, and if needed, generates an LLM summary of the conversation.
 */
public final class StreamingAgentLoop {

    private static final Logger log = LoggerFactory.getLogger(StreamingAgentLoop.class);

    /** Maximum characters allowed in a single tool result before truncation. */
    static final int MAX_TOOL_RESULT_CHARS = ToolResultTruncation.MAX_TOOL_RESULT_CHARS;

    /** Number of extra turns granted per auto-extension. */
    private static final int EXTENSION_TURNS = 15;

    /** Extra wall-clock time granted per auto-extension. */
    private static final Duration EXTENSION_WALL_TIME = Duration.ofSeconds(300);

    private final LlmClient llmClient;
    private final ToolRegistry toolRegistry;
    private final String model;
    private final String systemPrompt;
    private final int maxTokens;
    private final int thinkingBudget;
    private final int contextWindowTokens;
    private final MessageCompactor compactor;
    private final AgentLoopConfig config;

    /** The cancellation token for the current turn (set during runTurn, cleared on exit). */
    private volatile CancellationToken activeCancellationToken;

    /**
     * Creates a streaming agent loop with default token settings and no compaction.
     */
    public StreamingAgentLoop(LlmClient llmClient, ToolRegistry toolRegistry,
                              String model, String systemPrompt) {
        this(llmClient, toolRegistry, model, systemPrompt, 16384, 10240, 0, null, AgentLoopConfig.EMPTY);
    }

    /**
     * Creates a streaming agent loop with configurable token settings and no compaction.
     */
    public StreamingAgentLoop(LlmClient llmClient, ToolRegistry toolRegistry,
                              String model, String systemPrompt,
                              int maxTokens, int thinkingBudget) {
        this(llmClient, toolRegistry, model, systemPrompt, maxTokens, thinkingBudget, 0, null, AgentLoopConfig.EMPTY);
    }

    /**
     * Creates a streaming agent loop with full configuration including compaction.
     */
    public StreamingAgentLoop(LlmClient llmClient, ToolRegistry toolRegistry,
                              String model, String systemPrompt,
                              int maxTokens, int thinkingBudget,
                              MessageCompactor compactor) {
        this(llmClient, toolRegistry, model, systemPrompt, maxTokens, thinkingBudget, 0, compactor, AgentLoopConfig.EMPTY);
    }

    /**
     * Creates a streaming agent loop with full configuration including integrations.
     *
     * @param llmClient      the LLM client for streaming requests
     * @param toolRegistry   registry of available tools
     * @param model          model identifier
     * @param systemPrompt   system prompt for the LLM (may be null)
     * @param maxTokens      maximum tokens to generate per request
     * @param thinkingBudget tokens reserved for extended thinking (0 = disabled)
     * @param compactor      optional message compactor (null = no compaction)
     * @param config         optional integrations config
     */
    public StreamingAgentLoop(LlmClient llmClient, ToolRegistry toolRegistry,
                              String model, String systemPrompt,
                              int maxTokens, int thinkingBudget,
                              MessageCompactor compactor, AgentLoopConfig config) {
        this(llmClient, toolRegistry, model, systemPrompt, maxTokens, thinkingBudget, 0, compactor, config);
    }

    /**
     * Creates a streaming agent loop with full configuration including context window budget.
     *
     * @param llmClient           the LLM client for streaming requests
     * @param toolRegistry        registry of available tools
     * @param model               model identifier
     * @param systemPrompt        system prompt for the LLM (may be null)
     * @param maxTokens           maximum tokens to generate per request
     * @param thinkingBudget      tokens reserved for extended thinking (0 = disabled)
     * @param contextWindowTokens total context window size (0 = no budget check)
     * @param compactor           optional message compactor (null = no compaction)
     * @param config              optional integrations config
     */
    public StreamingAgentLoop(LlmClient llmClient, ToolRegistry toolRegistry,
                              String model, String systemPrompt,
                              int maxTokens, int thinkingBudget,
                              int contextWindowTokens,
                              MessageCompactor compactor, AgentLoopConfig config) {
        this.llmClient = Objects.requireNonNull(llmClient, "llmClient");
        this.toolRegistry = Objects.requireNonNull(toolRegistry, "toolRegistry");
        this.model = Objects.requireNonNull(model, "model");
        this.systemPrompt = systemPrompt;
        this.maxTokens = maxTokens;
        this.thinkingBudget = thinkingBudget;
        this.contextWindowTokens = contextWindowTokens;
        this.compactor = compactor;
        this.config = config != null ? config : AgentLoopConfig.EMPTY;
    }

    /**
     * Runs a single agent turn with streaming, invoking the handler for real-time token delivery.
     */
    public Turn runTurn(String userPrompt, List<Message> conversationHistory, StreamEventHandler handler)
            throws LlmException {
        return runTurn(userPrompt, conversationHistory, handler, null);
    }

    /**
     * Runs a single agent turn with streaming and cancellation support.
     */
    public Turn runTurn(String userPrompt, List<Message> conversationHistory,
                        StreamEventHandler handler, CancellationToken cancellationToken)
            throws LlmException {
        var eventHandler = handler != null ? handler : new StreamEventHandler() {};
        this.activeCancellationToken = cancellationToken;
        long turnStart = System.currentTimeMillis();
        int turnNumber = (int) conversationHistory.stream()
                .filter(m -> m instanceof Message.UserMessage).count() + 1;
        publishEvent(new AgentEvent.TurnStarted(config.sessionId(), turnNumber, Instant.now()));

        var newMessages = new ArrayList<Message>();
        var allMessages = new ArrayList<>(conversationHistory);

        // Add the user prompt
        var userMessage = Message.user(userPrompt);
        allMessages.add(userMessage);
        newMessages.add(userMessage);

        int totalInputTokens = 0;
        int totalOutputTokens = 0;
        int totalCacheCreationTokens = 0;
        int totalCacheReadTokens = 0;

        // Track actual input tokens from API for accurate compaction decisions
        int lastInputTokens = -1;

        // Track compaction result across iterations
        CompactionResult compactionResult = null;
        // Tracks repeated tool failures within this turn to emit generic fallback guidance.
        var toolFailureAdvisor = new ToolFailureAdvisor();
        int maxIterations = config.effectiveMaxIterations();

        try {
            for (int iteration = 0; iteration < maxIterations; iteration++) {

                // Watchdog budget check: before LLM call
                var watchdog = config.watchdog();
                if (watchdog != null) {
                    watchdog.checkBudget(iteration);
                }
                // If hard budget exhausted, token is cancelled -> Checkpoint 1 exits cleanly

                // Budget warning: inject once when approaching (but not yet at) the soft limit.
                // Skip if soft limit is already reached — extension/stop logic handles that below.
                if (watchdog != null && watchdog.isApproachingLimit()
                        && !watchdog.isSoftLimitReached()
                        && !watchdog.isWarningInjected()) {
                    String warning = buildBudgetWarning(watchdog);
                    allMessages.add(Message.user(warning));
                    newMessages.add(Message.user(warning));
                    watchdog.markWarningInjected();
                    log.info("Budget warning injected: {}", watchdog.remainingBudgetSummary());
                }

                // Soft limit: check progress, extend or stop
                boolean softBudgetStopped = false;
                if (watchdog != null && watchdog.isSoftLimitReached() && !watchdog.isExhausted()) {
                    var pd = config.progressDetector();
                    if (pd != null && !pd.isStalled()) {
                        watchdog.extendSoft(EXTENSION_TURNS, EXTENSION_WALL_TIME);
                        log.info("Budget auto-extended: extension #{}", watchdog.extensionCount());
                    } else {
                        log.warn("Soft budget reached with no progress, stopping");
                        softBudgetStopped = true;
                        if (cancellationToken != null) {
                            cancellationToken.cancel();
                        }
                    }
                }

                // Checkpoint 1: before LLM call
                // Check cancellation token OR soft-budget stop (handles null token case)
                boolean hardBudgetStopped = watchdog != null && watchdog.isExhausted();
                if (softBudgetStopped || hardBudgetStopped
                        || (cancellationToken != null && cancellationToken.isCancelled())) {
                    log.info("Cancellation detected before LLM call (iteration {})", iteration + 1);
                    var turn = buildCancelledTurn(newMessages, totalInputTokens, totalOutputTokens,
                            totalCacheCreationTokens, totalCacheReadTokens, compactionResult,
                            softBudgetStopped);
                    publishTurnCompleted(turnNumber, turnStart);
                    return turn;
                }

                // Check if context compaction is needed before this LLM call
                if (compactor != null) {
                    applyRequestTimePruning(allMessages);
                    compactionResult = checkAndCompact(
                            allMessages, lastInputTokens, compactionResult, eventHandler);
                }

                // Progress stall detection: inject pivot prompt if no progress in recent iterations
                var progressDetector = config.progressDetector();
                if (progressDetector != null && progressDetector.isStalled()) {
                    String pivotPrompt = progressDetector.buildPivotPrompt();
                    allMessages.add(Message.user(pivotPrompt));
                    newMessages.add(Message.user(pivotPrompt));
                    log.warn("Progress stall detected ({} iterations), injecting pivot prompt",
                            progressDetector.noProgressCount());
                    progressDetector.reset();
                }

                log.debug("Streaming ReAct iteration {} (messages: {})", iteration + 1, allMessages.size());

                var request = buildRequest(allMessages);

                // Stream the response, accumulating content blocks
                var accumulator = new StreamAccumulator(eventHandler);
                var session = llmClient.streamMessage(request);

                // Register the session with the cancellation token so cancel() propagates
                if (cancellationToken != null) {
                    cancellationToken.setActiveSession(session);
                }
                try {
                    session.onEvent(accumulator);
                } finally {
                    if (cancellationToken != null) {
                        cancellationToken.setActiveSession(null);
                    }
                }

                // Checkpoint 2: after stream completes
                if (cancellationToken != null && cancellationToken.isCancelled()) {
                    log.info("Cancellation detected after stream (iteration {})", iteration + 1);
                    var partialBlocks = accumulator.buildContentBlocks();
                    if (!partialBlocks.isEmpty()) {
                        var partialMessage = new Message.AssistantMessage(partialBlocks);
                        newMessages.add(partialMessage);
                    }
                    if (accumulator.usage != null) {
                        totalInputTokens += accumulator.usage.inputTokens();
                        totalOutputTokens += accumulator.usage.outputTokens();
                        totalCacheCreationTokens += accumulator.usage.cacheCreationInputTokens();
                        totalCacheReadTokens += accumulator.usage.cacheReadInputTokens();
                    }
                    var turn = buildCancelledTurn(newMessages, totalInputTokens, totalOutputTokens,
                            totalCacheCreationTokens, totalCacheReadTokens, compactionResult);
                    publishTurnCompleted(turnNumber, turnStart);
                    return turn;
                }

                // Check for stream errors
                if (accumulator.error != null) {
                    throw accumulator.error;
                }

                // Accumulate usage and track actual input tokens
                if (accumulator.usage != null) {
                    totalInputTokens += accumulator.usage.inputTokens();
                    totalOutputTokens += accumulator.usage.outputTokens();
                    totalCacheCreationTokens += accumulator.usage.cacheCreationInputTokens();
                    totalCacheReadTokens += accumulator.usage.cacheReadInputTokens();
                    lastInputTokens = accumulator.usage.effectiveInputTokens();
                    eventHandler.onUsageUpdate(lastInputTokens, totalInputTokens, totalOutputTokens);
                }

                // Build assistant message from accumulated content
                var contentBlocks = accumulator.buildContentBlocks();

                // Fallback: some local models return tool calls as plain text JSON
                var stopReason = accumulator.stopReason != null ? accumulator.stopReason : StopReason.ERROR;
                if (stopReason == StopReason.END_TURN) {
                    var converted = tryConvertTextToToolUse(contentBlocks);
                    if (converted != null) {
                        contentBlocks = converted;
                        stopReason = StopReason.TOOL_USE;
                        log.info("Converted text-based tool call to native ToolUse block");
                    }
                }

                var assistantMessage = new Message.AssistantMessage(contentBlocks);
                allMessages.add(assistantMessage);
                newMessages.add(assistantMessage);

                // Check stop reason
                switch (stopReason) {
                    case END_TURN, MAX_TOKENS, STOP_SEQUENCE, ERROR -> {
                        log.debug("Streaming turn complete: stopReason={}, iterations={}",
                                stopReason, iteration + 1);
                        var totalUsage = new Usage(
                                totalInputTokens, totalOutputTokens,
                                totalCacheCreationTokens, totalCacheReadTokens);
                        var turn = new Turn(newMessages, stopReason, totalUsage, compactionResult);
                        publishTurnCompleted(turnNumber, turnStart);
                        return turn;
                    }
                    case TOOL_USE -> {
                        var toolUseBlocks = contentBlocks.stream()
                                .filter(b -> b instanceof ContentBlock.ToolUse)
                                .map(b -> (ContentBlock.ToolUse) b)
                                .toList();
                        log.debug("Streaming tool use requested: {} tool(s)", toolUseBlocks.size());

                        List<ContentBlock.ToolResult> toolResults;
                        try {
                            toolResults = executeTools(toolUseBlocks, eventHandler, toolFailureAdvisor);
                        } catch (Exception e) {
                            // Safety net: ALWAYS produce tool_result to keep conversation valid.
                            // Without matching tool_result, the Anthropic API rejects the next request.
                            log.error("Tool execution failed unexpectedly, generating fallback results", e);
                            toolResults = toolUseBlocks.stream()
                                    .map(tu -> new ContentBlock.ToolResult(
                                            tu.id(),
                                            truncateToolResult("Tool execution error: " + e.getMessage(),
                                                    MAX_TOOL_RESULT_CHARS),
                                            true))
                                    .toList();
                        }
                        var toolResultMessage = Message.toolResults(toolResults);
                        allMessages.add(toolResultMessage);
                        newMessages.add(toolResultMessage);

                        // Checkpoint 3: after tool execution
                        if (cancellationToken != null && cancellationToken.isCancelled()) {
                            log.info("Cancellation detected after tool execution (iteration {})", iteration + 1);
                            var turn = buildCancelledTurn(newMessages, totalInputTokens, totalOutputTokens,
                                    totalCacheCreationTokens, totalCacheReadTokens, compactionResult);
                            publishTurnCompleted(turnNumber, turnStart);
                            return turn;
                        }
                    }
                }
            }

            // Exceeded max iterations
            log.warn("Streaming ReAct loop exceeded max iterations ({})", maxIterations);
            var totalUsage = new Usage(
                    totalInputTokens, totalOutputTokens,
                    totalCacheCreationTokens, totalCacheReadTokens);
            var turn = new Turn(newMessages, StopReason.END_TURN, totalUsage, compactionResult, true);
            publishTurnCompleted(turnNumber, turnStart);
            return turn;
        } catch (LlmException e) {
            publishEvent(new AgentEvent.TurnError(
                    config.sessionId(), turnNumber, e.getMessage(), Instant.now()));
            throw e;
        } finally {
            this.activeCancellationToken = null;
        }
    }

    private void publishTurnCompleted(int turnNumber, long turnStart) {
        long durationMs = System.currentTimeMillis() - turnStart;
        publishEvent(new AgentEvent.TurnCompleted(
                config.sessionId(), turnNumber, durationMs, Instant.now()));
    }

    /**
     * Builds a Turn result for a cancelled agent turn.
     */
    private Turn buildCancelledTurn(List<Message> newMessages,
                                    int totalInputTokens, int totalOutputTokens,
                                    int totalCacheCreationTokens, int totalCacheReadTokens,
                                    CompactionResult compactionResult) {
        return buildCancelledTurn(newMessages, totalInputTokens, totalOutputTokens,
                totalCacheCreationTokens, totalCacheReadTokens, compactionResult, false);
    }

    /**
     * Builds a Turn result for a cancelled agent turn, with optional soft-budget stop info.
     */
    private Turn buildCancelledTurn(List<Message> newMessages,
                                    int totalInputTokens, int totalOutputTokens,
                                    int totalCacheCreationTokens, int totalCacheReadTokens,
                                    CompactionResult compactionResult,
                                    boolean softBudgetStopped) {
        var totalUsage = new Usage(totalInputTokens, totalOutputTokens,
                totalCacheCreationTokens, totalCacheReadTokens);
        var watchdog = config.watchdog();
        boolean budgetExhausted = (watchdog != null && watchdog.isExhausted()) || softBudgetStopped;
        String reason = watchdog != null ? watchdog.exhaustionReason() : null;
        if (reason == null && softBudgetStopped) {
            reason = "soft_budget_no_progress";
        }
        return new Turn(newMessages, StopReason.END_TURN, totalUsage, compactionResult,
                false, budgetExhausted, reason);
    }

    /**
     * Builds a budget warning message to inject into the conversation when approaching
     * the soft limit, so the LLM can prioritize remaining work.
     */
    private static String buildBudgetWarning(WatchdogTimer watchdog) {
        return "[SYSTEM: Budget warning] You are approaching your execution budget. "
                + "Remaining: " + watchdog.remainingBudgetSummary() + ". "
                + "Prioritize completing the most important remaining work. "
                + "If you are making progress, the budget will be extended automatically. "
                + "If stuck, wrap up with a summary of what was accomplished and what remains.";
    }

    /**
     * Checks if compaction is needed and runs it if so.
     */
    private CompactionResult checkAndCompact(
            ArrayList<Message> allMessages, int lastInputTokens,
            CompactionResult previousResult, StreamEventHandler handler) throws LlmException {

        boolean needsCompaction;
        if (lastInputTokens > 0) {
            needsCompaction = compactor.needsCompaction(lastInputTokens);
        } else {
            needsCompaction = compactor.needsCompactionEstimate(
                    allMessages, systemPrompt, toolRegistry.toDefinitions());
        }

        if (!needsCompaction) {
            return previousResult;
        }

        log.info("Context compaction triggered (lastInputTokens={}, threshold={})",
                lastInputTokens, compactor.config().triggerTokens());

        publishEvent(new AgentEvent.CompactionTriggered(
                config.sessionId(), allMessages.size(), 0, Instant.now()));

        var result = compactor.compact(allMessages, systemPrompt);

        // Replace allMessages with compacted version
        allMessages.clear();
        allMessages.addAll(result.compactedMessages());

        // Publish compaction event with actual message counts
        publishEvent(new AgentEvent.CompactionTriggered(
                config.sessionId(), result.originalTokenEstimate(),
                result.compactedTokenEstimate(), Instant.now()));

        // Persist extracted context to auto-memory
        if (config.memoryHandler() != null && !result.extractedContext().isEmpty()) {
            try {
                config.memoryHandler().persist(
                        result.extractedContext(),
                        "compaction:" + (config.sessionId() != null ? config.sessionId() : "unknown"));
            } catch (Exception e) {
                log.warn("Failed to persist compaction context to memory: {}", e.getMessage());
            }
        }

        // Notify handler about compaction
        handler.onCompaction(
                result.originalTokenEstimate(),
                result.compactedTokenEstimate(),
                result.phaseReached().name());

        log.info("Compaction complete: phase={}, reduction={} -> {} estimated tokens ({}% reduction)",
                result.phaseReached(), result.originalTokenEstimate(),
                result.compactedTokenEstimate(),
                String.format("%.1f", result.reductionPercent()));

        return result;
    }

    private void applyRequestTimePruning(ArrayList<Message> allMessages) {
        if (allMessages.isEmpty()) {
            return;
        }
        var pruneResult = compactor.pruneForRequest(allMessages, systemPrompt, toolRegistry.toDefinitions());
        if (!pruneResult.applied()) {
            return;
        }
        allMessages.clear();
        allMessages.addAll(pruneResult.messages());
        log.info("Request-time pruning applied: {} -> {} estimated tokens",
                pruneResult.originalTokenEstimate(), pruneResult.prunedTokenEstimate());
    }

    private LlmRequest buildRequest(List<Message> messages) {
        int effectiveThinkingBudget = thinkingBudget;
        if (effectiveThinkingBudget > 0 && !llmClient.capabilities().supportsExtendedThinking()) {
            effectiveThinkingBudget = 0;
        }

        var builder = LlmRequest.builder()
                .model(model)
                .messages(messages)
                .maxTokens(maxTokens)
                .thinkingBudget(effectiveThinkingBudget);

        if (systemPrompt != null && !systemPrompt.isBlank()) {
            builder.systemPrompt(systemPrompt);
        }

        var toolDefs = toolRegistry.toDefinitions();

        // Pre-flight context budget check: if contextWindowTokens is configured,
        // verify the total request fits within the available window.
        // If over budget, truncate tool descriptions as a soft guardrail.
        if (contextWindowTokens > 0) {
            var budget = ContextEstimator.checkBudget(
                    systemPrompt, toolDefs, messages, contextWindowTokens, maxTokens);
            if (budget.overBudget()) {
                log.warn("Pre-flight budget exceeded: system={}t, tools={}t, messages={}t, " +
                                "total={}t, available={}t (excess={}t)",
                        budget.systemPromptTokens(), budget.toolDefinitionTokens(),
                        budget.messageTokens(), budget.totalEstimated(),
                        budget.availableTokens(), budget.excessTokens());

                if (!toolDefs.isEmpty()) {
                    // Compute a character budget for tool descriptions:
                    // available tokens minus system prompt and messages, converted to chars
                    int toolTokenBudget = budget.availableTokens()
                            - budget.systemPromptTokens() - budget.messageTokens();
                    int toolDescCharBudget = Math.max(0, toolTokenBudget * 4);
                    if (toolDescCharBudget == 0) {
                        log.warn("No budget remaining for tool descriptions; " +
                                "system prompt and messages alone exceed context window");
                    }
                    toolDefs = toolRegistry.toDefinitions(toolDescCharBudget);

                    log.info("Tool descriptions truncated to fit {} chars", toolDescCharBudget);
                }
            }
        }

        if (!toolDefs.isEmpty()) {
            builder.tools(toolDefs);
        }

        return builder.build();
    }

    /** Interval between heartbeat signals during tool execution. */
    private static final long TOOL_HEARTBEAT_INTERVAL_MS = 15_000;

    /**
     * Executes tool calls, using virtual threads for parallel execution.
     * A heartbeat thread sends periodic signals while tools are running.
     */
    private List<ContentBlock.ToolResult> executeTools(
            List<ContentBlock.ToolUse> toolUseBlocks,
            StreamEventHandler handler,
            ToolFailureAdvisor failureAdvisor) {
        Objects.requireNonNull(failureAdvisor, "failureAdvisor");

        var heartbeatDone = new AtomicBoolean(false);
        var heartbeat = Thread.ofVirtual()
                .name("tool-heartbeat")
                .start(() -> heartbeatLoop(handler, heartbeatDone, "tool_execution"));

        try {
            if (toolUseBlocks.size() == 1) {
                return List.of(executeSingleTool(toolUseBlocks.getFirst(), handler, failureAdvisor));
            }

            try (var scope = new StructuredTaskScope.ShutdownOnFailure()) {
                var subtasks = toolUseBlocks.stream()
                        .map(toolUse -> scope.fork(() -> executeSingleTool(toolUse, handler, failureAdvisor)))
                        .toList();

                scope.join();

                return subtasks.stream()
                        .map(StructuredTaskScope.Subtask::get)
                        .toList();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.error("Tool execution interrupted");
                return toolUseBlocks.stream()
                        .map(tu -> new ContentBlock.ToolResult(tu.id(), "Tool execution interrupted", true))
                        .toList();
            } catch (Exception e) {
                // Subtask::get() throws IllegalStateException for FAILED/UNAVAILABLE subtasks.
                // Must return fallback results to prevent orphan tool_use in conversation history.
                log.error("Parallel tool execution failed: {}", e.getMessage(), e);
                return toolUseBlocks.stream()
                        .map(tu -> new ContentBlock.ToolResult(
                                tu.id(),
                                truncateToolResult("Parallel tool execution error: " + e.getMessage(),
                                        MAX_TOOL_RESULT_CHARS),
                                true))
                        .toList();
            }
        } finally {
            heartbeatDone.set(true);
            heartbeat.interrupt();
            try {
                heartbeat.join(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * Sends periodic heartbeat events until the done flag is set.
     */
    private static void heartbeatLoop(StreamEventHandler handler, AtomicBoolean done, String phase) {
        while (!done.get()) {
            try {
                Thread.sleep(TOOL_HEARTBEAT_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            if (!done.get()) {
                handler.onHeartbeat(new StreamEvent.Heartbeat(phase));
            }
        }
    }

    private ContentBlock.ToolResult executeSingleTool(
            ContentBlock.ToolUse toolUse,
            StreamEventHandler handler,
            ToolFailureAdvisor failureAdvisor) {
        Objects.requireNonNull(failureAdvisor, "failureAdvisor");
        long toolStart = System.currentTimeMillis();

        // Doom loop pre-check: block identical failing calls before they execute
        String doomLoopWarnAdvice = null;
        var doomLoopDetector = config.doomLoopDetector();
        if (doomLoopDetector != null) {
            var verdict = doomLoopDetector.preCheck(toolUse.name(), toolUse.inputJson());
            switch (verdict) {
                case DoomLoopDetector.Verdict.Block block -> {
                    log.warn("Doom loop blocked: tool={}, failCount={}", toolUse.name(), block.failCount());
                    long toolDuration = System.currentTimeMillis() - toolStart;
                    String finalOutput = truncateToolResult(block.reason(), MAX_TOOL_RESULT_CHARS);
                    handler.onToolCompleted(toolUse.id(), toolUse.name(), toolDuration, true, summarizeError(finalOutput));
                    recordProgressResult(toolUse, true, finalOutput);
                    recordDoomLoopResult(toolUse, true, block.reason());
                    return new ContentBlock.ToolResult(toolUse.id(), finalOutput, true);
                }
                case DoomLoopDetector.Verdict.Warn warn -> {
                    log.info("Doom loop warning: tool={}, failCount={}", toolUse.name(), warn.failCount());
                    doomLoopWarnAdvice = warn.advice();
                }
                case DoomLoopDetector.Verdict.Allow _ -> { /* proceed normally */ }
            }
        }

        String preflightBlock = failureAdvisor.preflightBlockMessage(toolUse.name());
        if (preflightBlock != null && !preflightBlock.isBlank()) {
            long toolDuration = System.currentTimeMillis() - toolStart;
            String finalOutput = truncateToolResult(preflightBlock, MAX_TOOL_RESULT_CHARS);
            handler.onToolCompleted(toolUse.id(), toolUse.name(), toolDuration, true, summarizeError(finalOutput));
            // Note: do NOT record into DoomLoopDetector — ToolFailureAdvisor's per-turn
            // circuit breaker is independent; recording here would inflate session-wide counts.
            return new ContentBlock.ToolResult(toolUse.id(), finalOutput, true);
        }

        // Check permission before execution
        if (config.permissionChecker() != null) {
            try {
                var permResult = config.permissionChecker().check(toolUse.name(), toolUse.inputJson());
                if (permResult == null || !permResult.allowed()) {
                    String reason = permResult != null ? permResult.reason() : "permission check returned null";
                    log.info("Tool {} denied: {}", toolUse.name(), reason);
                    publishEvent(new ToolEvent.PermissionDenied(
                            config.sessionId(), toolUse.name(), reason, Instant.now()));
                    long toolDuration = System.currentTimeMillis() - toolStart;
                    String finalOutput = withFailureAdviceAndTruncation(
                            "Permission denied: " + reason, toolUse.name(), failureAdvisor);
                    handler.onToolCompleted(toolUse.id(), toolUse.name(), toolDuration, true,
                            summarizeError(finalOutput));
                    return new ContentBlock.ToolResult(toolUse.id(), finalOutput, true);
                }
            } catch (Exception e) {
                log.error("Permission checker threw for tool {}: {}", toolUse.name(), e.getMessage(), e);
                publishEvent(new ToolEvent.PermissionDenied(
                        config.sessionId(), toolUse.name(), "checker error: " + e.getMessage(), Instant.now()));
                long toolDuration = System.currentTimeMillis() - toolStart;
                String finalOutput = withFailureAdviceAndTruncation(
                        "Permission denied: " + e.getMessage(), toolUse.name(), failureAdvisor);
                handler.onToolCompleted(toolUse.id(), toolUse.name(), toolDuration, true,
                        summarizeError(finalOutput));
                return new ContentBlock.ToolResult(toolUse.id(), finalOutput, true);
            }
        }

        var toolOpt = toolRegistry.get(toolUse.name());
        if (toolOpt.isEmpty()) {
            log.warn("Unknown tool requested: {}", toolUse.name());
            long toolDuration = System.currentTimeMillis() - toolStart;
            String finalOutput = withFailureAdviceAndTruncation(
                    "Unknown tool: " + toolUse.name(), toolUse.name(), failureAdvisor);
            handler.onToolCompleted(toolUse.id(), toolUse.name(), toolDuration, true,
                    summarizeError(finalOutput));
            return new ContentBlock.ToolResult(toolUse.id(), finalOutput, true);
        }

        var tool = toolOpt.get();

        // Propagate cancellation token to tools that spawn sub-agents
        if (tool instanceof CancellationAware ca) {
            ca.setCancellationToken(activeCancellationToken);
        }

        publishEvent(new ToolEvent.Invoked(config.sessionId(), tool.name(), Instant.now()));

        try {
            log.debug("Executing tool: {} (id: {})", tool.name(), toolUse.id());
            var result = tool.execute(toolUse.inputJson());
            long toolDuration = System.currentTimeMillis() - toolStart;
            String output = truncateToolResult(result.output(), MAX_TOOL_RESULT_CHARS);
            if (output.length() != result.output().length()) {
                log.debug("Tool {} result truncated: {} -> {} chars [truncated]",
                        tool.name(), result.output().length(), output.length());
            }
            log.debug("Tool {} completed: isError={}", tool.name(), result.isError());
            publishEvent(new ToolEvent.Completed(
                    config.sessionId(), tool.name(), toolDuration, result.isError(), Instant.now()));
            recordMetrics(tool.name(), !result.isError(), toolDuration);
            String finalOutput = output;
            String errorPreview = null;
            if (result.isError()) {
                finalOutput = withFailureAdviceAndTruncation(output, tool.name(), failureAdvisor);
                if (doomLoopWarnAdvice != null) {
                    finalOutput = truncateToolResult(
                            finalOutput + "\n\n[doom-loop-warning] " + doomLoopWarnAdvice,
                            MAX_TOOL_RESULT_CHARS);
                }
                errorPreview = summarizeError(finalOutput);
            }
            handler.onToolCompleted(toolUse.id(), tool.name(), toolDuration, result.isError(), errorPreview);
            recordDoomLoopResult(toolUse, result.isError(), result.isError() ? finalOutput : null);
            recordProgressResult(toolUse, result.isError(), output);
            return new ContentBlock.ToolResult(toolUse.id(), finalOutput, result.isError());
        } catch (Exception e) {
            long toolDuration = System.currentTimeMillis() - toolStart;
            log.error("Tool {} threw exception: {}", tool.name(), e.getMessage(), e);
            publishEvent(new ToolEvent.Completed(
                    config.sessionId(), tool.name(), toolDuration, true, Instant.now()));
            recordMetrics(tool.name(), false, toolDuration);
            String finalOutput = withFailureAdviceAndTruncation("Tool error: " + e.getMessage(), tool.name(), failureAdvisor);
            handler.onToolCompleted(toolUse.id(), tool.name(), toolDuration, true, summarizeError(finalOutput));
            recordDoomLoopResult(toolUse, true, finalOutput);
            recordProgressResult(toolUse, true, finalOutput);
            return new ContentBlock.ToolResult(toolUse.id(), finalOutput, true);
        }
    }

    private static String withFailureAdviceAndTruncation(
            String baseMessage,
            String toolName,
            ToolFailureAdvisor failureAdvisor) {
        String safeBase = baseMessage == null ? "Tool error" : baseMessage;
        String advice = failureAdvisor.onFailure(toolName, safeBase).advice();
        String combined = (advice == null || advice.isBlank())
                ? safeBase
                : safeBase + "\n\n[auto-fallback-advice] " + advice;
        return truncateToolResult(combined, MAX_TOOL_RESULT_CHARS);
    }

    private static String summarizeError(String errorText) {
        if (errorText == null || errorText.isBlank()) {
            return null;
        }
        var normalized = errorText.replace('\n', ' ').trim();
        if (normalized.length() <= 120) {
            return normalized;
        }
        return normalized.substring(0, 117) + "...";
    }

    /**
     * Truncates a tool result to fit within the given character limit.
     */
    static String truncateToolResult(String output, int maxChars) {
        return ToolResultTruncation.truncate(output, maxChars);
    }

    private void recordDoomLoopResult(ContentBlock.ToolUse toolUse, boolean isError, String errorText) {
        var detector = config.doomLoopDetector();
        if (detector != null) {
            try {
                detector.recordResult(toolUse.name(), toolUse.inputJson(), isError, errorText);
            } catch (Exception e) {
                log.warn("Failed to record doom loop result for tool {}: {}", toolUse.name(), e.getMessage());
            }
        }
    }

    private void recordProgressResult(ContentBlock.ToolUse toolUse, boolean isError, String output) {
        var detector = config.progressDetector();
        if (detector != null) {
            try {
                detector.recordToolResult(toolUse.name(), toolUse.inputJson(), isError, output);
            } catch (Exception e) {
                log.warn("Failed to record progress result for tool {}: {}", toolUse.name(), e.getMessage());
            }
        }
    }

    private void publishEvent(dev.aceclaw.infra.event.AceClawEvent event) {
        if (config.eventBus() != null) {
            config.eventBus().publish(event);
        }
    }

    private void recordMetrics(String toolName, boolean success, long durationMs) {
        if (config.metricsCollector() != null) {
            try {
                config.metricsCollector().record(toolName, success, durationMs);
            } catch (Exception e) {
                log.warn("Failed to record metrics for tool {} ({}ms): {}",
                        toolName, durationMs, e.getMessage());
            }
        }
    }

    /** Shared JSON mapper for text-to-tool-call fallback parsing. */
    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * Detects text content that is actually a tool call JSON from models that
     * don't support native tool calling.
     */
    private List<ContentBlock> tryConvertTextToToolUse(List<ContentBlock> blocks) {
        boolean hasToolUse = blocks.stream().anyMatch(b -> b instanceof ContentBlock.ToolUse);
        if (hasToolUse) return null;

        String fullText = blocks.stream()
                .filter(b -> b instanceof ContentBlock.Text)
                .map(b -> ((ContentBlock.Text) b).text())
                .reduce("", (a, b) -> a + b)
                .trim();

        if (fullText.isEmpty() || fullText.charAt(0) != '{') return null;

        try {
            JsonNode node = JSON.readTree(fullText);
            if (!node.isObject()) return null;

            String toolName = node.path("name").asText(null);
            JsonNode arguments = node.get("arguments");

            if (toolName == null || toolName.isBlank()) return null;
            if (arguments == null || !arguments.isObject()) return null;
            if (toolRegistry.get(toolName).isEmpty()) return null;

            String toolId = "text-tool-" + UUID.randomUUID().toString().substring(0, 8);
            String argsJson = JSON.writeValueAsString(arguments);

            var result = new ArrayList<ContentBlock>();
            for (var block : blocks) {
                if (block instanceof ContentBlock.Text) {
                    // Skip — replaced by ToolUse
                } else {
                    result.add(block);
                }
            }
            result.add(new ContentBlock.ToolUse(toolId, toolName, argsJson));

            log.debug("Parsed text-based tool call: tool={}, args={}", toolName, argsJson);
            return List.copyOf(result);

        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Accumulates stream events into a complete response while forwarding events
     * to the caller's handler for real-time rendering.
     */
    private static final class StreamAccumulator implements StreamEventHandler {

        private final StreamEventHandler delegate;

        private final StringBuilder textBuilder = new StringBuilder();
        private final StringBuilder thinkingBuilder = new StringBuilder();
        private final List<ContentBlock> contentBlocks = new ArrayList<>();

        private String currentToolUseId;
        private String currentToolUseName;
        private final StringBuilder toolUseJsonBuilder = new StringBuilder();
        private boolean inToolUse;
        private boolean inThinking;

        StopReason stopReason;
        /** Final merged usage: input_tokens from message_start + output_tokens from message_delta. */
        Usage usage;
        /** Usage from message_start (carries accurate input_tokens for Anthropic). */
        private Usage startUsage;
        LlmException error;

        StreamAccumulator(StreamEventHandler delegate) {
            this.delegate = delegate;
        }

        @Override
        public void onMessageStart(StreamEvent.MessageStart event) {
            if (event.usage() != null) {
                this.startUsage = event.usage();
            }
            delegate.onMessageStart(event);
        }

        @Override
        public void onContentBlockStart(StreamEvent.ContentBlockStart event) {
            flushTextBlock();
            flushThinkingBlock();

            if (event.block() instanceof ContentBlock.ToolUse toolUse) {
                inToolUse = true;
                currentToolUseId = toolUse.id();
                currentToolUseName = toolUse.name();
                toolUseJsonBuilder.setLength(0);
            } else if (event.block() instanceof ContentBlock.Thinking) {
                inThinking = true;
                thinkingBuilder.setLength(0);
            }

            delegate.onContentBlockStart(event);
        }

        @Override
        public void onTextDelta(StreamEvent.TextDelta event) {
            textBuilder.append(event.text());
            delegate.onTextDelta(event);
        }

        @Override
        public void onThinkingDelta(StreamEvent.ThinkingDelta event) {
            thinkingBuilder.append(event.text());
            delegate.onThinkingDelta(event);
        }

        @Override
        public void onToolUseDelta(StreamEvent.ToolUseDelta event) {
            if (event.partialJson() != null) {
                toolUseJsonBuilder.append(event.partialJson());
            }
            if (event.name() != null && currentToolUseName == null) {
                currentToolUseName = event.name();
            }
            delegate.onToolUseDelta(event);
        }

        @Override
        public void onContentBlockStop(StreamEvent.ContentBlockStop event) {
            if (inToolUse) {
                contentBlocks.add(new ContentBlock.ToolUse(
                        currentToolUseId, currentToolUseName, toolUseJsonBuilder.toString()));
                inToolUse = false;
                currentToolUseId = null;
                currentToolUseName = null;
                toolUseJsonBuilder.setLength(0);
            } else if (inThinking) {
                flushThinkingBlock();
            } else {
                flushTextBlock();
            }
            delegate.onContentBlockStop(event);
        }

        @Override
        public void onMessageDelta(StreamEvent.MessageDelta event) {
            this.stopReason = event.stopReason();
            // Merge: input_tokens from message_start + output_tokens from message_delta.
            // Anthropic sends input_tokens only in message_start, and output_tokens only in message_delta.
            this.usage = mergeUsage(startUsage, event.usage());
            delegate.onMessageDelta(event);
        }

        /**
         * Merges usage from {@code message_start} (which carries {@code input_tokens})
         * with usage from {@code message_delta} (which carries {@code output_tokens}).
         * If either is null, returns the other. Cache tokens are summed from both.
         */
        private static Usage mergeUsage(Usage start, Usage delta) {
            if (start == null) return delta;
            if (delta == null) return start;
            return new Usage(
                    Math.max(start.inputTokens(), delta.inputTokens()),
                    Math.max(start.outputTokens(), delta.outputTokens()),
                    start.cacheCreationInputTokens() + delta.cacheCreationInputTokens(),
                    start.cacheReadInputTokens() + delta.cacheReadInputTokens());
        }

        @Override
        public void onComplete(StreamEvent.StreamComplete event) {
            flushTextBlock();
            flushThinkingBlock();
            delegate.onComplete(event);
        }

        @Override
        public void onError(StreamEvent.StreamError event) {
            this.error = event.error();
            delegate.onError(event);
        }

        private void flushTextBlock() {
            if (!textBuilder.isEmpty()) {
                contentBlocks.add(new ContentBlock.Text(textBuilder.toString()));
                textBuilder.setLength(0);
            }
        }

        private void flushThinkingBlock() {
            if (inThinking && !thinkingBuilder.isEmpty()) {
                contentBlocks.add(new ContentBlock.Thinking(thinkingBuilder.toString()));
                thinkingBuilder.setLength(0);
            }
            inThinking = false;
        }

        List<ContentBlock> buildContentBlocks() {
            flushTextBlock();
            flushThinkingBlock();
            return List.copyOf(contentBlocks);
        }
    }
}
