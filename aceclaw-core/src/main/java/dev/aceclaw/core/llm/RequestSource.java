package dev.aceclaw.core.llm;

/**
 * Identifies which execution path issued an LLM request, so the daemon can attribute
 * cumulative request counts by source. Used to build the baseline data needed to tune
 * Copilot premium-request spending (issue #418/#419) without guessing.
 *
 * <p>Per the pre-implementation decision on #419, retries are folded into their parent
 * source rather than carrying a dedicated {@code RETRY} category. A main-turn retry
 * therefore increments {@link #MAIN_TURN} by two, not one each for main + retry.
 */
public enum RequestSource {
    /** Normal streaming or non-streaming agent turn — the ReAct iteration request. */
    MAIN_TURN,

    /** An upfront planning request issued by {@code LLMTaskPlanner}. */
    PLANNER,

    /** A continuation iteration that resumes a turn after context compaction. */
    CONTINUATION,

    /** A plan step executed as a fallback path (e.g. step retry inside the plan executor). */
    FALLBACK,

    /** An adaptive replan request issued after a plan step fails. */
    REPLAN,

    /** The LLM summarization request issued during context compaction's phase 2. */
    COMPACTION_SUMMARY
}
