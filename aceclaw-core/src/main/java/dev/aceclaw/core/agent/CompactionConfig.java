package dev.aceclaw.core.agent;

/**
 * Configuration for context compaction behavior.
 *
 * <p>Controls when compaction triggers, how aggressively it prunes, and how many
 * recent turns are protected from modification.
 *
 * @param contextWindowTokens   total model context window (e.g., 200,000 for Claude)
 * @param maxOutputTokens       tokens reserved for model output (e.g., 16,384)
 * @param systemPromptTokens    estimated tokens consumed by the system prompt (default 0)
 * @param compactionThreshold   fraction of effective window that triggers compaction (e.g., 0.85)
 * @param pruneTarget           target fraction after Phase 1 pruning (e.g., 0.60)
 * @param protectedTurns        number of recent user-assistant turns to keep intact (e.g., 5)
 */
public record CompactionConfig(
        int contextWindowTokens,
        int maxOutputTokens,
        int systemPromptTokens,
        double compactionThreshold,
        double pruneTarget,
        int protectedTurns
) {

    /** Default configuration for Claude models with 200K context window. */
    public static final CompactionConfig DEFAULT = new CompactionConfig(
            200_000, 16384, 0, 0.85, 0.60, 5);

    public CompactionConfig {
        if (contextWindowTokens <= 0) {
            throw new IllegalArgumentException("contextWindowTokens must be positive");
        }
        if (maxOutputTokens < 0 || maxOutputTokens >= contextWindowTokens) {
            throw new IllegalArgumentException(
                    "maxOutputTokens must be >= 0 and < contextWindowTokens");
        }
        if (systemPromptTokens < 0) {
            throw new IllegalArgumentException("systemPromptTokens must be >= 0");
        }
        if (compactionThreshold <= 0 || compactionThreshold > 1.0) {
            throw new IllegalArgumentException("compactionThreshold must be in (0, 1]");
        }
        if (pruneTarget <= 0 || pruneTarget >= compactionThreshold) {
            throw new IllegalArgumentException(
                    "pruneTarget must be in (0, compactionThreshold)");
        }
        if (protectedTurns < 0) {
            throw new IllegalArgumentException("protectedTurns must be >= 0");
        }
    }

    /**
     * Backward-compatible constructor without systemPromptTokens.
     */
    public CompactionConfig(int contextWindowTokens, int maxOutputTokens,
                            double compactionThreshold, double pruneTarget, int protectedTurns) {
        this(contextWindowTokens, maxOutputTokens, 0, compactionThreshold, pruneTarget, protectedTurns);
    }

    /**
     * Returns a new config with the system prompt token count set.
     *
     * @param tokens estimated system prompt token count
     * @return new config with updated system prompt tokens
     */
    public CompactionConfig withSystemPromptTokens(int tokens) {
        return new CompactionConfig(contextWindowTokens, maxOutputTokens, tokens,
                compactionThreshold, pruneTarget, protectedTurns);
    }

    /**
     * Returns the effective input window for conversation: total context minus
     * reserved output tokens minus system prompt tokens.
     *
     * <p>This is the space actually available for conversation messages and tool
     * definitions. Without accounting for the system prompt, a large prompt could
     * cause compaction to trigger too late or the context to overflow.
     */
    public int effectiveWindowTokens() {
        return Math.max(0, contextWindowTokens - maxOutputTokens - systemPromptTokens);
    }

    /**
     * Returns the token count at which compaction is triggered.
     */
    public int triggerTokens() {
        return (int) (effectiveWindowTokens() * compactionThreshold);
    }

    /**
     * Returns the target token count after Phase 1 (pruning).
     * If pruning achieves this, Phase 2 (summarization) is skipped.
     */
    public int pruneTargetTokens() {
        return (int) (effectiveWindowTokens() * pruneTarget);
    }
}
