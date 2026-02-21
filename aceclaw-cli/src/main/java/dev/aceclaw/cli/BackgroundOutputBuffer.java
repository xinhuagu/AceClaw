package dev.aceclaw.cli;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

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
    public void onToolUse(String toolId, String toolName, String summary) {
        events.add(new OutputEvent.ToolUseDetailed(toolId, toolName, summary));
    }

    @Override
    public void onToolCompleted(String toolId, String toolName,
                                long durationMs, boolean isError, String error) {
        events.add(new OutputEvent.ToolCompleted(toolId, toolName, durationMs, isError, error));
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

    @Override
    public void onPlanCreated(JsonNode params) {
        events.add(new OutputEvent.PlanCreated(params));
    }

    @Override
    public void onPlanStepStarted(JsonNode params) {
        events.add(new OutputEvent.PlanStepStarted(params));
    }

    @Override
    public void onPlanStepCompleted(JsonNode params) {
        events.add(new OutputEvent.PlanStepCompleted(params));
    }

    @Override
    public void onPlanCompleted(JsonNode params) {
        events.add(new OutputEvent.PlanCompleted(params));
    }

    @Override
    public void onSubAgentStart(JsonNode params) {
        events.add(new OutputEvent.SubAgentStart(params));
    }

    @Override
    public void onSubAgentEnd(JsonNode params) {
        events.add(new OutputEvent.SubAgentEnd(params));
    }

    @Override
    public void onCompaction(JsonNode params) {
        events.add(new OutputEvent.Compaction(params));
    }

    /**
     * Replays all buffered events to a foreground sink.
     *
     * @param sink the foreground sink to replay events to
     */
    public void replay(ForegroundOutputSink sink) {
        Objects.requireNonNull(sink, "sink");
        List<OutputEvent> snapshot;
        synchronized (events) {
            snapshot = new ArrayList<>(events);
        }
        for (var event : snapshot) {
            switch (event) {
                case OutputEvent.Thinking e -> sink.onThinkingDelta(e.delta());
                case OutputEvent.Text e -> sink.onTextDelta(e.delta());
                case OutputEvent.ToolUse e -> sink.onToolUse(e.toolName());
                case OutputEvent.ToolUseDetailed e -> sink.onToolUse(e.toolId(), e.toolName(), e.summary());
                case OutputEvent.ToolCompleted e -> sink.onToolCompleted(
                        e.toolId(), e.toolName(), e.durationMs(), e.isError(), e.error());
                case OutputEvent.Error e -> sink.onStreamError(e.error());
                case OutputEvent.Cancelled _ -> sink.onStreamCancelled();
                case OutputEvent.PlanCreated e -> sink.onPlanCreated(e.params());
                case OutputEvent.PlanStepStarted e -> sink.onPlanStepStarted(e.params());
                case OutputEvent.PlanStepCompleted e -> sink.onPlanStepCompleted(e.params());
                case OutputEvent.PlanCompleted e -> sink.onPlanCompleted(e.params());
                case OutputEvent.SubAgentStart e -> sink.onSubAgentStart(e.params());
                case OutputEvent.SubAgentEnd e -> sink.onSubAgentEnd(e.params());
                case OutputEvent.Compaction e -> sink.onCompaction(e.params());
                case OutputEvent.Complete e -> sink.onTurnComplete(e.result(), e.hasError());
                case OutputEvent.ConnectionClosed _ -> sink.onConnectionClosed();
            }
        }
    }

    /**
     * Extracts just the text content from buffered events (no spinners, no thinking).
     * Safe for rendering to a StringWriter without side effects.
     *
     * @return the concatenated text deltas and error messages
     */
    public String extractTextContent() {
        var sb = new StringBuilder();
        synchronized (events) {
            for (var event : events) {
                switch (event) {
                    case OutputEvent.Text e -> sb.append(e.delta());
                    case OutputEvent.Error e -> sb.append("[error: ").append(e.error()).append("]\n");
                    default -> {} // skip thinking, tool use, lifecycle events
                }
            }
        }
        return sb.toString();
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
        record ToolUseDetailed(String toolId, String toolName, String summary) implements OutputEvent {}
        record ToolCompleted(String toolId, String toolName,
                             long durationMs, boolean isError, String error) implements OutputEvent {}
        record Error(String error) implements OutputEvent {}
        record Cancelled() implements OutputEvent {}
        record PlanCreated(JsonNode params) implements OutputEvent {}
        record PlanStepStarted(JsonNode params) implements OutputEvent {}
        record PlanStepCompleted(JsonNode params) implements OutputEvent {}
        record PlanCompleted(JsonNode params) implements OutputEvent {}
        record SubAgentStart(JsonNode params) implements OutputEvent {}
        record SubAgentEnd(JsonNode params) implements OutputEvent {}
        record Compaction(JsonNode params) implements OutputEvent {}
        record Complete(JsonNode result, boolean hasError) implements OutputEvent {}
        record ConnectionClosed() implements OutputEvent {}
    }
}
