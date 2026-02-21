package dev.aceclaw.cli;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Buffers streaming events for background tasks.
 *
 * <p>When a task is sent to the background, its {@link OutputSink} is swapped
 * to this buffer. Events accumulate silently and can be replayed to a
 * {@link ForegroundOutputSink} when the task is brought back to the foreground.
 */
public final class BackgroundOutputBuffer implements OutputSink {

    private final List<OutputEvent> events = Collections.synchronizedList(new ArrayList<>());

    @Override
    public void onThinkingDelta(String delta) {
        events.add(new OutputEvent.Thinking(delta));
    }

    @Override
    public void onTextDelta(String delta) {
        events.add(new OutputEvent.Text(delta));
    }

    @Override
    public void onToolUse(String toolName) {
        events.add(new OutputEvent.ToolUse(toolName));
    }

    @Override
    public void onStreamError(String error) {
        events.add(new OutputEvent.Error(error));
    }

    @Override
    public void onStreamCancelled() {
        events.add(new OutputEvent.Cancelled());
    }

    @Override
    public void onTurnComplete(JsonNode result, boolean hasError) {
        events.add(new OutputEvent.Complete(result, hasError));
    }

    @Override
    public void onConnectionClosed() {
        events.add(new OutputEvent.ConnectionClosed());
    }

    /**
     * Replays all buffered events to a foreground sink.
     *
     * @param sink the foreground sink to replay events to
     */
    public void replay(ForegroundOutputSink sink) {
        List<OutputEvent> snapshot;
        synchronized (events) {
            snapshot = new ArrayList<>(events);
        }
        for (var event : snapshot) {
            switch (event) {
                case OutputEvent.Thinking e -> sink.onThinkingDelta(e.delta());
                case OutputEvent.Text e -> sink.onTextDelta(e.delta());
                case OutputEvent.ToolUse e -> sink.onToolUse(e.toolName());
                case OutputEvent.Error e -> sink.onStreamError(e.error());
                case OutputEvent.Cancelled _ -> sink.onStreamCancelled();
                case OutputEvent.Complete e -> sink.onTurnComplete(e.result(), e.hasError());
                case OutputEvent.ConnectionClosed _ -> sink.onConnectionClosed();
            }
        }
    }

    /**
     * Returns the number of buffered events.
     */
    public int size() {
        return events.size();
    }

    /**
     * Sealed event hierarchy for buffered output events.
     */
    public sealed interface OutputEvent {
        record Thinking(String delta) implements OutputEvent {}
        record Text(String delta) implements OutputEvent {}
        record ToolUse(String toolName) implements OutputEvent {}
        record Error(String error) implements OutputEvent {}
        record Cancelled() implements OutputEvent {}
        record Complete(JsonNode result, boolean hasError) implements OutputEvent {}
        record ConnectionClosed() implements OutputEvent {}
    }
}
