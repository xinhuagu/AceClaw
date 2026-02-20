package dev.aceclaw.infra.event;

import java.time.Instant;

/**
 * Events emitted by the cron scheduler during job lifecycle.
 *
 * <p>Permits:
 * <ul>
 *   <li>{@link JobTriggered} — a cron job has been triggered and is starting execution</li>
 *   <li>{@link JobCompleted} — a cron job has completed successfully</li>
 *   <li>{@link JobFailed} — a cron job has failed (with optional retry info)</li>
 *   <li>{@link JobSkipped} — a cron job was skipped (e.g. previous run still active)</li>
 * </ul>
 */
public sealed interface SchedulerEvent extends AceClawEvent {

    /** The cron job identifier. */
    String jobId();

    record JobTriggered(String jobId, String cronExpression, Instant timestamp)
            implements SchedulerEvent {}

    record JobCompleted(String jobId, long durationMs, String summary, Instant timestamp)
            implements SchedulerEvent {}

    record JobFailed(String jobId, String error, int attempt, int maxAttempts, Instant timestamp)
            implements SchedulerEvent {}

    record JobSkipped(String jobId, String reason, Instant timestamp)
            implements SchedulerEvent {}
}
