package dev.aceclaw.llm.anthropic;

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
 * Anthropic SSE stream session.
 *
 * <p>Parses the Server-Sent Events line stream from the Anthropic Messages API
 * and dispatches typed events to the registered {@link StreamEventHandler}.
 *
 * <p>A watchdog virtual thread monitors stream liveness. If no SSE line is
 * received within {@link #STREAM_READ_TIMEOUT_MS}, the session is cancelled
 * and the underlying HTTP response body is closed, unblocking the iterator.
 */
final class AnthropicStreamSession implements StreamSession {

    private static final Logger log = LoggerFactory.getLogger(AnthropicStreamSession.class);

    /** Maximum silence between SSE lines before considering the stream hung. */
    static final long STREAM_READ_TIMEOUT_MS = 120_000;

    /** How often the watchdog checks for timeout. */
    private static final long WATCHDOG_CHECK_INTERVAL_MS = 5_000;

    private final HttpResponse<Stream<String>> response;
    private final AnthropicMapper mapper;
    private final AtomicBoolean cancelled = new AtomicBoolean(false);

    /** Timestamp of the last SSE line received (any line, including empty/ping). */
    private volatile long lastLineAtMs;

    // Accumulates partial input_json_delta fragments per content block index
    private final Map<Integer, StringBuilder> toolInputAccumulators = new HashMap<>();
    // Tracks tool name per content block index for ToolUseDelta events
    private final Map<Integer, String> toolNames = new HashMap<>();
    // Tracks which content block indices are thinking blocks
    private final Map<Integer, StringBuilder> thinkingAccumulators = new HashMap<>();

    AnthropicStreamSession(HttpResponse<Stream<String>> response, AnthropicMapper mapper) {
        this.response = response;
        this.mapper = mapper;
    }

    @Override
    public void onEvent(StreamEventHandler handler) {
        String currentEventType = null;
        lastLineAtMs = System.currentTimeMillis();

        // Start watchdog to detect hung streams
        var watchdog = Thread.ofVirtual()
                .name("sse-watchdog")
                .start(() -> watchdogLoop());

        try (Stream<String> lines = response.body()) {
            var iterator = lines.iterator();
            while (iterator.hasNext() && !cancelled.get()) {
                String line = iterator.next();
                lastLineAtMs = System.currentTimeMillis();

                if (line.isEmpty()) {
                    // Empty line marks the end of an SSE event block
                    currentEventType = null;
                    continue;
                }

                if (line.startsWith("event: ")) {
                    currentEventType = line.substring(7).trim();
                    continue;
                }

                if (line.startsWith("data: ") && currentEventType != null) {
                    String data = line.substring(6);
                    dispatchEvent(currentEventType, data, handler);
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
        } finally {
            // Ensure watchdog exits
            cancelled.set(true);
            watchdog.interrupt();
            try {
                watchdog.join(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    @Override
    public void cancel() {
        if (cancelled.compareAndSet(false, true)) {
            // Close the response body stream to unblock iterator.hasNext()
            try {
                response.body().close();
            } catch (Exception e) {
                log.debug("Failed to close response body on cancel: {}", e.getMessage());
            }
        }
    }

    /**
     * Watchdog loop: periodically checks if the stream has gone silent.
     * If no SSE line arrives within {@link #STREAM_READ_TIMEOUT_MS}, cancels the session.
     */
    private void watchdogLoop() {
        while (!cancelled.get()) {
            try {
                Thread.sleep(WATCHDOG_CHECK_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            if (cancelled.get()) {
                return;
            }
            long silenceMs = System.currentTimeMillis() - lastLineAtMs;
            if (silenceMs >= STREAM_READ_TIMEOUT_MS) {
                log.warn("SSE stream read timeout: no data for {}ms, cancelling", silenceMs);
                cancel();
                return;
            }
        }
    }

    private void dispatchEvent(String eventType, String data, StreamEventHandler handler) {
        try {
            switch (eventType) {
                case "message_start" -> handleMessageStart(data, handler);
                case "content_block_start" -> handleContentBlockStart(data, handler);
                case "content_block_delta" -> handleContentBlockDelta(data, handler);
                case "content_block_stop" -> handleContentBlockStop(data, handler);
                case "message_delta" -> handleMessageDelta(data, handler);
                case "message_stop" -> { /* Will be handled by stream completion */ }
                case "ping" -> { /* Ignore keepalive pings */ }
                case "error" -> handleError(data, handler);
                default -> log.debug("Unknown SSE event type: {}", eventType);
            }
        } catch (Exception e) {
            log.warn("Failed to process SSE event '{}': {}", eventType, e.getMessage(), e);
        }
    }

    private void handleMessageStart(String data, StreamEventHandler handler) throws Exception {
        JsonNode root = mapper.objectMapper().readTree(data);
        JsonNode message = root.path("message");
        String id = message.path("id").asText("");
        String model = message.path("model").asText("");
        Usage usage = mapper.parseUsage(message.path("usage"));
        handler.onMessageStart(new StreamEvent.MessageStart(id, model, usage));
    }

    private void handleContentBlockStart(String data, StreamEventHandler handler) throws Exception {
        JsonNode root = mapper.objectMapper().readTree(data);
        int index = root.path("index").asInt(0);
        JsonNode blockNode = root.path("content_block");
        ContentBlock block = mapper.parseContentBlock(blockNode);

        // If this is a tool_use block, initialize the accumulator
        if (block instanceof ContentBlock.ToolUse tu) {
            toolInputAccumulators.put(index, new StringBuilder());
            toolNames.put(index, tu.name());
        } else if (block instanceof ContentBlock.Thinking) {
            thinkingAccumulators.put(index, new StringBuilder());
        }

        if (block != null) {
            handler.onContentBlockStart(new StreamEvent.ContentBlockStart(index, block));
        }
    }

    private void handleContentBlockDelta(String data, StreamEventHandler handler) throws Exception {
        JsonNode root = mapper.objectMapper().readTree(data);
        int index = root.path("index").asInt(0);
        JsonNode delta = root.path("delta");
        String deltaType = delta.path("type").asText("");

        switch (deltaType) {
            case "text_delta" -> {
                String text = delta.path("text").asText("");
                handler.onTextDelta(new StreamEvent.TextDelta(text));
            }
            case "input_json_delta" -> {
                String partialJson = delta.path("partial_json").asText("");
                // Accumulate the partial JSON
                toolInputAccumulators.computeIfAbsent(index, k -> new StringBuilder())
                        .append(partialJson);
                String toolName = toolNames.get(index);
                handler.onToolUseDelta(new StreamEvent.ToolUseDelta(index, toolName, partialJson));
            }
            case "thinking_delta" -> {
                String thinking = delta.path("thinking").asText("");
                thinkingAccumulators.computeIfAbsent(index, k -> new StringBuilder())
                        .append(thinking);
                handler.onThinkingDelta(new StreamEvent.ThinkingDelta(thinking));
            }
            case "signature_delta" -> {
                // Signature for thinking blocks — not needed since we skip
                // thinking blocks in outgoing conversation history
                log.trace("Signature delta received, index={}", index);
            }
            default -> log.debug("Unknown delta type: {}", deltaType);
        }
    }

    private void handleContentBlockStop(String data, StreamEventHandler handler) throws Exception {
        JsonNode root = mapper.objectMapper().readTree(data);
        int index = root.path("index").asInt(0);

        // Clean up accumulators for this block
        toolInputAccumulators.remove(index);
        toolNames.remove(index);
        thinkingAccumulators.remove(index);

        handler.onContentBlockStop(new StreamEvent.ContentBlockStop(index));
    }

    private void handleMessageDelta(String data, StreamEventHandler handler) throws Exception {
        JsonNode root = mapper.objectMapper().readTree(data);
        JsonNode delta = root.path("delta");
        StopReason stopReason = StopReason.fromString(delta.path("stop_reason").asText(null));
        Usage usage = mapper.parseUsage(root.path("usage"));
        handler.onMessageDelta(new StreamEvent.MessageDelta(stopReason, usage));
    }

    private void handleError(String data, StreamEventHandler handler) throws Exception {
        JsonNode root = mapper.objectMapper().readTree(data);
        String type = root.path("error").path("type").asText("unknown_error");
        String message = root.path("error").path("message").asText("Unknown error");
        handler.onError(new StreamEvent.StreamError(
                new LlmException("Anthropic stream error [" + type + "]: " + message)));
    }

    /**
     * Returns the accumulated tool input JSON for a content block index, or null if none.
     */
    String getAccumulatedToolInput(int index) {
        StringBuilder sb = toolInputAccumulators.get(index);
        return sb != null ? sb.toString() : null;
    }
}
