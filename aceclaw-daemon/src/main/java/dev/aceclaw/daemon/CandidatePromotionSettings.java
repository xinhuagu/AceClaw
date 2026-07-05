package dev.aceclaw.daemon;

/**
 * Thematic config grouping for candidate promotion — the 4 scalars
 * AceClawConfig used to hold for the {@code CandidateStateMachine.Config}
 * thresholds and the SelfImprovementEngine feature gate. Batch 3 of the
 * AceClawConfig decomposition.
 *
 * <p>Shape:
 * <ul>
 *   <li>{@code enabled} — feature gate. When {@code false} the daemon
 *       skips constructing the CandidateStore entirely (provided injection
 *       is also off) and the self-improvement engine doesn't run promotion
 *       passes.</li>
 *   <li>{@code minEvidence} — minimum evidence count before a candidate
 *       can be considered for promotion.</li>
 *   <li>{@code minScore} — minimum aggregate score for promotion.</li>
 *   <li>{@code maxFailureRate} — upper bound on observed failure rate that
 *       still allows promotion.</li>
 * </ul>
 *
 * <p>Note: {@code minEvidence}, {@code minScore}, {@code maxFailureRate}
 * feed directly into a {@code CandidateStateMachine.Config} constructor at
 * the call site. We don't reuse that record here (unlike SkillAutoRelease)
 * because {@code CandidateStateMachine.Config} carries additional state
 * (decay parameters, kind allowlist) that lives outside the AceClawConfig
 * surface — exposing it would couple daemon-side config to memory-side
 * details the user doesn't tune via env vars.
 */
public record CandidatePromotionSettings(
        boolean enabled,
        int minEvidence,
        double minScore,
        double maxFailureRate) {

    /** Defaults matched 1:1 to the prior {@code DEFAULT_CANDIDATE_PROMOTION_*} constants. */
    public static CandidatePromotionSettings defaults() {
        return new CandidatePromotionSettings(
                true,   // enabled
                2,      // minEvidence
                0.75,   // minScore
                0.20    // maxFailureRate
        );
    }

    /** Fresh builder pre-loaded with {@link #defaults()}. */
    static Builder builder() {
        return new Builder(defaults());
    }

    static final class Builder {
        private boolean enabled;
        private int minEvidence;
        private double minScore;
        private double maxFailureRate;

        Builder(CandidatePromotionSettings seed) {
            java.util.Objects.requireNonNull(seed, "seed");
            this.enabled = seed.enabled();
            this.minEvidence = seed.minEvidence();
            this.minScore = seed.minScore();
            this.maxFailureRate = seed.maxFailureRate();
        }

        Builder enabled(Boolean v) { if (v != null) this.enabled = v; return this; }
        Builder minEvidence(Integer v) { if (v != null && v > 0) this.minEvidence = v; return this; }
        // minScore / maxFailureRate: the pre-decomp code didn't clamp these
        // to [0,1]. Preserve that behaviour exactly — accept any non-negative
        // value. Clamping would silently change the semantics of an out-of-
        // range config entry from "honor the user's value" to "silently cap."
        Builder minScore(Double v) { if (v != null && v >= 0) this.minScore = v; return this; }
        Builder maxFailureRate(Double v) { if (v != null && v >= 0) this.maxFailureRate = v; return this; }

        CandidatePromotionSettings build() {
            return new CandidatePromotionSettings(enabled, minEvidence, minScore, maxFailureRate);
        }
    }
}
