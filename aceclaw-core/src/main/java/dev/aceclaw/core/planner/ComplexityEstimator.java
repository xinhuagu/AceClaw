package dev.aceclaw.core.planner;

import java.util.ArrayList;
import java.util.regex.Pattern;

/**
 * Heuristic estimator that scores the complexity of a user prompt
 * to decide whether upfront planning is beneficial.
 *
 * <p>Uses regex-based pattern matching to detect complexity signals
 * such as multiple actions, multiple files, refactoring, testing, etc.
 * No LLM call is made — this is a fast, deterministic check.
 */
public final class ComplexityEstimator {

    private static final int DEFAULT_THRESHOLD = 5;

    // -- Heuristic patterns ---------------------------------------------------

    private static final Pattern MULTIPLE_ACTIONS = Pattern.compile(
            "(?i)(and then|after that|first.*then|next.*then|"
            + "step\\s*\\d|\\d+\\.\\s+\\w|finally|additionally|also.*need|"
            + "followed by|once.*done|before.*after)",
            Pattern.DOTALL);

    private static final Pattern MULTIPLE_FILES = Pattern.compile(
            "(?i)(\\S+\\.(java|py|ts|js|go|rs|rb|c|cpp|h|kt|scala|swift|yaml|yml|json|xml|html|css|sql|md|txt)"
            + ".*\\S+\\.(java|py|ts|js|go|rs|rb|c|cpp|h|kt|scala|swift|yaml|yml|json|xml|html|css|sql|md|txt)"
            + "|multiple files|several files|across files|all files|every file"
            + "|modules|components|packages)");

    private static final Pattern RESEARCH_FIRST = Pattern.compile(
            "(?i)(investigate|analyze|understand|find out|explore|look into"
            + "|figure out|examine|check how|review the|study)");

    private static final Pattern TESTING_DEPLOYMENT = Pattern.compile(
            "(?i)(add tests|write tests|run\\s+(?:the\\s+)?tests|unit test|integration test"
            + "|deploy|CI/CD|build and|pipeline|coverage|verify|validate)");

    private static final Pattern REFACTORING = Pattern.compile(
            "(?i)(refactor|restructure|reorganize|rename across|move to"
            + "|extract|split|merge|consolidate|decouple|migrate)");

    private static final int LONG_PROMPT_WORD_THRESHOLD = 50;

    private final int threshold;

    public ComplexityEstimator() {
        this(DEFAULT_THRESHOLD);
    }

    public ComplexityEstimator(int threshold) {
        this.threshold = threshold;
    }

    /**
     * Estimates the complexity of the given goal.
     *
     * @param goal the user's prompt text
     * @return a complexity score with detected signals
     */
    public ComplexityScore estimate(String goal) {
        if (goal == null || goal.isBlank()) {
            return new ComplexityScore(0, false, java.util.List.of());
        }

        int score = 0;
        var signals = new ArrayList<String>();

        if (MULTIPLE_ACTIONS.matcher(goal).find()) {
            score += 3;
            signals.add("multiple_actions");
        }
        if (MULTIPLE_FILES.matcher(goal).find()) {
            score += 2;
            signals.add("multiple_files");
        }
        if (RESEARCH_FIRST.matcher(goal).find()) {
            score += 2;
            signals.add("research_first");
        }
        if (TESTING_DEPLOYMENT.matcher(goal).find()) {
            score += 2;
            signals.add("testing");
        }
        if (REFACTORING.matcher(goal).find()) {
            score += 3;
            signals.add("refactoring");
        }
        if (isLongPrompt(goal)) {
            score += 1;
            signals.add("long_prompt");
        }

        return new ComplexityScore(score, score >= threshold, signals);
    }

    private static boolean isLongPrompt(String goal) {
        return goal.split("\\s+").length > LONG_PROMPT_WORD_THRESHOLD;
    }
}
