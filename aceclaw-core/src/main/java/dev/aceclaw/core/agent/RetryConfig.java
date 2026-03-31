package dev.aceclaw.core.agent;

/**
 * Configuration for retry behavior on transient API errors (rate-limit, overloaded).
 *
 * <p>Defaults are tuned for Claude Code OAuth token-bucket rate limiting,
 * matching the Anthropic JS SDK's retry strategy.
 *
 * @param maxRetries       maximum retry attempts (0 = no retries)
 * @param initialBackoffMs initial backoff delay in milliseconds (doubles each attempt)
 * @param maxBackoffMs     ceiling for backoff delay in milliseconds
 * @param jitterFactor     jitter as a fraction of backoff (0.0–1.0; e.g. 0.25 = ±25%)
 */
public record RetryConfig(
        int maxRetries,
        long initialBackoffMs,
        long maxBackoffMs,
        double jitterFactor
) {

    /** Default retry config matching Anthropic SDK behavior. */
    public static final RetryConfig DEFAULT = new RetryConfig(5, 500, 60_000, 0.25);

    public RetryConfig {
        if (maxRetries < 0) {
            throw new IllegalArgumentException("maxRetries must be >= 0, got " + maxRetries);
        }
        if (initialBackoffMs < 0) {
            throw new IllegalArgumentException("initialBackoffMs must be >= 0, got " + initialBackoffMs);
        }
        if (maxBackoffMs < 0) {
            throw new IllegalArgumentException("maxBackoffMs must be >= 0, got " + maxBackoffMs);
        }
        if (jitterFactor < 0.0 || jitterFactor > 1.0) {
            throw new IllegalArgumentException("jitterFactor must be in [0.0, 1.0], got " + jitterFactor);
        }
    }

    /**
     * Calculates backoff delay for a given attempt (0-based).
     * Uses exponential backoff with jitter, capped at {@link #maxBackoffMs()}.
     *
     * @param attempt    0-based attempt index
     * @param retryAfterMs server-suggested delay in ms (-1 if not specified)
     * @return delay in milliseconds
     */
    public long calculateBackoffMs(int attempt, long retryAfterMs) {
        if (retryAfterMs > 0) {
            return Math.min(retryAfterMs, maxBackoffMs);
        }
        long baseMs = initialBackoffMs * (1L << attempt);
        long jitter = (long) (baseMs * jitterFactor * Math.random());
        return Math.min(baseMs + jitter, maxBackoffMs);
    }
}
