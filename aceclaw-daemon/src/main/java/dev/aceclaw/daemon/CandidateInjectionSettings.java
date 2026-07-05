package dev.aceclaw.daemon;

/**
 * Thematic config grouping for candidate prompt injection — the 3 scalars
 * AceClawConfig used to hold for the agent handler's per-prompt candidate
 * injection budget. Batch 3 of the AceClawConfig decomposition.
 *
 * <p>Shape:
 * <ul>
 *   <li>{@code enabled} — feature gate. When {@code false} the agent loop
 *       doesn't pull candidates from the store into the prompt; the rest
 *       of the candidate pipeline (promotion, etc.) may still run.</li>
 *   <li>{@code maxCount} — upper bound on number of candidates injected
 *       per prompt.</li>
 *   <li>{@code maxTokens} — upper bound on token budget consumed by the
 *       injected candidate block.</li>
 * </ul>
 *
 * <p>Legacy: the {@code candidateInjectionMaxChars} JSON key (pre-token-
 * counter days) maps to {@code maxTokens} via approximate char-to-token
 * conversion ({@code chars / 4}). Handled at the merge call site, not
 * here, so the builder API stays clean.
 */
public record CandidateInjectionSettings(
        boolean enabled,
        int maxCount,
        int maxTokens) {

    /** Defaults matched 1:1 to the prior {@code DEFAULT_CANDIDATE_INJECTION_*} constants. */
    public static CandidateInjectionSettings defaults() {
        return new CandidateInjectionSettings(
                true,   // enabled
                10,     // maxCount
                1200    // maxTokens
        );
    }

    /** Fresh builder pre-loaded with {@link #defaults()}. */
    static Builder builder() {
        return new Builder(defaults());
    }

    static final class Builder {
        private boolean enabled;
        private int maxCount;
        private int maxTokens;

        Builder(CandidateInjectionSettings seed) {
            java.util.Objects.requireNonNull(seed, "seed");
            this.enabled = seed.enabled();
            this.maxCount = seed.maxCount();
            this.maxTokens = seed.maxTokens();
        }

        Builder enabled(Boolean v) { if (v != null) this.enabled = v; return this; }
        Builder maxCount(Integer v) { if (v != null && v >= 0) this.maxCount = v; return this; }
        Builder maxTokens(Integer v) { if (v != null && v >= 0) this.maxTokens = v; return this; }

        CandidateInjectionSettings build() {
            return new CandidateInjectionSettings(enabled, maxCount, maxTokens);
        }
    }
}
