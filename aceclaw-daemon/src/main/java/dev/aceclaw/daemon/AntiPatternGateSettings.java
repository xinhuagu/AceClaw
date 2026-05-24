package dev.aceclaw.daemon;

/**
 * Thematic config grouping for the anti-pattern pre-execution gate's
 * feedback / rollback thresholds. Batch 3 of the AceClawConfig
 * decomposition.
 *
 * <p>Shape:
 * <ul>
 *   <li>{@code minBlockedBeforeRollback} — minimum number of times a
 *       candidate rule must block before its false-positive rate is
 *       evaluated for auto-rollback. Avoids over-rolling-back rules
 *       that have only fired once or twice.</li>
 *   <li>{@code maxFalsePositiveRate} — false-positive rate threshold
 *       above which a candidate-derived rule is auto-rolled-back
 *       (clamped to [0, 1]).</li>
 * </ul>
 *
 * <p>No {@code enabled} flag — the anti-pattern gate is always on; these
 * two scalars only tune its rollback behaviour. Consumers that need to
 * disable the gate entirely do so via the underlying feature module's
 * own switches (candidate promotion, candidate store presence).
 */
public record AntiPatternGateSettings(
        int minBlockedBeforeRollback,
        double maxFalsePositiveRate) {

    /** Defaults matched 1:1 to the prior {@code DEFAULT_ANTI_PATTERN_GATE_*} constants. */
    public static AntiPatternGateSettings defaults() {
        return new AntiPatternGateSettings(2, 0.50);
    }

    /** Fresh builder pre-loaded with {@link #defaults()}. */
    static Builder builder() {
        return new Builder(defaults());
    }

    static final class Builder {
        private int minBlockedBeforeRollback;
        private double maxFalsePositiveRate;

        Builder(AntiPatternGateSettings seed) {
            this.minBlockedBeforeRollback = seed.minBlockedBeforeRollback();
            this.maxFalsePositiveRate = seed.maxFalsePositiveRate();
        }

        Builder minBlockedBeforeRollback(Integer v) {
            if (v != null && v > 0) this.minBlockedBeforeRollback = v;
            return this;
        }
        Builder maxFalsePositiveRate(Double v) {
            // The pre-decomp code clamped this one (clampRate); preserve.
            if (v != null && v >= 0) this.maxFalsePositiveRate = clampRate(v);
            return this;
        }

        AntiPatternGateSettings build() {
            return new AntiPatternGateSettings(minBlockedBeforeRollback, maxFalsePositiveRate);
        }

        private static double clampRate(double v) {
            if (v < 0.0) return 0.0;
            if (v > 1.0) return 1.0;
            return v;
        }
    }
}
