package dev.aceclaw.infra.event;

import java.time.Instant;

/** Events related to the agent loop lifecycle. */
public sealed interface AgentEvent extends AceClawEvent {

    record TurnStarted(String sessionId, int turnNumber, Instant timestamp) implements AgentEvent {}

    record TurnCompleted(String sessionId, int turnNumber, long durationMs, Instant timestamp) implements AgentEvent {}

    record TurnError(String sessionId, int turnNumber, String error, Instant timestamp) implements AgentEvent {}

    record CompactionTriggered(String sessionId, int estimatedTokensBefore, int estimatedTokensAfter, Instant timestamp) implements AgentEvent {}
}
