package dev.aceclaw.core.agent;

import dev.aceclaw.core.llm.*;
import dev.aceclaw.infra.event.AgentEvent;
import dev.aceclaw.infra.event.EventBus;
import dev.aceclaw.infra.event.ToolEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.StructuredTaskScope;

/**
 * The core ReAct (Reason + Act) execution engine.
 *
 * <p>Orchestrates an iterative loop between the LLM and tool execution:
 * <ol>
 *   <li>Send the conversation (with tool definitions) to the LLM</li>
 *   <li>If the LLM returns {@link StopReason#END_TURN}, return the response</li>
 *   <li>If the LLM returns {@link StopReason#TOOL_USE}, execute the requested tools,
 *       append results to the conversation, and loop back to step 1</li>
 *   <li>If the LLM returns {@link StopReason#MAX_TOKENS}, return the partial response</li>
 * </ol>
 *
 * <p>Tool execution uses virtual threads via {@link StructuredTaskScope} for
 * parallel execution when multiple tools are requested in a single response.
 */
public final class AgentLoop {

    private static final Logger log = LoggerFactory.getLogger(AgentLoop.class);

    private final LlmClient llmClient;
    private final ToolRegistry toolRegistry;
    private final String model;
    private final String systemPrompt;
    private final AgentLoopConfig config;

    /**
     * Creates an agent loop.
     *
     * @param llmClient    the LLM client for sending requests
     * @param toolRegistry registry of available tools
     * @param model        model identifier (e.g. "claude-sonnet-4-5-20250929")
     * @param systemPrompt system prompt for the LLM (may be null)
     */
    public AgentLoop(LlmClient llmClient, ToolRegistry toolRegistry, String model, String systemPrompt) {
        this(llmClient, toolRegistry, model, systemPrompt, AgentLoopConfig.EMPTY);
    }

    /**
     * Creates an agent loop with optional integrations.
     *
     * @param llmClient    the LLM client for sending requests
     * @param toolRegistry registry of available tools
     * @param model        model identifier
     * @param systemPrompt system prompt for the LLM (may be null)
     * @param config       optional integrations config
     */
    public AgentLoop(LlmClient llmClient, ToolRegistry toolRegistry, String model,
                     String systemPrompt, AgentLoopConfig config) {
        this.llmClient = llmClient;
        this.toolRegistry = toolRegistry;
        this.model = model;
        this.systemPrompt = systemPrompt;
        this.config = config != null ? config : AgentLoopConfig.EMPTY;
    }

    /**
     * Runs a single agent turn: sends the user prompt through the ReAct loop
     * until the LLM produces a final response or the iteration limit is reached.
     *
     * @param userPrompt          the user's prompt text
     * @param conversationHistory previous messages in the conversation
     * @return the turn result containing all new messages and usage statistics
     * @throws LlmException if the LLM call fails
     */
    public Turn runTurn(String userPrompt, List<Message> conversationHistory) throws LlmException {
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
        int maxIterations = config.effectiveMaxIterations();

        try {
            for (int iteration = 0; iteration < maxIterations; iteration++) {
                log.debug("ReAct iteration {} (messages: {})", iteration + 1, allMessages.size());

                // Build and send the LLM request
                var request = buildRequest(allMessages);
                var response = llmClient.sendMessage(request);

                // Accumulate usage
                var usage = response.usage();
                totalInputTokens += usage.inputTokens();
                totalOutputTokens += usage.outputTokens();
                totalCacheCreationTokens += usage.cacheCreationInputTokens();
                totalCacheReadTokens += usage.cacheReadInputTokens();

                // Add assistant message to conversation
                var assistantMessage = new Message.AssistantMessage(response.content());
                allMessages.add(assistantMessage);
                newMessages.add(assistantMessage);

                // Check stop reason
                switch (response.stopReason()) {
                    case END_TURN, MAX_TOKENS, STOP_SEQUENCE, ERROR -> {
                        log.debug("Turn complete: stopReason={}, iterations={}",
                                response.stopReason(), iteration + 1);
                        var totalUsage = new Usage(
                                totalInputTokens, totalOutputTokens,
                                totalCacheCreationTokens, totalCacheReadTokens);
                        var turn = new Turn(newMessages, response.stopReason(), totalUsage);
                        long durationMs = System.currentTimeMillis() - turnStart;
                        publishEvent(new AgentEvent.TurnCompleted(
                                config.sessionId(), turnNumber, durationMs, Instant.now()));
                        return turn;
                    }
                    case TOOL_USE -> {
                        var toolUseBlocks = response.toolUseBlocks();
                        log.debug("Tool use requested: {} tool(s)", toolUseBlocks.size());

                        var toolResults = executeTools(toolUseBlocks);
                        var toolResultMessage = Message.toolResults(toolResults);
                        allMessages.add(toolResultMessage);
                        newMessages.add(toolResultMessage);
                    }
                }
            }

            // Exceeded max iterations — return what we have
            log.warn("ReAct loop exceeded max iterations ({})", maxIterations);
            var totalUsage = new Usage(
                    totalInputTokens, totalOutputTokens,
                    totalCacheCreationTokens, totalCacheReadTokens);
            var turn = new Turn(newMessages, StopReason.END_TURN, totalUsage, null, true);
            long durationMs = System.currentTimeMillis() - turnStart;
            publishEvent(new AgentEvent.TurnCompleted(
                    config.sessionId(), turnNumber, durationMs, Instant.now()));
            return turn;
        } catch (LlmException e) {
            publishEvent(new AgentEvent.TurnError(
                    config.sessionId(), turnNumber, e.getMessage(), Instant.now()));
            throw e;
        }
    }

