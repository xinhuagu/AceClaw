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
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

/**
 * SSE stream session for the OpenAI Responses API.
 *
 * <p>Parses Server-Sent Events with explicit {@code event:} and {@code data:} lines
 * from the Responses API streaming format. Unlike Chat Completions streaming, the
 * Responses API uses named event types ({@code response.created},
 * {@code response.output_item.added}, {@code response.content_part.delta}, etc.).
 */
final class OpenAIResponsesStreamSession implements StreamSession {

    private static final Logger log = LoggerFactory.getLogger(OpenAIResponsesStreamSession.class);

    private final HttpResponse<Stream<String>> response;
    private final OpenAIResponsesMapper mapper;
    private final AtomicBoolean cancelled = new AtomicBoolean(false);

    // Track tool call states by output_index for argument accumulation
    private final Map<Integer, ToolCallState> toolCallStates = new HashMap<>();
    private int nextBlockIndex;

    OpenAIResponsesStreamSession(HttpResponse<Stream<String>> response, OpenAIResponsesMapper mapper) {
        this.response = response;
        this.mapper = mapper;
    }

    @Override
    public void onEvent(StreamEventHandler handler) {
        try (Stream<String> lines = response.body()) {
            var iterator = lines.iterator();
            String currentEvent = null;

            while (iterator.hasNext() && !cancelled.get()) {
                String line = iterator.next();

                if (line.isEmpty()) {
                    currentEvent = null;
                    continue;
                }

                if (line.startsWith("event: ")) {
                    currentEvent = line.substring(7).trim();
                    continue;
                }

                if (line.startsWith("data: ")) {
                    String data = line.substring(6).trim();
                    if (currentEvent != null) {
                        try {
                            processEvent(currentEvent, data, handler);
                        } catch (Exception e) {
                            log.warn("Failed to process SSE event {}: {}", currentEvent, e.getMessage(), e);
                        }
                    }
                }
            }

            if (!cancelled.get()) {
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

    private void processEvent(String eventType, String data, StreamEventHandler handler) throws Exception {
        JsonNode root = mapper.objectMapper().readTree(data);

        switch (eventType) {
            case "response.created" -> {
                String id = root.path("id").asText("");
                String model = root.path("model").asText("");
                handler.onMessageStart(new StreamEvent.MessageStart(id, model));
            }

            case "response.output_item.added" -> {
                JsonNode item = root.path("item");
                int outputIndex = root.path("output_index").asInt(0);
                String type = item.path("type").asText("");

                int blockIndex = nextBlockIndex++;
                if ("message".equals(type)) {
                    handler.onContentBlockStart(new StreamEvent.ContentBlockStart(
                            blockIndex, new ContentBlock.Text("")));
                } else if ("function_call".equals(type)) {
                    String callId = item.path("call_id").asText("");
                    String name = item.path("name").asText("");
                    toolCallStates.put(outputIndex, new ToolCallState(callId, name, blockIndex));
                    handler.onContentBlockStart(new StreamEvent.ContentBlockStart(
                            blockIndex, new ContentBlock.ToolUse(callId, name, "")));
                }
            }

            case "response.content_part.delta" -> {
                JsonNode delta = root.path("delta");
                String deltaType = delta.path("type").asText("");
                if ("text_delta".equals(deltaType)) {
                    String text = delta.path("text").asText("");
                    if (!text.isEmpty()) {
                        handler.onTextDelta(new StreamEvent.TextDelta(text));
                    }
                }
            }

            case "response.function_call_arguments.delta" -> {
                int outputIndex = root.path("output_index").asInt(0);
                String argDelta = root.path("delta").asText("");
                ToolCallState state = toolCallStates.get(outputIndex);
                if (state != null && !argDelta.isEmpty()) {
                    state.argumentsBuilder.append(argDelta);
                    handler.onToolUseDelta(new StreamEvent.ToolUseDelta(
                            state.blockIndex, state.name, argDelta));
                }
            }

            case "response.output_item.done" -> {
                JsonNode item = root.path("item");
                int outputIndex = root.path("output_index").asInt(0);
                String type = item.path("type").asText("");

                if ("function_call".equals(type)) {
                    ToolCallState state = toolCallStates.get(outputIndex);
                    if (state != null) {
                        handler.onContentBlockStop(new StreamEvent.ContentBlockStop(state.blockIndex));
                    }
                } else if ("message".equals(type)) {
                    // Find block index for this message output
                    // It was assigned as nextBlockIndex - N earlier; use a simple heuristic
                    // Content block stop for text blocks
                    handler.onContentBlockStop(new StreamEvent.ContentBlockStop(
                            outputIndex < nextBlockIndex ? outputIndex : nextBlockIndex - 1));
                }
            }

            case "response.completed" -> {
                JsonNode resp = root.path("response");
                Usage usage = mapper.parseUsage(resp.path("usage"));
                StopReason stopReason = mapper.mapStatus(resp.path("status").asText(null));
                // Override to TOOL_USE if output contains function_call items
                JsonNode output = resp.path("output");
                if (output.isArray()) {
                    for (JsonNode item : output) {
                        if ("function_call".equals(item.path("type").asText(""))) {
                            stopReason = StopReason.TOOL_USE;
                            break;
                        }
                    }
                }
                handler.onMessageDelta(new StreamEvent.MessageDelta(stopReason, usage));
            }

            case "response.failed" -> {
                String errorMsg = root.path("error").path("message").asText("Unknown error");
                handler.onError(new StreamEvent.StreamError(new LlmException(errorMsg)));
            }

            default -> log.trace("Ignoring unknown Responses API event: {}", eventType);
        }
    }

    private static final class ToolCallState {
        final String id;
        final String name;
        final int blockIndex;
        final StringBuilder argumentsBuilder = new StringBuilder();

        ToolCallState(String id, String name, int blockIndex) {
            this.id = id;
            this.name = name;
            this.blockIndex = blockIndex;
        }
    }
}
