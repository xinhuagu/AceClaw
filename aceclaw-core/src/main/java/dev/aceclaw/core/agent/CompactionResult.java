package dev.aceclaw.core.agent;

import dev.aceclaw.core.llm.Message;

import java.util.List;

/**
 * Result of a context compaction operation.
 *
 * @param compactedMessages     the new (compacted) message list to replace the original
 * @param originalTokenEstimate estimated tokens before compaction
 * @param compactedTokenEstimate estimated tokens after compaction
 * @param phaseReached          the deepest compaction phase that was executed
 * @param extractedContext      key context items extracted during Phase 0 (memory flush)
 */
public record CompactionResult(
        List<Message> compactedMessages,
        int originalTokenEstimate,
        int compactedTokenEstimate,
        Phase phaseReached,
        List<String> extractedContext
) {

    public CompactionResult {
        compactedMessages = List.copyOf(compactedMessages);
        extractedContext = List.copyOf(extractedContext);
    }

    /**
     * The compaction phase that was executed.
     */
    public enum Phase {
        /** No compaction was needed. */
        NONE,
        /** Phase 1 only: old tool results pruned, thinking blocks cleared. */
        PRUNED,
        /** Phase 2: LLM-generated summary replaced old conversation history. */
        SUMMARIZED
    }

    /**
     * Returns the percentage of tokens reduced by compaction.
     */
    public double reductionPercent() {
        if (originalTokenEstimate == 0) return 0;
        return 100.0 * (1.0 - (double) compactedTokenEstimate / originalTokenEstimate);
    }
}
