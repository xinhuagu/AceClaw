package dev.aceclaw.core.agent;

import dev.aceclaw.core.llm.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.StructuredTaskScope;

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

    /** Maximum number of tool-use iterations before forcing a stop. */
    private static final int MAX_ITERATIONS = 25;

    /** Maximum characters allowed in a single tool result before truncation. */
    private static final int MAX_TOOL_RESULT_CHARS = 30_000;

    private final LlmClient llmClient;
    private final ToolRegistry toolRegistry;
    private final String model;
    private final String systemPrompt;
    private final int maxTokens;
    private final int thinkingBudget;
    private final MessageCompactor compactor;

    /**
     * Creates a streaming agent loop with default token settings and no compaction.
     *
     * @param llmClient    the LLM client for streaming requests
     * @param toolRegistry registry of available tools
     * @param model        model identifier
     * @param systemPrompt system prompt for the LLM (may be null)
     */
    public StreamingAgentLoop(LlmClient llmClient, ToolRegistry toolRegistry,
                              String model, String systemPrompt) {
        this(llmClient, toolRegistry, model, systemPrompt, 16384, 10240, null);
    }

    /**
     * Creates a streaming agent loop with configurable token settings and no compaction.
     *
     * @param llmClient      the LLM client for streaming requests
     * @param toolRegistry   registry of available tools
     * @param model          model identifier
     * @param systemPrompt   system prompt for the LLM (may be null)
     * @param maxTokens      maximum tokens to generate per request
     * @param thinkingBudget tokens reserved for extended thinking (0 = disabled)
     */
    public StreamingAgentLoop(LlmClient llmClient, ToolRegistry toolRegistry,
                              String model, String systemPrompt,
                              int maxTokens, int thinkingBudget) {
        this(llmClient, toolRegistry, model, systemPrompt, maxTokens, thinkingBudget, null);
    }

    /**
     * Creates a streaming agent loop with full configuration including compaction.
     *
     * @param llmClient      the LLM client for streaming requests
     * @param toolRegistry   registry of available tools
     * @param model          model identifier
     * @param systemPrompt   system prompt for the LLM (may be null)
     * @param maxTokens      maximum tokens to generate per request
     * @param thinkingBudget tokens reserved for extended thinking (0 = disabled)
     * @param compactor      optional message compactor (null = no compaction)
     */
    public StreamingAgentLoop(LlmClient llmClient, ToolRegistry toolRegistry,
                              String model, String systemPrompt,
                              int maxTokens, int thinkingBudget,
                              MessageCompactor compactor) {
        this.llmClient = llmClient;
        this.toolRegistry = toolRegistry;
        this.model = model;
        this.systemPrompt = systemPrompt;
        this.maxTokens = maxTokens;
        this.thinkingBudget = thinkingBudget;
        this.compactor = compactor;
    }

    /**
     * Runs a single agent turn with streaming, invoking the handler for real-time token delivery.
     *
     * <p>If a {@link MessageCompactor} is configured, context size is checked before each
     * LLM call. Uses actual {@code inputTokens} from API responses when available, falling
     * back to character-based estimation for the first call.
     *
     * @param userPrompt          the user's prompt text
     * @param conversationHistory previous messages in the conversation
     * @param handler             callback for streaming events
     * @return the turn result containing all new messages and usage statistics
     * @throws LlmException if the LLM call fails
     */
    public Turn runTurn(String userPrompt, List<Message> conversationHistory, StreamEventHandler handler)
            throws LlmException {
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

        for (int iteration = 0; iteration < MAX_ITERATIONS; iteration++) {

            // Check if context compaction is needed before this LLM call
            if (compactor != null) {
                compactionResult = checkAndCompact(
                        allMessages, lastInputTokens, compactionResult, handler);
            }

            log.debug("Streaming ReAct iteration {} (messages: {})", iteration + 1, allMessages.size());

            var request = buildRequest(allMessages);

            // Stream the response, accumulating content blocks
            var accumulator = new StreamAccumulator(handler);
            var session = llmClient.streamMessage(request);
            session.onEvent(accumulator);

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
                lastInputTokens = accumulator.usage.inputTokens();
            }

            // Build assistant message from accumulated content
            var contentBlocks = accumulator.buildContentBlocks();
            var assistantMessage = new Message.AssistantMessage(contentBlocks);
            allMessages.add(assistantMessage);
            newMessages.add(assistantMessage);

            var stopReason = accumulator.stopReason != null ? accumulator.stopReason : StopReason.ERROR;

            // Check stop reason
            switch (stopReason) {
                case END_TURN, MAX_TOKENS, STOP_SEQUENCE, ERROR -> {
                    log.debug("Streaming turn complete: stopReason={}, iterations={}",
                            stopReason, iteration + 1);
                    var totalUsage = new Usage(
                            totalInputTokens, totalOutputTokens,
                            totalCacheCreationTokens, totalCacheReadTokens);
                    return new Turn(newMessages, stopReason, totalUsage, compactionResult);
                }
                case TOOL_USE -> {
                    var toolUseBlocks = contentBlocks.stream()
                            .filter(b -> b instanceof ContentBlock.ToolUse)
                            .map(b -> (ContentBlock.ToolUse) b)
                            .toList();
                    log.debug("Streaming tool use requested: {} tool(s)", toolUseBlocks.size());

                    var toolResults = executeTools(toolUseBlocks);
                    var toolResultMessage = Message.toolResults(toolResults);
                    allMessages.add(toolResultMessage);
                    newMessages.add(toolResultMessage);
                }
            }
        }

        // Exceeded max iterations
        log.warn("Streaming ReAct loop exceeded max iterations ({})", MAX_ITERATIONS);
        var totalUsage = new Usage(
                totalInputTokens, totalOutputTokens,
                totalCacheCreationTokens, totalCacheReadTokens);
        return new Turn(newMessages, StopReason.END_TURN, totalUsage, compactionResult);
    }

    /**
     * Checks if compaction is needed and runs it if so.
     * Uses actual API token count when available, falls back to estimation.
     *
     * @return the compaction result (possibly from a previous iteration), or null
     */
    private CompactionResult checkAndCompact(
            ArrayList<Message> allMessages, int lastInputTokens,
            CompactionResult previousResult, StreamEventHandler handler) throws LlmException {

        boolean needsCompaction;
        if (lastInputTokens > 0) {
            // Use actual token count from the last API response
            needsCompaction = compactor.needsCompaction(lastInputTokens);
        } else {
            // First iteration — use estimation
            needsCompaction = compactor.needsCompactionEstimate(
                    allMessages, systemPrompt, toolRegistry.toDefinitions());
        }

        if (!needsCompaction) {
            return previousResult;
        }

        log.info("Context compaction triggered (lastInputTokens={}, threshold={})",
                lastInputTokens, compactor.config().triggerTokens());

        var result = compactor.compact(allMessages, systemPrompt);

        // Replace allMessages with compacted version
        allMessages.clear();
        allMessages.addAll(result.compactedMessages());

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

    private LlmRequest buildRequest(List<Message> messages) {
        // Only enable extended thinking if the provider supports it
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
            return List.of(executeSingleTool(toolUseBlocks.getFirst()));
        }

        try (var scope = new StructuredTaskScope.ShutdownOnFailure()) {
            var subtasks = toolUseBlocks.stream()
                    .map(toolUse -> scope.fork(() -> executeSingleTool(toolUse)))
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
        }
    }

    private ContentBlock.ToolResult executeSingleTool(ContentBlock.ToolUse toolUse) {
        var toolOpt = toolRegistry.get(toolUse.name());
        if (toolOpt.isEmpty()) {
            log.warn("Unknown tool requested: {}", toolUse.name());
            return new ContentBlock.ToolResult(toolUse.id(), "Unknown tool: " + toolUse.name(), true);
        }

        var tool = toolOpt.get();
        try {
            log.debug("Executing tool: {} (id: {})", tool.name(), toolUse.id());
            var result = tool.execute(toolUse.inputJson());
            String output = truncateToolResult(result.output(), MAX_TOOL_RESULT_CHARS);
            if (output.length() != result.output().length()) {
                log.debug("Tool {} result truncated: {} -> {} chars [truncated]",
                        tool.name(), result.output().length(), output.length());
            }
            log.debug("Tool {} completed: isError={}", tool.name(), result.isError());
            return new ContentBlock.ToolResult(toolUse.id(), output, result.isError());
        } catch (Exception e) {
            log.error("Tool {} threw exception: {}", tool.name(), e.getMessage(), e);
            return new ContentBlock.ToolResult(toolUse.id(), "Tool error: " + e.getMessage(), true);
        }
    }

    /**
     * Truncates a tool result to fit within the given character limit.
     * Keeps 40% from the head and 60% from the tail (error messages tend to be at the end).
     */
    static String truncateToolResult(String output, int maxChars) {
        if (output == null || output.length() <= maxChars) {
            return output;
        }
        int headChars = (int) (maxChars * 0.4);
        int tailChars = maxChars - headChars;
        return output.substring(0, headChars)
                + "\n\n... (truncated: " + output.length() + " chars total, showing first "
                + headChars + " and last " + tailChars + ") ...\n\n"
                + output.substring(output.length() - tailChars);
    }

    /**
     * Accumulates stream events into a complete response while forwarding events
     * to the caller's handler for real-time rendering.
     */
    private static final class StreamAccumulator implements StreamEventHandler {

        private final StreamEventHandler delegate;

        // Accumulated state
        private final StringBuilder textBuilder = new StringBuilder();
        private final StringBuilder thinkingBuilder = new StringBuilder();
        private final List<ContentBlock> contentBlocks = new ArrayList<>();

        // Current tool-use block being accumulated
        private String currentToolUseId;
        private String currentToolUseName;
        private final StringBuilder toolUseJsonBuilder = new StringBuilder();
        private boolean inToolUse;
        private boolean inThinking;

        StopReason stopReason;
        Usage usage;
        LlmException error;

        StreamAccumulator(StreamEventHandler delegate) {
            this.delegate = delegate;
        }

        @Override
        public void onMessageStart(StreamEvent.MessageStart event) {
            delegate.onMessageStart(event);
        }

        @Override
        public void onContentBlockStart(StreamEvent.ContentBlockStart event) {
            // Finalize any pending text or thinking block
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
            this.usage = event.usage();
            delegate.onMessageDelta(event);
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
