package dev.aceclaw.llm.openai;

import com.fasterxml.jackson.databind.JsonNode;
import dev.aceclaw.core.llm.ContentBlock;
import dev.aceclaw.core.llm.LlmException;
import dev.aceclaw.core.llm.StopReason;
import dev.aceclaw.core.llm.StreamEvent;
import dev.aceclaw.core.llm.StreamEventHandler;
import dev.aceclaw.core.llm.StreamSession;
import dev.aceclaw.core.llm.Usage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.http.HttpResponse;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

/**
 * OpenAI-compatible SSE stream session.
 *
 * <p>Parses the Server-Sent Events line stream from the OpenAI Chat Completions
 * streaming API and dispatches typed events to the registered {@link StreamEventHandler}.
 *
 * <p>Unlike Anthropic's format, OpenAI does not emit explicit content block start/stop
 * events. This session synthesizes them by tracking state transitions between text
 * output and tool calls.
 */
final class OpenAIStreamSession implements StreamSession {

    private static final Logger log = LoggerFactory.getLogger(OpenAIStreamSession.class);

    private final HttpResponse<Stream<String>> response;
    private final OpenAIMapper mapper;
    private final AtomicBoolean cancelled = new AtomicBoolean(false);

    // State tracking for synthesized content block boundaries
    private boolean inTextBlock;
    private int textBlockIndex;
    private int nextBlockIndex;

    // Reasoning block tracking (qwen3, DeepSeek, etc.)
    private boolean inReasoningBlock;
    private int reasoningBlockIndex;

    // Tool call accumulation: stable key -> state. Ollama has emitted tool calls
    // without index in OpenAI-compatible streams, so id is used as a fallback.
    private final Map<String, ToolCallState> toolCallStates = new LinkedHashMap<>();
    private StopReason lastStopReason;

    OpenAIStreamSession(HttpResponse<Stream<String>> response, OpenAIMapper mapper) {
        this.response = response;
        this.mapper = mapper;
    }

    @Override
    public void onEvent(StreamEventHandler handler) {
        try (Stream<String> lines = response.body()) {
            var iterator = lines.iterator();
            while (iterator.hasNext() && !cancelled.get()) {
                String line = iterator.next();

                if (line.isEmpty()) {
                    continue;
                }

                if (!line.startsWith("data: ")) {
                    continue;
                }

                String data = line.substring(6).trim();

                // [DONE] sentinel marks end of stream
                if ("[DONE]".equals(data)) {
                    // Flush any open blocks before completing
                    flushOpenBlocks(handler);
                    handler.onComplete(new StreamEvent.StreamComplete());
                    return;
                }

                try {
                    processChunk(data, handler);
                } catch (Exception e) {
                    log.warn("Failed to process SSE chunk: {}", e.getMessage(), e);
                }
            }

            if (!cancelled.get()) {
                flushOpenBlocks(handler);
                handler.onComplete(new StreamEvent.StreamComplete());
            }
        } catch (Exception e) {
            if (!cancelled.get()) {
                LlmException llmEx = (e instanceof LlmException le) ? le
                        : new LlmException("Stream processing error", e);
                handler.onError(new StreamEvent.StreamError(llmEx));
            }
        }
    }

    @Override
    public void cancel() {
        cancelled.set(true);
    }

    private void processChunk(String data, StreamEventHandler handler) throws Exception {
        JsonNode root = mapper.objectMapper().readTree(data);

        // Extract model info from first chunk
        String id = root.path("id").asText(null);
        String model = root.path("model").asText(null);
        if (id != null && model != null && nextBlockIndex == 0 && !inTextBlock && toolCallStates.isEmpty()) {
            handler.onMessageStart(new StreamEvent.MessageStart(id, model));
        }

        JsonNode choices = root.path("choices");
        if (choices.isArray() && !choices.isEmpty()) {
            JsonNode firstChoice = choices.get(0);
            JsonNode delta = firstChoice.path("delta");
            String finishReason = firstChoice.path("finish_reason").asText(null);

            // Process reasoning delta (qwen3 uses "reasoning", DeepSeek uses "reasoning_content")
            String reasoningText = delta.path("reasoning").asText(null);
            if (reasoningText == null) {
                reasoningText = delta.path("reasoning_content").asText(null);
            }
            if (reasoningText != null && !reasoningText.isEmpty()) {
                handleReasoningDelta(reasoningText, handler);
            }

            // Process content delta (text)
            // Guard against empty strings (Ollama sends "content":"" alongside "reasoning" tokens)
            String contentText = delta.path("content").asText(null);
            if (contentText != null && !contentText.isEmpty()) {
                handleTextDelta(contentText, handler);
            }

            // Process tool_calls delta
            JsonNode toolCalls = delta.path("tool_calls");
            if (toolCalls.isArray()) {
                for (JsonNode tc : toolCalls) {
                    handleToolCallDelta(tc, handler);
                }
            }

            // Process finish_reason
            if (finishReason != null) {
                boolean hasToolCalls = !toolCallStates.isEmpty();
                flushOpenBlocks(handler);

                // Extract usage from top-level (OpenAI puts it at root when stream_options.include_usage=true)
                Usage usage = mapper.parseUsage(root.path("usage"));
                StopReason stopReason = StopReason.fromString(finishReason);
                if (hasToolCalls && stopReason != StopReason.ERROR) {
                    stopReason = StopReason.TOOL_USE;
                }
                lastStopReason = stopReason;
                handler.onMessageDelta(new StreamEvent.MessageDelta(stopReason, usage));
            }
        }

        // Some providers (Ollama, etc.) put usage in a separate final chunk with empty choices
        if ((!choices.isArray() || choices.isEmpty()) && root.has("usage")) {
            Usage chunkUsage = mapper.parseUsage(root.path("usage"));
            if (chunkUsage.inputTokens() > 0 || chunkUsage.outputTokens() > 0) {
                // Emit MessageDelta with usage so token counts reach the client
                flushOpenBlocks(handler);
                handler.onMessageDelta(new StreamEvent.MessageDelta(
                        lastStopReason != null ? lastStopReason : StopReason.END_TURN,
                        chunkUsage));
            }
        }
    }

