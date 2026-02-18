package dev.aceclaw.daemon;

/**
 * Configuration for system prompt size limits.
 *
 * <p>Inspired by OpenClaw's two-tier character caps for bootstrap file injection.
 * Prevents the system prompt from consuming too much of the context window.
 *
 * @param maxPerTierChars maximum characters per individual memory tier (default 20,000)
 * @param maxTotalChars   maximum total characters for the entire system prompt (default 150,000)
 */
public record SystemPromptBudget(int maxPerTierChars, int maxTotalChars) {

    /** Default budget: 20K per tier, 150K total. */
    public static final SystemPromptBudget DEFAULT = new SystemPromptBudget(20_000, 150_000);

    public SystemPromptBudget {
        if (maxPerTierChars <= 0) {
            throw new IllegalArgumentException("maxPerTierChars must be positive");
        }
        if (maxTotalChars <= 0) {
            throw new IllegalArgumentException("maxTotalChars must be positive");
        }
    }
}
