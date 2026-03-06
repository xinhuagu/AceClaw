package dev.aceclaw.memory;

import java.util.regex.Pattern;

/**
 * Rejects candidate content that is clearly non-actionable.
 *
 * <p>Uses a <b>denylist</b> approach: content is considered actionable unless it
 * matches a known non-actionable pattern (pure error observation, bare metric,
 * or is too short/null). This avoids English-bias from an allowlist of action
 * verbs and does not penalize user-preference or non-English content.
 *
 * <p>Used by both {@link CandidateStateMachine} (promotion gate) and
 * {@code SkillDraftGenerator} (draft quality gate).
 */
public final class ActionableContentChecker {

    /** Content below this length is too vague to produce a useful skill draft. */
    private static final int MIN_CONTENT_LENGTH = 20;

    /**
     * Patterns that indicate pure error/metric observations with no remediation.
     * Matched case-insensitively. Each pattern is a self-contained signal of
     * "this is just describing a problem, not a solution".
     */
    private static final Pattern NON_ACTIONABLE_PATTERN = Pattern.compile(
            "^Tool '.*' repeatedly fails"
            + "|\\(\\d+ times in session\\)"
            + "|^error occurred"
            + "|^API returns \\d{3} status"
            + "|^(Permission denied|Operation timed out|Connection refused) on every"
            + "|consistently in all (calls|attempts|requests)"
            + "|^The operation timed out after \\d+",
            Pattern.CASE_INSENSITIVE);

    private ActionableContentChecker() {}

    /**
     * Returns {@code true} unless the content is null, too short, or matches a
     * known non-actionable pattern (pure error observation).
     */
    public static boolean isActionable(String content) {
        if (content == null || content.length() < MIN_CONTENT_LENGTH) {
            return false;
        }
        return !NON_ACTIONABLE_PATTERN.matcher(content).find();
    }
}
