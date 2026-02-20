package dev.aceclaw.core.agent;

import dev.aceclaw.core.llm.Message;

import java.time.Instant;
import java.util.List;

/**
 * Captures the full conversation transcript of a sub-agent execution.
 *
 * <p>Used for debugging, auditing, and understanding what a sub-agent did
 * during its execution. Transcripts are persisted as JSONL files.
 *
 * @param taskId      unique identifier for this sub-agent execution
 * @param agentType   the agent type name (e.g., "explore", "general")
 * @param prompt      the original task prompt given to the sub-agent
 * @param messages    the full conversation history (assistant + tool results)
 * @param startedAt   when the sub-agent started executing
 * @param completedAt when the sub-agent finished (null if still running)
 * @param resultText  the final text response from the sub-agent
 */
public record SubAgentTranscript(
        String taskId,
        String agentType,
        String prompt,
        List<Message> messages,
        Instant startedAt,
        Instant completedAt,
        String resultText
) {

    public SubAgentTranscript {
        messages = messages != null ? List.copyOf(messages) : List.of();
    }
}
