package dev.aceclaw.memory;

/**
 * Types of patterns that can be detected from agent behavior across sessions.
 *
 * <p>Used by the pattern detector to classify recurring behaviors
 * and feed them into the self-learning pipeline.
 */
public enum PatternType {

    /** Same tool sequence (3+ tools) appears 3+ times across sessions. */
    REPEATED_TOOL_SEQUENCE,

    /** Tool error followed by a successful retry with different parameters. */
    ERROR_CORRECTION,

    /** User corrects agent behavior 2+ times in the same way. */
    USER_PREFERENCE,

    /** Multi-step operation performed 3+ times with similar structure. */
    WORKFLOW
}
