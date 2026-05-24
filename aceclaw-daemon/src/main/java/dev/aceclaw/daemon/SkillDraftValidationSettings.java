package dev.aceclaw.daemon;

/**
 * Thematic config grouping for the skill draft validation gate — the 5
 * scalars AceClawConfig used to hold one-by-one for the
 * {@code ValidationGateEngine} constructor. Batch 2 of the AceClawConfig
 * decomposition (batch 1 lifted SkillAutoRelease).
 *
 * <p>Shape:
 * <ul>
 *   <li>{@code enabled} — feature gate. When {@code false} the daemon
 *       skips constructing {@code ValidationGateEngine} entirely and the
 *       draft pipeline runs without the durable-validation step.</li>
 *   <li>{@code strictMode} — fail-closed on missing replay reports etc.</li>
 *   <li>{@code replayRequired} — gate draft promotion on a recent replay
 *       comparison.</li>
 *   <li>{@code replayReportPath} — string path to the replay report JSON
 *       (resolved with {@code Path.of} at the consumer).</li>
 *   <li>{@code maxTokenEstimationErrorRatio} — replay-comparison tolerance
 *       for token-count drift between drafts.</li>
 * </ul>
 *
 * <p>Builder lives here for incremental population during env-var loading +
 * {@code mergeFromFormat}. Package-private setters — only AceClawConfig uses
 * the builder; consumers receive the immutable record.
 */
public record SkillDraftValidationSettings(
        boolean enabled,
        boolean strictMode,
        boolean replayRequired,
        String replayReportPath,
        double maxTokenEstimationErrorRatio) {

    static final String DEFAULT_REPLAY_REPORT_PATH =
            ".aceclaw/metrics/continuous-learning/replay-latest.json";

    /** Defaults matched 1:1 to the prior {@code DEFAULT_SKILL_DRAFT_VALIDATION_*} constants. */
    public static SkillDraftValidationSettings defaults() {
        return new SkillDraftValidationSettings(
                true,                            // enabled
                false,                           // strictMode
                true,                            // replayRequired
                DEFAULT_REPLAY_REPORT_PATH,      // replayReportPath
                0.65                             // maxTokenEstimationErrorRatio
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
        private boolean strictMode;
        private boolean replayRequired;
        private String replayReportPath;
        private double maxTokenEstimationErrorRatio;

        Builder(SkillDraftValidationSettings seed) {
            java.util.Objects.requireNonNull(seed, "seed");
            this.enabled = seed.enabled();
            this.strictMode = seed.strictMode();
            this.replayRequired = seed.replayRequired();
            this.replayReportPath = seed.replayReportPath();
            this.maxTokenEstimationErrorRatio = seed.maxTokenEstimationErrorRatio();
        }

        Builder enabled(Boolean v) { if (v != null) this.enabled = v; return this; }
        Builder strictMode(Boolean v) { if (v != null) this.strictMode = v; return this; }
        Builder replayRequired(Boolean v) { if (v != null) this.replayRequired = v; return this; }
        Builder replayReportPath(String v) {
            if (v != null && !v.isBlank()) this.replayReportPath = v;
            return this;
        }
        Builder maxTokenEstimationErrorRatio(Double v) {
            // Negative ratio would be nonsensical; preserve the pre-decomp behaviour
            // of accepting non-negative values unmodified (no upper clamp — the
            // original code didn't clamp this one either; tolerance can exceed 1.0).
            if (v != null && v >= 0) this.maxTokenEstimationErrorRatio = v;
            return this;
        }

        SkillDraftValidationSettings build() {
            return new SkillDraftValidationSettings(
                    enabled, strictMode, replayRequired,
                    replayReportPath, maxTokenEstimationErrorRatio);
        }
    }
}
