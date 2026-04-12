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
 * @param llmRequestCount  number of LLM requests sent during this turn
 * @param compactionResult        context compaction result, or null if no compaction occurred
 * @param maxIterationsReached    whether the loop hit max-iterations guardrail
 * @param budgetExhausted         whether the turn was stopped by a watchdog budget limit
 * @param budgetExhaustionReason  the budget that was exhausted: {@code "turn_budget"}, {@code "time_budget"}, or null
 */
public record Turn(
        List<Message> newMessages,
        StopReason finalStopReason,
        Usage totalUsage,
        int llmRequestCount,
        CompactionResult compactionResult,
        boolean maxIterationsReached,
        boolean budgetExhausted,
        String budgetExhaustionReason
) {

    public Turn {
        newMessages = newMessages != null ? List.copyOf(newMessages) : List.of();
        llmRequestCount = Math.max(0, llmRequestCount);
    }

    /**
     * Creates a turn result without compaction info (backward-compatible).
     */
    public Turn(List<Message> newMessages, StopReason finalStopReason, Usage totalUsage) {
        this(newMessages, finalStopReason, totalUsage, 0, null, false, false, null);
    }

    /**
     * Creates a turn result with an LLM request count.
     */
    public Turn(List<Message> newMessages, StopReason finalStopReason, Usage totalUsage,
                int llmRequestCount) {
        this(newMessages, finalStopReason, totalUsage, llmRequestCount, null, false, false, null);
    }

    /**
     * Creates a turn result with optional compaction info (backward-compatible).
     */
    public Turn(List<Message> newMessages, StopReason finalStopReason, Usage totalUsage,
                CompactionResult compactionResult) {
        this(newMessages, finalStopReason, totalUsage, 0, compactionResult, false, false, null);
    }

    /**
     * Creates a turn result with compaction info and an LLM request count.
     */
    public Turn(List<Message> newMessages, StopReason finalStopReason, Usage totalUsage,
                CompactionResult compactionResult, int llmRequestCount) {
        this(newMessages, finalStopReason, totalUsage, llmRequestCount, compactionResult,
                false, false, null);
    }

    /**
     * Creates a turn result with compaction and max-iterations info (backward-compatible).
     */
    public Turn(List<Message> newMessages, StopReason finalStopReason, Usage totalUsage,
                CompactionResult compactionResult, boolean maxIterationsReached) {
        this(newMessages, finalStopReason, totalUsage, 0, compactionResult, maxIterationsReached,
                false, null);
    }

    /**
     * Creates a turn result with compaction, max-iterations info, and an LLM request count.
     */
    public Turn(List<Message> newMessages, StopReason finalStopReason, Usage totalUsage,
                CompactionResult compactionResult, boolean maxIterationsReached,
                int llmRequestCount) {
        this(newMessages, finalStopReason, totalUsage, llmRequestCount, compactionResult,
                maxIterationsReached, false, null);
    }

    /**
     * Creates a turn result with budget info and an LLM request count.
     */
    public Turn(List<Message> newMessages, StopReason finalStopReason, Usage totalUsage,
                CompactionResult compactionResult, boolean maxIterationsReached,
                boolean budgetExhausted, String budgetExhaustionReason) {
        this(newMessages, finalStopReason, totalUsage, 0, compactionResult,
                maxIterationsReached, budgetExhausted, budgetExhaustionReason);
    }

    /**
     * Creates a turn result with full status info and an LLM request count.
     */
    public Turn(List<Message> newMessages, StopReason finalStopReason, Usage totalUsage,
                CompactionResult compactionResult, boolean maxIterationsReached,
                boolean budgetExhausted, String budgetExhaustionReason,
                int llmRequestCount) {
        this(newMessages, finalStopReason, totalUsage, llmRequestCount, compactionResult,
                maxIterationsReached, budgetExhausted, budgetExhaustionReason);
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
