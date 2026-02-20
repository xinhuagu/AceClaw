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
 * <p>Four variants:
 * <ul>
 *   <li>{@link ErrorInsight} — a tool error and its resolution</li>
 *   <li>{@link SuccessInsight} — a successful tool sequence for a task type</li>
 *   <li>{@link PatternInsight} — a recurring behavioral pattern</li>
 *   <li>{@link RecoveryRecipe} — a multi-step recovery procedure for a specific error type</li>
 * </ul>
 */
public sealed interface Insight permits Insight.ErrorInsight, Insight.SuccessInsight, Insight.PatternInsight, Insight.RecoveryRecipe {

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
     * @param errorClass   classification of the error type (auto-classified if null)
     */
    record ErrorInsight(
            String toolName,
            String errorMessage,
            String resolution,
            double confidence,
            ErrorClass errorClass
    ) implements Insight {

        public ErrorInsight {
            Objects.requireNonNull(toolName, "toolName");
            Objects.requireNonNull(errorMessage, "errorMessage");
            Objects.requireNonNull(resolution, "resolution");
            if (confidence < 0.0 || confidence > 1.0) {
                throw new IllegalArgumentException("confidence must be in [0.0, 1.0], got: " + confidence);
            }
            if (errorClass == null) {
                errorClass = ErrorClass.classify(errorMessage);
            }
        }

        /** Backward-compatible factory that auto-classifies the error. */
        public static ErrorInsight of(String toolName, String errorMessage, String resolution, double confidence) {
            return new ErrorInsight(toolName, errorMessage, resolution, confidence, null);
        }

        @Override
        public String description() {
            return "%s: Tool '%s' error: %s — resolved by: %s".formatted(
                    errorClass.name(), toolName, errorMessage, resolution);
        }

        @Override
        public List<String> tags() {
            return List.of(toolName, "error-recovery", errorClass.name().toLowerCase());
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

    /**
     * A single step in a multi-step recovery procedure.
     *
     * @param description   human-readable description of the step
     * @param toolName      the tool to use for this step (nullable if no specific tool)
     * @param parameterHint hint about parameters for the tool (nullable)
     */
    record RecoveryStep(
            String description,
            String toolName,
            String parameterHint
    ) {
        public RecoveryStep {
            Objects.requireNonNull(description, "description");
        }
    }

    /**
     * A multi-step recovery procedure learned from error-correction sequences.
     *
     * @param triggerPattern the error pattern that triggers this recipe
     * @param steps          ordered recovery steps
     * @param toolName       the primary tool involved (for indexing)
     * @param confidence     confidence score in [0.0, 1.0]
     */
    record RecoveryRecipe(
            String triggerPattern,
            List<RecoveryStep> steps,
            String toolName,
            double confidence
    ) implements Insight {

        public RecoveryRecipe {
            Objects.requireNonNull(triggerPattern, "triggerPattern");
            Objects.requireNonNull(steps, "steps");
            steps = List.copyOf(steps);
            Objects.requireNonNull(toolName, "toolName");
            if (confidence < 0.0 || confidence > 1.0) {
                throw new IllegalArgumentException("confidence must be in [0.0, 1.0], got: " + confidence);
            }
            if (steps.isEmpty()) {
                throw new IllegalArgumentException("steps must not be empty");
            }
        }

        @Override
        public String description() {
            var stepDescs = steps.stream()
                    .map(RecoveryStep::description)
                    .toList();
            return "Recovery recipe for '%s': %s".formatted(triggerPattern, String.join(" -> ", stepDescs));
        }

        @Override
        public List<String> tags() {
            var tags = new java.util.ArrayList<String>();
            tags.add(toolName);
            tags.add("recovery-recipe");
            for (var step : steps) {
                if (step.toolName() != null && !tags.contains(step.toolName())) {
                    tags.add(step.toolName());
                }
            }
            return List.copyOf(tags);
        }

        @Override
        public MemoryEntry.Category targetCategory() {
            return MemoryEntry.Category.RECOVERY_RECIPE;
        }
    }
}
