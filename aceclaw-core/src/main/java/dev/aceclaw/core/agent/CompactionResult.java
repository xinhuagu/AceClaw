package dev.aceclaw.core.agent;

import dev.aceclaw.core.llm.Message;
import dev.aceclaw.core.llm.RequestAttribution;

import java.util.List;

/**
 * Result of a context compaction operation.
 *
 * @param compactedMessages     the new (compacted) message list to replace the original
 * @param originalTokenEstimate estimated tokens before compaction
 * @param compactedTokenEstimate estimated tokens after compaction
 * @param phaseReached          the deepest compaction phase that was executed
 * @param extractedContext      key context items extracted during Phase 0 (memory flush)
 * @param requestAttribution    LLM requests this compaction made — empty for phase NONE/PRUNED,
 *                              one COMPACTION_SUMMARY request for phase SUMMARIZED. Callers fold
 *                              this into the parent turn's attribution so the summary call is
 *                              counted and categorised separately from the main turn
 */
public record CompactionResult(
        List<Message> compactedMessages,
        int originalTokenEstimate,
        int compactedTokenEstimate,
        Phase phaseReached,
        List<String> extractedContext,
        RequestAttribution requestAttribution
) {

    public CompactionResult {
        compactedMessages = List.copyOf(compactedMessages);
        extractedContext = List.copyOf(extractedContext);
        requestAttribution = requestAttribution != null ? requestAttribution : RequestAttribution.empty();
    }

    /** Backward-compatible constructor used by callers that predate request attribution. */
    public CompactionResult(List<Message> compactedMessages,
                            int originalTokenEstimate,
                            int compactedTokenEstimate,
                            Phase phaseReached,
                            List<String> extractedContext) {
        this(compactedMessages, originalTokenEstimate, compactedTokenEstimate, phaseReached,
                extractedContext, RequestAttribution.empty());
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
