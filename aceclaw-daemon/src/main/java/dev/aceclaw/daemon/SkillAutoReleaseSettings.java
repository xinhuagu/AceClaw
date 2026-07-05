package dev.aceclaw.daemon;

import dev.aceclaw.learning.validation.AutoReleaseController;

import java.time.Duration;

/**
 * Thematic config grouping for the skill auto-release subsystem — the 12
 * scalars AceClawConfig used to hold one-by-one for the AutoReleaseController
 * canary/rollback gates. First pilot of the AceClawConfig decomposition
 * pattern (the god class had grown to 152 instance fields + 85 getters; each
 * subsystem is being lifted into its own small record).
 *
 * <p>Shape:
 * <ul>
 *   <li>{@code enabled} — feature gate (was {@code skillAutoReleaseEnabled})</li>
 *   <li>{@code tuning} — the 11-scalar {@link AutoReleaseController.Config}
 *       record that the controller already accepts in its constructor.
 *       Reusing the consumer-side record means daemon wireup goes from
 *       "pass 11 scalars by hand" to "pass one record argument."</li>
 * </ul>
 *
 * <p>Builder lives here for incremental population during env-var loading +
 * {@code mergeFromFormat} — kept package-private; only {@code AceClawConfig}
 * uses it.
 */
public record SkillAutoReleaseSettings(boolean enabled, AutoReleaseController.Config tuning) {

    /** Defaults matched 1:1 to the prior {@code DEFAULT_SKILL_AUTO_RELEASE_*} constants. */
    public static SkillAutoReleaseSettings defaults() {
        return new SkillAutoReleaseSettings(
                true,
                new AutoReleaseController.Config(
                        0.80,   // minCandidateScore
                        3,      // minEvidenceCount
                        20,     // canaryMinAttempts
                        0.10,   // canaryMaxFailureRate
                        0.20,   // canaryMaxTimeoutRate
                        0.20,   // canaryMaxPermissionRate
                        0.20,   // rollbackMaxFailureRate
                        0.20,   // rollbackMaxTimeoutRate
                        0.20,   // rollbackMaxPermissionRate
                        Duration.ofHours(168),   // healthLookback (was 168h = 7 days)
                        24                       // canaryDwellHours
                ));
    }

    /** Fresh builder pre-loaded with {@link #defaults()}. */
    static Builder builder() {
        return new Builder(defaults());
    }

    /**
     * Mutable builder used by {@code AceClawConfig} to accumulate per-source
     * overrides (file → env var → profile) without re-allocating the record
     * on every field touch. Call {@link #build()} once when loading is done.
     *
     * <p>Setters return {@code this} but also accept {@code null} as "leave
     * the previous value alone" — matches the {@code if (fileConfig.x != null)}
     * pattern AceClawConfig used to inline for each scalar.
     */
    static final class Builder {
        private boolean enabled;
        private double minCandidateScore;
        private int minEvidenceCount;
        private int canaryMinAttempts;
        private double canaryMaxFailureRate;
        private double canaryMaxTimeoutRate;
        private double canaryMaxPermissionRate;
        private double rollbackMaxFailureRate;
        private double rollbackMaxTimeoutRate;
        private double rollbackMaxPermissionRate;
        private int healthLookbackHours;
        private int canaryDwellHours;

        Builder(SkillAutoReleaseSettings seed) {
            java.util.Objects.requireNonNull(seed, "seed");
            this.enabled = seed.enabled();
            var t = seed.tuning();
            this.minCandidateScore = t.minCandidateScore();
            this.minEvidenceCount = t.minEvidenceCount();
            this.canaryMinAttempts = t.canaryMinAttempts();
            this.canaryMaxFailureRate = t.canaryMaxFailureRate();
            this.canaryMaxTimeoutRate = t.canaryMaxTimeoutRate();
            this.canaryMaxPermissionRate = t.canaryMaxPermissionRate();
            this.rollbackMaxFailureRate = t.rollbackMaxFailureRate();
            this.rollbackMaxTimeoutRate = t.rollbackMaxTimeoutRate();
            this.rollbackMaxPermissionRate = t.rollbackMaxPermissionRate();
            this.healthLookbackHours = (int) Math.max(1, t.healthLookback().toHours());
            this.canaryDwellHours = t.canaryDwellHours();
        }

        Builder enabled(Boolean v) { if (v != null) this.enabled = v; return this; }
        Builder minCandidateScore(Double v) { if (v != null) this.minCandidateScore = clampRate(v); return this; }
        Builder minEvidenceCount(Integer v) { if (v != null && v > 0) this.minEvidenceCount = v; return this; }
        Builder canaryMinAttempts(Integer v) { if (v != null && v >= 0) this.canaryMinAttempts = v; return this; }
        Builder canaryMaxFailureRate(Double v) { if (v != null) this.canaryMaxFailureRate = clampRate(v); return this; }
        Builder canaryMaxTimeoutRate(Double v) { if (v != null) this.canaryMaxTimeoutRate = clampRate(v); return this; }
        Builder canaryMaxPermissionRate(Double v) { if (v != null) this.canaryMaxPermissionRate = clampRate(v); return this; }
        Builder rollbackMaxFailureRate(Double v) { if (v != null) this.rollbackMaxFailureRate = clampRate(v); return this; }
        Builder rollbackMaxTimeoutRate(Double v) { if (v != null) this.rollbackMaxTimeoutRate = clampRate(v); return this; }
        Builder rollbackMaxPermissionRate(Double v) { if (v != null) this.rollbackMaxPermissionRate = clampRate(v); return this; }
        Builder healthLookbackHours(Integer v) { if (v != null && v > 0) this.healthLookbackHours = v; return this; }
        Builder canaryDwellHours(Integer v) { if (v != null && v >= 0) this.canaryDwellHours = v; return this; }

        /**
         * Legacy alias: {@code skillAutoRelease.activeMaxFailureRate} in
         * older config files maps onto the rollback-failure threshold.
         * Kept as a separate setter so the merge path is explicit at the
         * call site (vs hiding the alias inside a generic setter).
         */
        Builder applyLegacyActiveMaxFailureRate(Double v) {
            if (v != null) this.rollbackMaxFailureRate = clampRate(v);
            return this;
        }

        SkillAutoReleaseSettings build() {
            return new SkillAutoReleaseSettings(
                    enabled,
                    new AutoReleaseController.Config(
                            minCandidateScore,
                            minEvidenceCount,
                            canaryMinAttempts,
                            canaryMaxFailureRate,
                            canaryMaxTimeoutRate,
                            canaryMaxPermissionRate,
                            rollbackMaxFailureRate,
                            rollbackMaxTimeoutRate,
                            rollbackMaxPermissionRate,
                            Duration.ofHours(Math.max(1, healthLookbackHours)),
                            canaryDwellHours));
        }

        private static double clampRate(double value) {
            if (value < 0.0) return 0.0;
            if (value > 1.0) return 1.0;
            return value;
        }
    }
}
