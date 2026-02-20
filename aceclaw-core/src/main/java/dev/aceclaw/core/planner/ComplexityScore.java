package dev.aceclaw.core.planner;

import java.util.List;

/**
 * Result of complexity estimation for a user prompt.
 *
 * @param score      numeric complexity score (higher = more complex)
 * @param shouldPlan whether the score meets the threshold for planning
 * @param signals    list of detected complexity signals (e.g. "multiple_actions", "refactoring")
 */
public record ComplexityScore(int score, boolean shouldPlan, List<String> signals) {

    public ComplexityScore {
        signals = List.copyOf(signals);
    }
}
