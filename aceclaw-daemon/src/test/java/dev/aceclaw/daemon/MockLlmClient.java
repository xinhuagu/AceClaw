package dev.aceclaw.daemon;

import dev.aceclaw.core.llm.*;

import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A programmable mock {@link LlmClient} for integration testing.
 *
 * <p>Callers enqueue responses as lists of {@link StreamEvent}s or pre-built
 * {@link StreamSession} objects. Each call to {@link #streamMessage(LlmRequest)}
 * pops the next response from the queue and returns it.
 */
public final class MockLlmClient implements LlmClient {

    private final ConcurrentLinkedQueue<Object> responses = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<LlmRequest> capturedRequests = new ConcurrentLinkedQueue<>();

    /**
     * Enqueues a canned streaming response. Events are fired synchronously
     * when {@link StreamSession#onEvent(StreamEventHandler)} is called.
     */
    public void enqueueResponse(List<StreamEvent> events) {
        responses.add(events);
    }

    /**
     * Enqueues a pre-built {@link StreamSession} (e.g. a pausing session for cancel tests).
     */
    public void enqueueSession(StreamSession session) {
        responses.add(session);
    }

    /**
     * Returns all captured requests (in order).
     */
    public List<LlmRequest> capturedRequests() {
        return List.copyOf(capturedRequests);
    }

    /**
     * Clears all queued responses and captured requests.
     */
    public void reset() {
        responses.clear();
        capturedRequests.clear();
    }

    @Override
    public LlmResponse sendMessage(LlmRequest request) throws LlmException {
        throw new UnsupportedOperationException("Use streamMessage() in integration tests");
    }

    @SuppressWarnings("unchecked")
    @Override
    public StreamSession streamMessage(LlmRequest request) throws LlmException {
        capturedRequests.add(request);
        var next = responses.poll();
        if (next == null) {
            throw new LlmException("No more mock responses queued", 500);
        }
        if (next instanceof StreamSession session) {
            return session;
        }
        return new MockStreamSession((List<StreamEvent>) next);
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
    public static List<StreamEvent> textResponse(String text) {
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
    public static List<StreamEvent> toolUseResponse(String prefixText, String toolId,
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
    public static List<StreamEvent> errorResponse(String message) {
        return List.of(
                new StreamEvent.MessageStart("msg-mock", "mock-model"),
                new StreamEvent.StreamError(new LlmException(message, 500))
        );
    }

    /**
     * Creates a pausing text response for testing cancel-during-stream scenarios.
     * The session signals {@code arrivedLatch} after delivering partial text,
     * then waits on {@code continueLatch} before delivering the rest.
     * If cancelled while waiting, exits immediately with partial content.
     *
     * @param text           the full text to deliver (split roughly in half)
     * @param arrivedLatch   signalled after the first half of text is delivered
     * @param continueLatch  waited on before delivering the second half
     */
    public static PausingStreamSession pausingTextResponse(String text,
                                                     CountDownLatch arrivedLatch,
                                                     CountDownLatch continueLatch) {
        int mid = text.length() / 2;
        String firstHalf = text.substring(0, mid);
        String secondHalf = text.substring(mid);
        return new PausingStreamSession(firstHalf, secondHalf, arrivedLatch, continueLatch);
    }

    // -- Mock stream session --

    private static final class MockStreamSession implements StreamSession {

        private final List<StreamEvent> events;
        private final AtomicBoolean cancelled = new AtomicBoolean(false);

        MockStreamSession(List<StreamEvent> events) {
            this.events = events;
        }

        @Override
        public void onEvent(StreamEventHandler handler) {
            for (var event : events) {
                if (cancelled.get()) return;
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
                    case StreamEvent.Heartbeat e -> handler.onHeartbeat(e);
                }
            }
        }

        @Override
        public void cancel() {
            cancelled.set(true);
        }
    }

    /**
     * A stream session that pauses mid-delivery, allowing tests to trigger
     * cancellation during streaming. After delivering the first half of text,
     * it signals {@code arrivedLatch} and waits on {@code continueLatch}.
     * If cancelled while waiting, it exits immediately with partial content.
     */
    static final class PausingStreamSession implements StreamSession {

        private final String firstHalf;
        private final String secondHalf;
        private final CountDownLatch arrivedLatch;
        private final CountDownLatch continueLatch;
        private final AtomicBoolean cancelled = new AtomicBoolean(false);

        PausingStreamSession(String firstHalf, String secondHalf,
                             CountDownLatch arrivedLatch, CountDownLatch continueLatch) {
            this.firstHalf = firstHalf;
            this.secondHalf = secondHalf;
            this.arrivedLatch = arrivedLatch;
            this.continueLatch = continueLatch;
        }

        @Override
        public void onEvent(StreamEventHandler handler) {
            handler.onMessageStart(new StreamEvent.MessageStart("msg-pause", "mock-model"));
            handler.onContentBlockStart(new StreamEvent.ContentBlockStart(0, new ContentBlock.Text("")));

            // Deliver first half
            handler.onTextDelta(new StreamEvent.TextDelta(firstHalf));

            // Signal that we've arrived at the pause point
            arrivedLatch.countDown();

            // Wait for either continuation or cancellation
            try {
                while (!continueLatch.await(50, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                    if (cancelled.get()) {
                        // Cancelled while paused: close the block and exit
                        handler.onContentBlockStop(new StreamEvent.ContentBlockStop(0));
                        handler.onMessageDelta(new StreamEvent.MessageDelta(
                                StopReason.END_TURN, new Usage(80, 30)));
                        handler.onComplete(new StreamEvent.StreamComplete());
                        return;
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }

            if (cancelled.get()) {
                handler.onContentBlockStop(new StreamEvent.ContentBlockStop(0));
                handler.onMessageDelta(new StreamEvent.MessageDelta(
                        StopReason.END_TURN, new Usage(80, 30)));
                handler.onComplete(new StreamEvent.StreamComplete());
                return;
            }

            // Deliver second half
            handler.onTextDelta(new StreamEvent.TextDelta(secondHalf));
            handler.onContentBlockStop(new StreamEvent.ContentBlockStop(0));
            handler.onMessageDelta(new StreamEvent.MessageDelta(
                    StopReason.END_TURN, new Usage(100, 50)));
            handler.onComplete(new StreamEvent.StreamComplete());
        }

        @Override
        public void cancel() {
            cancelled.set(true);
        }

        boolean wasCancelled() {
            return cancelled.get();
        }
    }
}
