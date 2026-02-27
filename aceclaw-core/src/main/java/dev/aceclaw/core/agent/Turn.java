package dev.aceclaw.core.agent;

import dev.aceclaw.core.llm.Message;
import dev.aceclaw.core.llm.StopReason;
import dev.aceclaw.core.llm.Usage;

import java.util.List;

/**
 * The result of a single agent turn (one or more ReAct iterations).
 *
 * @param newMessages      all messages produced during this turn (assistant messages and tool results)
 * @param finalStopReason  the stop reason from the last LLM call
 * @param totalUsage       aggregated token usage across all LLM calls in this turn
 * @param compactionResult context compaction result, or null if no compaction occurred
 * @param maxIterationsReached whether the loop hit max-iterations guardrail
 */
public record Turn(
        List<Message> newMessages,
        StopReason finalStopReason,
        Usage totalUsage,
        CompactionResult compactionResult,
        boolean maxIterationsReached
) {

    public Turn {
        newMessages = List.copyOf(newMessages);
    }

    /**
     * Creates a turn result without compaction info (backward-compatible).
     */
    public Turn(List<Message> newMessages, StopReason finalStopReason, Usage totalUsage) {
        this(newMessages, finalStopReason, totalUsage, null, false);
    }

    /**
     * Creates a turn result with optional compaction info (backward-compatible).
     */
    public Turn(List<Message> newMessages, StopReason finalStopReason, Usage totalUsage,
                CompactionResult compactionResult) {
        this(newMessages, finalStopReason, totalUsage, compactionResult, false);
    }

    /**
     * Returns whether context compaction occurred during this turn.
     */
    public boolean wasCompacted() {
        return compactionResult != null;
    }

    /**
     * Convenience: extracts the final text response from the last assistant message.
     * Returns empty string if no text content is present.
     */
    public String text() {
        for (int i = newMessages.size() - 1; i >= 0; i--) {
            if (newMessages.get(i) instanceof Message.AssistantMessage assistant) {
                var text = assistant.content().stream()
                        .filter(b -> b instanceof dev.aceclaw.core.llm.ContentBlock.Text)
                        .map(b -> ((dev.aceclaw.core.llm.ContentBlock.Text) b).text())
                        .reduce("", (a, b) -> a + b);
                if (!text.isEmpty()) {
                    return text;
                }
            }
        }
        return "";
    }
}
