package dev.aceclaw.memory;

import java.util.List;
import java.util.Objects;

/**
 * A pattern detected from agent behavior across sessions.
 *
 * <p>Produced by the pattern detector and consumed by the self-improvement engine.
 * Each pattern carries evidence strings for traceability.
 *
 * @param type        the classification of the pattern
 * @param description human-readable description of what was detected
 * @param frequency   how many times the pattern was observed
 * @param confidence  confidence score in [0.0, 1.0]
 * @param evidence    human-readable evidence (e.g. "session:abc123 turn 5: grep->read->edit")
 */
public record DetectedPattern(
        PatternType type,
        String description,
        int frequency,
        double confidence,
        List<String> evidence
) {

    public DetectedPattern {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(description, "description");
        Objects.requireNonNull(evidence, "evidence");
        evidence = List.copyOf(evidence);
        if (confidence < 0.0 || confidence > 1.0) {
            throw new IllegalArgumentException("confidence must be in [0.0, 1.0], got: " + confidence);
        }
        if (frequency < 0) {
            throw new IllegalArgumentException("frequency must be non-negative, got: " + frequency);
        }
    }

    /**
     * Converts this detected pattern into a {@link Insight.PatternInsight}.
     */
    public Insight.PatternInsight toInsight() {
        return new Insight.PatternInsight(type, description, frequency, confidence, evidence);
    }
}
