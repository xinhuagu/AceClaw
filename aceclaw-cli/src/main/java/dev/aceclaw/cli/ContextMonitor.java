package dev.aceclaw.cli;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayDeque;

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
    private static final int MAX_HISTORY_SAMPLES = 32;

    private final int contextWindowTokens;
    private final ArrayDeque<Long> recentSamples = new ArrayDeque<>(MAX_HISTORY_SAMPLES);

    /** Per-call input tokens from the most recent LLM call (context occupation). */
    private long lastRealInputTokens;
    /** Highest observed per-call input tokens in the session. */
    private long peakContextTokens;

    /** Cumulative input tokens across all turns in the session. */
    private long totalInputTokens;

    /** Cumulative output tokens across all turns in the session. */
    private long totalOutputTokens;
    /** Number of compaction events observed in the session. */
    private int compactionCount;
    /** Most recent compaction pre-compaction token estimate. */
    private long lastCompactionOriginalTokens;
    /** Most recent compaction post-compaction token estimate. */
    private long lastCompactionCompactedTokens;
    /** Most recent compaction phase. */
    private String lastCompactionPhase;
    /** When the most recent compaction occurred. */
    private Instant lastCompactionAt;

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
        this.peakContextTokens = Math.max(peakContextTokens, perCallInputTokens);
        appendSample(perCallInputTokens);
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
        this.peakContextTokens = Math.max(peakContextTokens, lastRealInputTokens);
        appendSample(lastRealInputTokens);
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
     * Records a context compaction event and updates the visible context estimate.
     */
    public synchronized void recordCompaction(long originalTokens, long compactedTokens, String phase) {
        long normalizedOriginal = Math.max(0L, originalTokens);
        long normalizedCompacted = Math.max(0L, compactedTokens);
        this.compactionCount++;
        this.lastCompactionOriginalTokens = normalizedOriginal;
        this.lastCompactionCompactedTokens = normalizedCompacted;
        this.lastCompactionPhase = phase == null || phase.isBlank() ? "UNKNOWN" : phase;
        this.lastCompactionAt = Instant.now();
        this.lastRealInputTokens = normalizedCompacted;
        this.peakContextTokens = Math.max(peakContextTokens, Math.max(normalizedOriginal, normalizedCompacted));
        appendSample(normalizedCompacted);
    }

    /**
     * Returns the highest observed context occupation in the session.
     */
    public synchronized long peakContextTokens() {
        return peakContextTokens;
    }

    /**
     * Returns the number of retained usage samples.
     */
    public synchronized int sampleCount() {
        return recentSamples.size();
    }

    /**
     * Returns the recent direction of context growth across retained samples.
     */
    public synchronized Trend recentTrend() {
        if (recentSamples.size() < 2) return Trend.UNKNOWN;
        long last = recentSamples.removeLast();
        long previous = recentSamples.peekLast();
        recentSamples.addLast(last);
        if (previous == 0 && last == 0) return Trend.STABLE;
        long tolerance = contextWindowTokens > 0
                ? Math.max(256L, Math.round(contextWindowTokens * 0.02))
                : 256L;
        long delta = last - previous;
        if (Math.abs(delta) <= tolerance) return Trend.STABLE;
        return delta > 0 ? Trend.RISING : Trend.FALLING;
    }

    /**
     * Returns the current context pressure band.
     */
    public synchronized PressureLevel pressureLevel() {
        double pct = usagePercent();
        if (pct >= 95.0) return PressureLevel.CRITICAL;
        if (pct >= 85.0) return PressureLevel.COMPACT;
        if (pct >= 70.0) return PressureLevel.WATCH;
        return PressureLevel.NORMAL;
    }

    public synchronized int compactionCount() {
        return compactionCount;
    }

    public synchronized long lastCompactionOriginalTokens() {
        return lastCompactionOriginalTokens;
    }

    public synchronized long lastCompactionCompactedTokens() {
        return lastCompactionCompactedTokens;
    }

    public synchronized String lastCompactionPhase() {
        return lastCompactionPhase;
    }

    public synchronized Instant lastCompactionAt() {
        return lastCompactionAt;
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

    private void appendSample(long tokens) {
        long normalized = Math.max(0L, tokens);
        if (!recentSamples.isEmpty() && recentSamples.peekLast() == normalized) {
            return;
        }
        if (recentSamples.size() == MAX_HISTORY_SAMPLES) {
            recentSamples.removeFirst();
        }
        recentSamples.addLast(normalized);
    }

    public enum Trend {
        UNKNOWN("unknown"),
        STABLE("stable"),
        RISING("rising"),
        FALLING("falling");

        private final String label;

        Trend(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    public enum PressureLevel {
        NORMAL("normal"),
        WATCH("watch"),
        COMPACT("compact"),
        CRITICAL("critical");

        private final String label;

        PressureLevel(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }
}
