package dev.aceclaw.daemon;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.aceclaw.infra.event.SchedulerEvent;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the wire contract for {@link AceClawDaemon#translateSchedulerEvent}
 * — the daemon-side mapping that turns a typed {@link SchedulerEvent} into
 * the JSON-RPC notification (method + params) the dashboard's
 * {@code useCronJobs} hook will subscribe to.
 *
 * <p>Without this test the only safety net for typos in method names
 * (e.g. {@code "scheduler.job_trigerred"}) or field swaps (e.g. {@code attempt}
 * vs {@code maxAttempts}) would be a manual end-to-end run with both the
 * daemon AND the dashboard up — which #459 layer 2 doesn't exist yet to
 * provide.
 */
final class SchedulerEventTranslationTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private static final Instant T = Instant.parse("2026-05-01T12:00:00Z");

    @Test
    void jobTriggered_translatesToScheduledJobTriggered() {
        var event = new SchedulerEvent.JobTriggered("daily-backup", "0 2 * * *", T);
        var n = AceClawDaemon.translateSchedulerEvent(mapper, event);

        assertThat(n.method()).isEqualTo("scheduler.job_triggered");
        assertThat(n.params().get("jobId").asText()).isEqualTo("daily-backup");
        assertThat(n.params().get("cronExpression").asText()).isEqualTo("0 2 * * *");
        assertThat(n.params().get("timestamp").asText()).isEqualTo(T.toString());
    }

    @Test
    void jobCompleted_translatesToSchedulerJobCompleted() {
        var event = new SchedulerEvent.JobCompleted(
                "daily-backup", 1234L, "Backed up 42 files", T);
        var n = AceClawDaemon.translateSchedulerEvent(mapper, event);

        assertThat(n.method()).isEqualTo("scheduler.job_completed");
        assertThat(n.params().get("jobId").asText()).isEqualTo("daily-backup");
        assertThat(n.params().get("durationMs").asLong()).isEqualTo(1234L);
        assertThat(n.params().get("summary").asText()).isEqualTo("Backed up 42 files");
        assertThat(n.params().get("timestamp").asText()).isEqualTo(T.toString());
    }

    @Test
    void jobFailed_translatesToSchedulerJobFailedWithRetryFields() {
        // Field-swap regression guard: a copy-paste typo that swaps attempt
        // and maxAttempts would silently confuse the dashboard's retry UI
        // (showing "attempt 3/2" or similar) without breaking any other
        // contract — this test pins the assignment.
        var event = new SchedulerEvent.JobFailed(
                "flaky-job", "tool exec timeout", 2, 5, T);
        var n = AceClawDaemon.translateSchedulerEvent(mapper, event);

        assertThat(n.method()).isEqualTo("scheduler.job_failed");
        assertThat(n.params().get("jobId").asText()).isEqualTo("flaky-job");
        assertThat(n.params().get("error").asText()).isEqualTo("tool exec timeout");
        assertThat(n.params().get("attempt").asInt()).isEqualTo(2);
        assertThat(n.params().get("maxAttempts").asInt()).isEqualTo(5);
        assertThat(n.params().get("timestamp").asText()).isEqualTo(T.toString());
    }

    @Test
    void jobSkipped_translatesToSchedulerJobSkipped() {
        var event = new SchedulerEvent.JobSkipped(
                "long-running", "previous run still active", T);
        var n = AceClawDaemon.translateSchedulerEvent(mapper, event);

        assertThat(n.method()).isEqualTo("scheduler.job_skipped");
        assertThat(n.params().get("jobId").asText()).isEqualTo("long-running");
        assertThat(n.params().get("reason").asText()).isEqualTo("previous run still active");
        assertThat(n.params().get("timestamp").asText()).isEqualTo(T.toString());
    }

    @Test
    void allMethodsLiveUnderSchedulerNamespace() {
        // Cheap guard against a partial rename — every event type's method
        // should sit under the scheduler.* namespace so dashboard filters
        // can do a single prefix match.
        var t = AceClawDaemon.translateSchedulerEvent(mapper,
                new SchedulerEvent.JobTriggered("a", "* * * * *", T)).method();
        var c = AceClawDaemon.translateSchedulerEvent(mapper,
                new SchedulerEvent.JobCompleted("a", 1L, "ok", T)).method();
        var f = AceClawDaemon.translateSchedulerEvent(mapper,
                new SchedulerEvent.JobFailed("a", "boom", 1, 1, T)).method();
        var s = AceClawDaemon.translateSchedulerEvent(mapper,
                new SchedulerEvent.JobSkipped("a", "busy", T)).method();

        assertThat(t).startsWith("scheduler.");
        assertThat(c).startsWith("scheduler.");
        assertThat(f).startsWith("scheduler.");
        assertThat(s).startsWith("scheduler.");
    }
}
