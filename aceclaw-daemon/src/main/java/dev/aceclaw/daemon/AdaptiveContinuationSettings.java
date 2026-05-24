package dev.aceclaw.daemon;

/**
 * Thematic config grouping for the adaptive-continuation behaviour — the
 * 5 scalars the agent loop reads to decide whether to extend a turn past
 * a {@code max_tokens} stop, and when to give up. Batch 2 of the
 * AceClawConfig decomposition.
 *
 * <p>Shape:
 * <ul>
 *   <li>{@code enabled} — feature gate. When {@code false} the agent loop
 *       falls back to single-segment behaviour ({@code maxSegments == 1}).</li>
 *   <li>{@code maxSegments} — hard ceiling on continuation rounds within one
 *       turn (default 3).</li>
 *   <li>{@code noProgressThreshold} — consecutive no-progress segments
 *       before bailing out (default 2).</li>
 *   <li>{@code maxTotalTokens} — combined input+output token budget across
 *       segments; 0 disables the check.</li>
 *   <li>{@code maxWallClockSeconds} — wall-clock budget across segments;
 *       0 disables the check.</li>
 * </ul>
 */
public record AdaptiveContinuationSettings(
        boolean enabled,
        int maxSegments,
        int noProgressThreshold,
        int maxTotalTokens,
        int maxWallClockSeconds) {

    /** Defaults matched 1:1 to the prior {@code DEFAULT_ADAPTIVE_CONTINUATION_*} constants. */
    public static AdaptiveContinuationSettings defaults() {
        return new AdaptiveContinuationSettings(
                true,   // enabled
                3,      // maxSegments
                2,      // noProgressThreshold
                0,      // maxTotalTokens — disabled
                0       // maxWallClockSeconds — disabled
        );
    }

    /** Fresh builder pre-loaded with {@link #defaults()}. */
    static Builder builder() {
        return new Builder(defaults());
    }

    /**
     * Mutable builder used during config load. Setters accept {@code null} as
     * "leave the previous value alone" — matches the {@code if (fileConfig.x
     * != null)} pattern AceClawConfig used to inline for each scalar.
     */
    static final class Builder {
        private boolean enabled;
        private int maxSegments;
        private int noProgressThreshold;
        private int maxTotalTokens;
        private int maxWallClockSeconds;

        Builder(AdaptiveContinuationSettings seed) {
            java.util.Objects.requireNonNull(seed, "seed");
            this.enabled = seed.enabled();
            this.maxSegments = seed.maxSegments();
            this.noProgressThreshold = seed.noProgressThreshold();
            this.maxTotalTokens = seed.maxTotalTokens();
            this.maxWallClockSeconds = seed.maxWallClockSeconds();
        }

        Builder enabled(Boolean v) { if (v != null) this.enabled = v; return this; }
        // Pre-decomp clamps: maxSegments >= 1, noProgress >= 1, the two
        // budgets >= 0 (zero means disabled). Preserved here so the builder
        // contract matches the prior behaviour exactly.
        Builder maxSegments(Integer v) { if (v != null && v >= 1) this.maxSegments = v; return this; }
        Builder noProgressThreshold(Integer v) { if (v != null && v >= 1) this.noProgressThreshold = v; return this; }
        Builder maxTotalTokens(Integer v) { if (v != null && v >= 0) this.maxTotalTokens = v; return this; }
        Builder maxWallClockSeconds(Integer v) { if (v != null && v >= 0) this.maxWallClockSeconds = v; return this; }

        AdaptiveContinuationSettings build() {
            return new AdaptiveContinuationSettings(
                    enabled, maxSegments, noProgressThreshold,
                    maxTotalTokens, maxWallClockSeconds);
        }
    }
}
