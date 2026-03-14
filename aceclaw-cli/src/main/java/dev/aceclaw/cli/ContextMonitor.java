package dev.aceclaw.cli;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Single source of truth for context window usage tracking.
 *
 * <p>Replaces scattered token counters in {@link TerminalRepl} with a thread-safe,
 * centralized tracker. Distinguishes between per-call context occupation
 * (how much of the context window a single LLM call consumed) and cumulative
 * turn totals (sum of all tokens across all API calls in the session).
 *
 * <p>The key insight: {@code stream.usage} notifications carry per-call input
 * tokens (actual context occupation), while the final JSON-RPC result carries
 * cumulative turn totals. This class keeps them separate to avoid the erratic
 * usage % jumps caused by mixing the two.
 */
public final class ContextMonitor {

    private static final Logger log = LoggerFactory.getLogger(ContextMonitor.class);

    private final int contextWindowTokens;

    /** Per-call input tokens from the most recent LLM call (context occupation). */
    private long lastRealInputTokens;

    /** Cumulative input tokens across all turns in the session. */
    private long totalInputTokens;

    /** Cumulative output tokens across all turns in the session. */
    private long totalOutputTokens;

    /** Threshold warning flags to avoid repeated log spam. */
    private boolean warned70;
    private boolean warned85;
    private boolean warned95;

    public ContextMonitor(int contextWindowTokens) {
        this.contextWindowTokens = Math.max(0, contextWindowTokens);
    }

    /**
     * Records a real-time per-call usage update from {@code stream.usage} notifications.
     * This is the actual context window occupation for the most recent LLM call.
     */
    public synchronized void recordStreamingUsage(long perCallInputTokens) {
        log.trace("recordStreamingUsage: perCall={} (was {}), window={}, pct={}%",
                perCallInputTokens, lastRealInputTokens, contextWindowTokens,
                contextWindowTokens > 0 ? String.format("%.1f", (double) perCallInputTokens / contextWindowTokens * 100.0) : "0.0");
        this.lastRealInputTokens = perCallInputTokens;
    }

    /**
     * Records final turn completion stats from the JSON-RPC result.
     *
     * @param turnCumulativeIn   cumulative input tokens for the entire turn
     * @param turnCumulativeOut  cumulative output tokens for the entire turn
     * @param lastPerCallInputTokens per-call input tokens from the last LLM call in the turn;
     *                               if {@code <= 0}, the existing per-call value is preserved
     *                               (avoids overwriting a valid streaming value with a zero fallback)
     */
    public synchronized void recordTurnComplete(long turnCumulativeIn, long turnCumulativeOut,
                                                 long lastPerCallInputTokens) {
        this.totalInputTokens += turnCumulativeIn;
        this.totalOutputTokens += turnCumulativeOut;
        if (lastPerCallInputTokens > 0) {
            this.lastRealInputTokens = lastPerCallInputTokens;
        }
        log.trace("recordTurnComplete: turnIn={}, turnOut={}, perCall={}, totalIn={}, totalOut={}, ctxPct={}%",
                turnCumulativeIn, turnCumulativeOut, lastPerCallInputTokens,
                totalInputTokens, totalOutputTokens,
                contextWindowTokens > 0 ? String.format("%.1f", (double) lastRealInputTokens / contextWindowTokens * 100.0) : "0.0");
    }

    /**
     * Returns the most recent per-call input tokens, representing actual
     * context window occupation. This is the correct value for context % display.
     */
    public synchronized long currentContextTokens() {
        return lastRealInputTokens;
    }

    /**
     * Returns the configured context window size in tokens.
     */
    public int contextWindow() {
        return contextWindowTokens;
    }

    /**
     * Returns the current context usage as a percentage (0.0 - 100.0+).
     */
    public synchronized double usagePercent() {
        if (contextWindowTokens <= 0) return 0.0;
        return (double) lastRealInputTokens / contextWindowTokens * 100.0;
    }

    /**
     * Returns cumulative input tokens across all turns.
     */
    public synchronized long totalInput() {
        return totalInputTokens;
    }

    /**
     * Returns cumulative output tokens across all turns.
     */
    public synchronized long totalOutput() {
        return totalOutputTokens;
    }

    /**
     * Checks context usage thresholds and logs warnings at 70%, 85%, and 95%.
     * Each threshold is warned about only once per session.
     */
    public synchronized void checkThresholds(Logger log) {
        if (contextWindowTokens <= 0) return;
        double pct = usagePercent();

        if (!warned95 && pct >= 95.0) {
            log.warn("Context usage at {}% ({}/{}) - approaching limit",
                    String.format("%.0f", pct), lastRealInputTokens, contextWindowTokens);
            warned95 = true;
            warned85 = true;
            warned70 = true;
        } else if (!warned85 && pct >= 85.0) {
            log.warn("Context usage at {}% ({}/{}) - consider compacting",
                    String.format("%.0f", pct), lastRealInputTokens, contextWindowTokens);
            warned85 = true;
            warned70 = true;
        } else if (!warned70 && pct >= 70.0) {
            log.info("Context usage at {}% ({}/{})",
                    String.format("%.0f", pct), lastRealInputTokens, contextWindowTokens);
            warned70 = true;
        }
    }
}