    private void handleReasoningDelta(String text, StreamEventHandler handler) {
        if (!inReasoningBlock) {
            reasoningBlockIndex = nextBlockIndex++;
            inReasoningBlock = true;
            handler.onContentBlockStart(new StreamEvent.ContentBlockStart(
                    reasoningBlockIndex, new ContentBlock.Thinking("")));
        }
        handler.onThinkingDelta(new StreamEvent.ThinkingDelta(text));
    }

    private void handleTextDelta(String text, StreamEventHandler handler) {
        // Close reasoning block when transitioning to text
        if (inReasoningBlock) {
            handler.onContentBlockStop(new StreamEvent.ContentBlockStop(reasoningBlockIndex));
            inReasoningBlock = false;
        }
        if (!inTextBlock) {
            // Start a new text block
            textBlockIndex = nextBlockIndex++;
            inTextBlock = true;
            handler.onContentBlockStart(new StreamEvent.ContentBlockStart(
                    textBlockIndex, new ContentBlock.Text("")));
        }
        handler.onTextDelta(new StreamEvent.TextDelta(text));
    }

    private void handleToolCallDelta(JsonNode tc, StreamEventHandler handler) {
        // Close reasoning block if transitioning to tool calls
        if (inReasoningBlock) {
            handler.onContentBlockStop(new StreamEvent.ContentBlockStop(reasoningBlockIndex));
            inReasoningBlock = false;
        }

        // Close text block if transitioning to tool calls
        if (inTextBlock) {
            handler.onContentBlockStop(new StreamEvent.ContentBlockStop(textBlockIndex));
            inTextBlock = false;
        }

        String toolKey = toolCallKey(tc);
        ToolCallState state = toolCallStates.get(toolKey);

        // New tool call
        if (state == null) {
            String tcId = tc.path("id").asText("");
            JsonNode function = tc.path("function");
            String name = function.path("name").asText("");

            int blockIndex = nextBlockIndex++;
            state = new ToolCallState(tcId, name, blockIndex);
            toolCallStates.put(toolKey, state);
        } else {
            String tcId = tc.path("id").asText("");
            if (!tcId.isBlank() && state.id.isBlank()) {
                state.id = tcId;
            }
            JsonNode function = tc.path("function");
            String name = function.path("name").asText("");
            if (!name.isBlank() && state.name.isBlank()) {
                state.name = name;
            }
        }

        // Accumulate arguments
        JsonNode function = tc.path("function");
        String argFragment = function.path("arguments").asText(null);
        if (argFragment != null) {
            state.argumentsBuilder.append(argFragment);
        }
    }

    private String toolCallKey(JsonNode tc) {
        if (tc.has("index") && tc.path("index").canConvertToInt()) {
            return "index:" + tc.path("index").asInt();
        }
        String id = tc.path("id").asText("");
        if (!id.isBlank()) {
            return "id:" + id;
        }
        String name = tc.path("function").path("name").asText("");
        if (!name.isBlank()) {
            return "name:" + name;
        }
        return "position:" + toolCallStates.size();
    }

    /**
     * Flushes any open content blocks by emitting ContentBlockStop events.
     */
    private void flushOpenBlocks(StreamEventHandler handler) {
        if (inReasoningBlock) {
            handler.onContentBlockStop(new StreamEvent.ContentBlockStop(reasoningBlockIndex));
            inReasoningBlock = false;
        }
        if (inTextBlock) {
            handler.onContentBlockStop(new StreamEvent.ContentBlockStop(textBlockIndex));
            inTextBlock = false;
        }
        for (ToolCallState state : toolCallStates.values()) {
            String arguments = state.argumentsBuilder.isEmpty()
                    ? "{}"
                    : state.argumentsBuilder.toString();
            handler.onContentBlockStart(new StreamEvent.ContentBlockStart(
                    state.blockIndex, new ContentBlock.ToolUse(state.id, state.name, "")));
            handler.onToolUseDelta(new StreamEvent.ToolUseDelta(
                    state.blockIndex, state.name, arguments));
            handler.onContentBlockStop(new StreamEvent.ContentBlockStop(state.blockIndex));
        }
        toolCallStates.clear();
    }

    /**
     * Tracks the accumulation state for a single tool call during streaming.
     */
    private static final class ToolCallState {
        String id;
        String name;
        final int blockIndex;
        final StringBuilder argumentsBuilder = new StringBuilder();

        ToolCallState(String id, String name, int blockIndex) {
            this.id = id != null ? id : "";
            this.name = name != null ? name : "";
            this.blockIndex = blockIndex;
        }
    }
}
