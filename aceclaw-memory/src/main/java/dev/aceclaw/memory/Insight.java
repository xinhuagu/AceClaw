package dev.aceclaw.memory;

import java.util.List;
import java.util.Objects;

/**
 * A learned insight extracted from agent behavior during sessions.
 *
 * <p>Insights are the output of detectors ({@code ErrorDetector}, {@code PatternDetector})
 * and the input to the self-improvement engine. Each insight maps to a
 * {@link MemoryEntry.Category} for persistence in auto-memory.
 *
 * <p>Three variants:
 * <ul>
 *   <li>{@link ErrorInsight} — a tool error and its resolution</li>
 *   <li>{@link SuccessInsight} — a successful tool sequence for a task type</li>
 *   <li>{@link PatternInsight} — a recurring behavioral pattern</li>
 * </ul>
 */
public sealed interface Insight permits Insight.ErrorInsight, Insight.SuccessInsight, Insight.PatternInsight {

    /** Human-readable description of the insight. */
    String description();

    /** Searchable tags for memory retrieval. */
    List<String> tags();

    /** The memory category this insight should be stored under. */
    MemoryEntry.Category targetCategory();

    /** Confidence score in [0.0, 1.0]. */
    double confidence();

    /**
     * An insight about a tool error and how it was resolved.
     *
     * @param toolName     the tool that failed
     * @param errorMessage the error output
     * @param resolution   how the error was resolved
     * @param confidence   confidence score in [0.0, 1.0]
     */
    record ErrorInsight(
            String toolName,
            String errorMessage,
            String resolution,
            double confidence
    ) implements Insight {

        public ErrorInsight {
            Objects.requireNonNull(toolName, "toolName");
            Objects.requireNonNull(errorMessage, "errorMessage");
            Objects.requireNonNull(resolution, "resolution");
            if (confidence < 0.0 || confidence > 1.0) {
                throw new IllegalArgumentException("confidence must be in [0.0, 1.0], got: " + confidence);
            }
        }

        @Override
        public String description() {
            return "Tool '%s' error: %s — resolved by: %s".formatted(toolName, errorMessage, resolution);
        }

        @Override
        public List<String> tags() {
            return List.of(toolName, "error-recovery");
        }

        @Override
        public MemoryEntry.Category targetCategory() {
            return MemoryEntry.Category.ERROR_RECOVERY;
        }
    }

    /**
     * An insight about a successful tool sequence for a task type.
     *
     * @param toolSequence    ordered list of tool names that succeeded
     * @param taskDescription what the sequence accomplishes
     * @param confidence      confidence score in [0.0, 1.0]
     */
    record SuccessInsight(
            List<String> toolSequence,
            String taskDescription,
            double confidence
    ) implements Insight {

        public SuccessInsight {
            Objects.requireNonNull(toolSequence, "toolSequence");
            toolSequence = List.copyOf(toolSequence);
            Objects.requireNonNull(taskDescription, "taskDescription");
            if (confidence < 0.0 || confidence > 1.0) {
                throw new IllegalArgumentException("confidence must be in [0.0, 1.0], got: " + confidence);
            }
        }

        @Override
        public String description() {
            return "Successful sequence [%s] for: %s".formatted(String.join(" -> ", toolSequence), taskDescription);
        }

        @Override
        public List<String> tags() {
            var tags = new java.util.ArrayList<>(toolSequence);
            tags.add("successful-strategy");
            return List.copyOf(tags);
        }

        @Override
        public MemoryEntry.Category targetCategory() {
            return MemoryEntry.Category.SUCCESSFUL_STRATEGY;
        }
    }

    /**
     * An insight about a recurring behavioral pattern.
     *
     * @param patternType the classification of the pattern
     * @param description human-readable description
     * @param frequency   how many times the pattern was observed
     * @param confidence  confidence score in [0.0, 1.0]
     * @param evidence    human-readable evidence strings (e.g. "session:abc turn 5: grep->read->edit")
     */
    record PatternInsight(
            PatternType patternType,
            String description,
            int frequency,
            double confidence,
            List<String> evidence
    ) implements Insight {

        public PatternInsight {
            Objects.requireNonNull(patternType, "patternType");
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

        @Override
        public List<String> tags() {
            return List.of(patternType.name().toLowerCase(), "pattern");
        }

        @Override
        public MemoryEntry.Category targetCategory() {
            return switch (patternType) {
                case REPEATED_TOOL_SEQUENCE, WORKFLOW -> MemoryEntry.Category.PATTERN;
                case ERROR_CORRECTION -> MemoryEntry.Category.ERROR_RECOVERY;
                case USER_PREFERENCE -> MemoryEntry.Category.PREFERENCE;
            };
        }
    }
}
