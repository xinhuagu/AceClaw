package dev.aceclaw.daemon;

import dev.aceclaw.core.llm.*;

import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * A programmable mock {@link LlmClient} for integration testing.
 *
 * <p>Callers enqueue responses as lists of {@link StreamEvent}s.
 * Each call to {@link #streamMessage(LlmRequest)} pops the next
 * response from the queue and returns a session that fires those events.
 */
final class MockLlmClient implements LlmClient {

    private final ConcurrentLinkedQueue<List<StreamEvent>> responses = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<LlmRequest> capturedRequests = new ConcurrentLinkedQueue<>();

    /**
     * Enqueues a canned streaming response. Events are fired synchronously
     * when {@link StreamSession#onEvent(StreamEventHandler)} is called.
     */
    void enqueueResponse(List<StreamEvent> events) {
        responses.add(events);
    }

    /**
     * Returns all captured requests (in order).
     */
    List<LlmRequest> capturedRequests() {
        return List.copyOf(capturedRequests);
    }

    /**
     * Clears all queued responses and captured requests.
     */
    void reset() {
        responses.clear();
        capturedRequests.clear();
    }

    @Override
    public LlmResponse sendMessage(LlmRequest request) throws LlmException {
        throw new UnsupportedOperationException("Use streamMessage() in integration tests");
    }

    @Override
    public StreamSession streamMessage(LlmRequest request) throws LlmException {
        capturedRequests.add(request);
        var events = responses.poll();
        if (events == null) {
            throw new LlmException("No more mock responses queued", 500);
        }
        return new MockStreamSession(events);
    }

    @Override
    public String provider() {
        return "mock";
    }

    @Override
    public String defaultModel() {
        return "mock-model";
    }

    // -- Convenience factories for common response patterns --

    /**
     * Creates events for a simple text-only response.
     */
    static List<StreamEvent> textResponse(String text) {
        return List.of(
                new StreamEvent.MessageStart("msg-mock", "mock-model"),
                new StreamEvent.ContentBlockStart(0, new ContentBlock.Text("")),
                new StreamEvent.TextDelta(text),
                new StreamEvent.ContentBlockStop(0),
                new StreamEvent.MessageDelta(StopReason.END_TURN, new Usage(100, 50)),
                new StreamEvent.StreamComplete()
        );
    }

    /**
     * Creates events for a response that includes a tool use request.
     *
     * @param prefixText text before the tool call
     * @param toolId     tool use block ID
     * @param toolName   tool name
     * @param inputJson  tool input JSON
     */
    static List<StreamEvent> toolUseResponse(String prefixText, String toolId,
                                             String toolName, String inputJson) {
        return List.of(
                new StreamEvent.MessageStart("msg-mock", "mock-model"),
                new StreamEvent.ContentBlockStart(0, new ContentBlock.Text("")),
                new StreamEvent.TextDelta(prefixText),
                new StreamEvent.ContentBlockStop(0),
                new StreamEvent.ContentBlockStart(1, new ContentBlock.ToolUse(toolId, toolName, "")),
                new StreamEvent.ToolUseDelta(1, toolName, inputJson),
                new StreamEvent.ContentBlockStop(1),
                new StreamEvent.MessageDelta(StopReason.TOOL_USE, new Usage(150, 80)),
                new StreamEvent.StreamComplete()
        );
    }

    /**
     * Creates events for an error response.
     */
    static List<StreamEvent> errorResponse(String message) {
        return List.of(
                new StreamEvent.MessageStart("msg-mock", "mock-model"),
                new StreamEvent.StreamError(new LlmException(message, 500))
        );
    }

    // -- Mock stream session --

    private static final class MockStreamSession implements StreamSession {

        private final List<StreamEvent> events;

        MockStreamSession(List<StreamEvent> events) {
            this.events = events;
        }

        @Override
        public void onEvent(StreamEventHandler handler) {
            for (var event : events) {
                switch (event) {
                    case StreamEvent.MessageStart e -> handler.onMessageStart(e);
                    case StreamEvent.ContentBlockStart e -> handler.onContentBlockStart(e);
                    case StreamEvent.TextDelta e -> handler.onTextDelta(e);
                    case StreamEvent.ThinkingDelta e -> handler.onThinkingDelta(e);
                    case StreamEvent.ToolUseDelta e -> handler.onToolUseDelta(e);
                    case StreamEvent.ContentBlockStop e -> handler.onContentBlockStop(e);
                    case StreamEvent.MessageDelta e -> handler.onMessageDelta(e);
                    case StreamEvent.StreamComplete e -> handler.onComplete(e);
                    case StreamEvent.StreamError e -> handler.onError(e);
                }
            }
        }

        @Override
        public void cancel() {
            // no-op for mock
        }
    }
}