    private LlmRequest buildRequest(List<Message> messages) {
        var builder = LlmRequest.builder()
                .model(model)
                .messages(messages);

        if (systemPrompt != null && !systemPrompt.isBlank()) {
            builder.systemPrompt(systemPrompt);
        }

        var toolDefs = toolRegistry.toDefinitions();
        if (!toolDefs.isEmpty()) {
            builder.tools(toolDefs);
        }

        return builder.build();
    }

    /**
     * Executes tool calls, using virtual threads for parallel execution
     * when multiple tools are requested.
     */
    private List<ContentBlock.ToolResult> executeTools(List<ContentBlock.ToolUse> toolUseBlocks) {
        if (toolUseBlocks.size() == 1) {
            // Single tool: execute directly, no need for structured concurrency
            return List.of(executeSingleTool(toolUseBlocks.getFirst()));
        }

        // Multiple tools: execute in parallel using StructuredTaskScope
        try (var scope = new StructuredTaskScope.ShutdownOnFailure()) {
            var subtasks = toolUseBlocks.stream()
                    .map(toolUse -> scope.fork(() -> executeSingleTool(toolUse)))
                    .toList();

            scope.join();
            // Do not throwIfFailed — individual tool errors are captured in ToolResult

            return subtasks.stream()
                    .map(StructuredTaskScope.Subtask::get)
                    .toList();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Tool execution interrupted");
            return toolUseBlocks.stream()
                    .map(tu -> new ContentBlock.ToolResult(tu.id(), "Tool execution interrupted", true))
                    .toList();
        }
    }

    private ContentBlock.ToolResult executeSingleTool(ContentBlock.ToolUse toolUse) {
        // Check permission before execution
        if (config.permissionChecker() != null) {
            try {
                var permResult = config.permissionChecker().check(toolUse.name(), toolUse.inputJson());
                if (permResult == null || !permResult.allowed()) {
                    String reason = permResult != null ? permResult.reason() : "permission check returned null";
                    log.info("Tool {} denied: {}", toolUse.name(), reason);
                    publishEvent(new ToolEvent.PermissionDenied(
                            config.sessionId(), toolUse.name(), reason, Instant.now()));
                    return new ContentBlock.ToolResult(
                            toolUse.id(), "Permission denied: " + toolUse.name(), true);
                }
            } catch (Exception e) {
                log.error("Permission checker threw for tool {}: {}", toolUse.name(), e.getMessage(), e);
                publishEvent(new ToolEvent.PermissionDenied(
                        config.sessionId(), toolUse.name(), "checker error: " + e.getMessage(), Instant.now()));
                return new ContentBlock.ToolResult(
                        toolUse.id(), "Permission denied: " + toolUse.name(), true);
            }
        }

        var toolOpt = toolRegistry.get(toolUse.name());
        if (toolOpt.isEmpty()) {
            log.warn("Unknown tool requested: {}", toolUse.name());
            return new ContentBlock.ToolResult(toolUse.id(), "Unknown tool: " + toolUse.name(), true);
        }

        var tool = toolOpt.get();
        publishEvent(new ToolEvent.Invoked(config.sessionId(), tool.name(), Instant.now()));
        long toolStart = System.currentTimeMillis();

        try {
            log.debug("Executing tool: {} (id: {})", tool.name(), toolUse.id());
            var result = tool.execute(toolUse.inputJson());
            long toolDuration = System.currentTimeMillis() - toolStart;
            log.debug("Tool {} completed: isError={}", tool.name(), result.isError());
            publishEvent(new ToolEvent.Completed(
                    config.sessionId(), tool.name(), toolDuration, result.isError(), Instant.now()));
            return new ContentBlock.ToolResult(toolUse.id(), result.output(), result.isError());
        } catch (Exception e) {
            long toolDuration = System.currentTimeMillis() - toolStart;
            log.error("Tool {} threw exception: {}", tool.name(), e.getMessage(), e);
            publishEvent(new ToolEvent.Completed(
                    config.sessionId(), tool.name(), toolDuration, true, Instant.now()));
            return new ContentBlock.ToolResult(toolUse.id(), "Tool error: " + e.getMessage(), true);
        }
    }

    private void publishEvent(dev.aceclaw.infra.event.AceClawEvent event) {
        if (config.eventBus() != null) {
            config.eventBus().publish(event);
        }
    }
}
