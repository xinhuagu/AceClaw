package dev.aceclaw.core.llm;

import java.util.EnumMap;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;

/**
 * Breakdown of LLM request counts by {@link RequestSource}. Used to attribute the
 * aggregate {@code llmRequestCount} on {@link dev.aceclaw.core.agent.Turn} and related
 * result types so consumers can see which execution paths drive premium-request spend.
 *
 * <p>The invariant {@link #total()} {@code ==} sum of per-source counts is enforced by
 * construction — the record exposes a read-only map and computes the total once.
 *
 * <p>Instances are immutable; use {@link Builder} to accumulate counts over a scope,
 * then call {@link Builder#build()} to materialise the final snapshot.
 */
public record RequestAttribution(Map<RequestSource, Integer> bySource, int total) {

    private static final RequestAttribution EMPTY =
            new RequestAttribution(Collections.emptyMap(), 0);

    public RequestAttribution {
        Objects.requireNonNull(bySource, "bySource");
        if (total < 0) {
            throw new IllegalArgumentException("total must be non-negative: " + total);
        }
        int sum = 0;
        for (Map.Entry<RequestSource, Integer> e : bySource.entrySet()) {
            if (e.getKey() == null) {
                throw new IllegalArgumentException("null source in bySource map");
            }
            int count = e.getValue() == null ? 0 : e.getValue();
            if (count < 0) {
                throw new IllegalArgumentException(
                        "negative count for " + e.getKey() + ": " + count);
            }
            sum += count;
        }
        if (sum != total) {
            throw new IllegalArgumentException(
                    "total " + total + " does not match sum of per-source counts " + sum);
        }
        // Defensive copy, preserving EnumMap iteration order for deterministic JSON.
        var copy = new EnumMap<RequestSource, Integer>(RequestSource.class);
        for (Map.Entry<RequestSource, Integer> e : bySource.entrySet()) {
            int count = e.getValue() == null ? 0 : e.getValue();
            if (count > 0) {
                copy.put(e.getKey(), count);
            }
        }
        bySource = Collections.unmodifiableMap(copy);
    }

    /** The canonical empty attribution, representing "no LLM requests recorded". */
    public static RequestAttribution empty() {
        return EMPTY;
    }

    /**
     * Returns the count attributed to the given source, or zero if that source never fired.
     */
    public int count(RequestSource source) {
        Objects.requireNonNull(source, "source");
        return bySource.getOrDefault(source, 0);
    }

    /**
     * Merges two attributions into a new one. Counts add per source; the resulting total
     * equals this.total + other.total.
     */
    public RequestAttribution merge(RequestAttribution other) {
        if (other == null || other.total == 0) return this;
        if (this.total == 0) return other;
        var merged = new EnumMap<RequestSource, Integer>(RequestSource.class);
        for (Map.Entry<RequestSource, Integer> e : this.bySource.entrySet()) {
            merged.merge(e.getKey(), e.getValue(), Integer::sum);
        }
        for (Map.Entry<RequestSource, Integer> e : other.bySource.entrySet()) {
            merged.merge(e.getKey(), e.getValue(), Integer::sum);
        }
        return new RequestAttribution(merged, this.total + other.total);
    }

    /** Starts a new mutable accumulator. */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Mutable accumulator for a single scope (one turn, one plan step, one planner call).
     * Not thread-safe; call sites are expected to be single-threaded per scope.
     */
    public static final class Builder {
        private final EnumMap<RequestSource, Integer> counts =
                new EnumMap<>(RequestSource.class);
        private int total;

        private Builder() {}

        public Builder record(RequestSource source) {
            return record(source, 1);
        }

        public Builder record(RequestSource source, int count) {
            Objects.requireNonNull(source, "source");
            if (count < 0) {
                throw new IllegalArgumentException("count must be non-negative: " + count);
            }
            if (count == 0) return this;
            counts.merge(source, count, Integer::sum);
            total += count;
            return this;
        }

        /**
         * Folds another attribution's per-source counts into this builder. Used by the
         * streaming loop to absorb compaction-summary requests produced by MessageCompactor
         * into the parent turn's attribution, and by plan executors to aggregate step
         * attributions into a plan-level total.
         */
        public Builder merge(RequestAttribution other) {
            if (other == null || other.total == 0) return this;
            for (Map.Entry<RequestSource, Integer> e : other.bySource.entrySet()) {
                record(e.getKey(), e.getValue());
            }
            return this;
        }

        public int total() {
            return total;
        }

        public RequestAttribution build() {
            if (total == 0) return EMPTY;
            return new RequestAttribution(counts, total);
        }
    }
}
