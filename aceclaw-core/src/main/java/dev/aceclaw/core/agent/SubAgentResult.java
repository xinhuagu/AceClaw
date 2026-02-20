package dev.aceclaw.core.agent;

/**
 * The result of a sub-agent execution, including the text response,
 * the full turn details, and the conversation transcript.
 *
 * @param taskId     unique identifier for this sub-agent execution
 * @param text       the final text response from the sub-agent
 * @param turn       the full turn result with messages, usage, and stop reason
 * @param transcript the conversation transcript for debugging/auditing
 */
public record SubAgentResult(
        String taskId,
        String text,
        Turn turn,
        SubAgentTranscript transcript
) {}
