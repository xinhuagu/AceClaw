package dev.aceclaw.infra.event;

import java.time.Instant;

/** Events related to tool execution. */
public sealed interface ToolEvent extends AceClawEvent {

    record Invoked(String sessionId, String toolName, Instant timestamp) implements ToolEvent {}

    record Completed(String sessionId, String toolName, long durationMs, boolean isError, Instant timestamp) implements ToolEvent {}

    record PermissionDenied(String sessionId, String toolName, String reason, Instant timestamp) implements ToolEvent {}
}
