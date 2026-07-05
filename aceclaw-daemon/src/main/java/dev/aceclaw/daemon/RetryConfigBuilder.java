package dev.aceclaw.daemon;

import dev.aceclaw.core.agent.RetryConfig;

/**
 * Mutable builder used by {@code AceClawConfig} to accumulate retry-config
 * overrides during {@code load()}. Produces an immutable
 * {@link RetryConfig} (the consumer-side record in {@code aceclaw-core}).
 *
 * <p>Batch 6 of the AceClawConfig decomposition. Unlike batches 1-5 there is
 * no new "Settings" wrapper record — {@link RetryConfig} already covers it,
 * and consumers (already going through {@code config.retryConfig()}) see no
 * API change. This class only replaces the 4 flat retry fields + 4 ctor
 * inits + 13-line file-merge block in {@code AceClawConfig} with a single
 * builder pattern matching the other clusters.
 *
 * <p>Setters accept {@code null} as "leave previous value alone" and also
 * skip out-of-range values (preserving the pre-decomp {@code if (x != null
 * && x >= 0)} gates — invalid values silently keep the current default
 * rather than throw, so a typo in {@code config.json} doesn't crash
 * daemon startup).
 */
final class RetryConfigBuilder {
    private int maxRetries;
    private long initialBackoffMs;
    private long maxBackoffMs;
    private double jitterFactor;

    RetryConfigBuilder() {
        this(RetryConfig.DEFAULT);
    }

    RetryConfigBuilder(RetryConfig seed) {
        java.util.Objects.requireNonNull(seed, "seed");
        this.maxRetries = seed.maxRetries();
        this.initialBackoffMs = seed.initialBackoffMs();
        this.maxBackoffMs = seed.maxBackoffMs();
        this.jitterFactor = seed.jitterFactor();
    }

    // Pre-decomp file-merge gates (verbatim): null skipped + range-checked;
    // out-of-range silently leaves the current value (no clamp, no throw).
    RetryConfigBuilder maxRetries(Integer v) {
        if (v != null && v >= 0) this.maxRetries = v;
        return this;
    }
    RetryConfigBuilder initialBackoffMs(Long v) {
        if (v != null && v >= 0) this.initialBackoffMs = v;
        return this;
    }
    RetryConfigBuilder maxBackoffMs(Long v) {
        if (v != null && v >= 0) this.maxBackoffMs = v;
        return this;
    }
    RetryConfigBuilder jitterFactor(Double v) {
        if (v != null && v >= 0.0 && v <= 1.0) this.jitterFactor = v;
        return this;
    }

    RetryConfig build() {
        return new RetryConfig(maxRetries, initialBackoffMs, maxBackoffMs, jitterFactor);
    }
}
