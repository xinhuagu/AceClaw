package dev.aceclaw.infra.event;

import java.time.Instant;

/** Events related to session lifecycle. */
public sealed interface SessionEvent extends AceClawEvent {

    record Created(String sessionId, String projectPath, Instant timestamp) implements SessionEvent {}

    record Resumed(String sessionId, Instant timestamp) implements SessionEvent {}

    record Closed(String sessionId, String reason, Instant timestamp) implements SessionEvent {}
}
