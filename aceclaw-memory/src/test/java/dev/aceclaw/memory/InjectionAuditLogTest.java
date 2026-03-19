package dev.aceclaw.memory;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class InjectionAuditLogTest {

    @TempDir
    Path tempDir;

    @Test
    void recordInjection_writesJsonlLine() throws Exception {
        var auditLog = new InjectionAuditLog(tempDir);
        var event = new InjectionAuditLog.InjectionEvent(
                Instant.now(), "session-1", "fix bug",
                List.of(new InjectionAuditLog.InjectedCandidate(
                        "cand-1", "retry flaky commands", "bash", 0.85, 3, 0.72, 45)),
                45, 1200, 5, 1);

        auditLog.recordInjection(event);

        Path auditFile = tempDir.resolve("injection-audit.jsonl");
        assertThat(auditFile).exists();
        var lines = Files.readAllLines(auditFile);
        assertThat(lines).hasSize(1);
        assertThat(lines.getFirst()).contains("\"type\":\"injection\"");
        assertThat(lines.getFirst()).contains("cand-1");
    }

    @Test
    void recordOutcome_writesJsonlLine() throws Exception {
        var auditLog = new InjectionAuditLog(tempDir);
        var outcome = new InjectionAuditLog.InjectionOutcome(
                Instant.now(), "session-1", List.of("cand-1"), true, false, "end_turn");

        auditLog.recordOutcome(outcome);

        Path auditFile = tempDir.resolve("injection-audit.jsonl");
        var lines = Files.readAllLines(auditFile);
        assertThat(lines).hasSize(1);
        assertThat(lines.getFirst()).contains("\"type\":\"outcome\"");
        assertThat(lines.getFirst()).contains("\"success\":true");
    }

    @Test
    void computeHitRate_withMixedOutcomes() throws Exception {
        var auditLog = new InjectionAuditLog(tempDir);

        // 2 successes, 1 failure = 66.7% hit rate
        auditLog.recordOutcome(new InjectionAuditLog.InjectionOutcome(
                Instant.now(), "s1", List.of("c1"), true, false, "end_turn"));
        auditLog.recordOutcome(new InjectionAuditLog.InjectionOutcome(
                Instant.now(), "s2", List.of("c1"), true, false, "end_turn"));
        auditLog.recordOutcome(new InjectionAuditLog.InjectionOutcome(
                Instant.now(), "s3", List.of("c1"), false, true, "error"));

        assertThat(auditLog.computeHitRate()).isCloseTo(0.6667, within(0.01));
    }

    @Test
    void computeHitRate_noData_returnsNaN() {
        var auditLog = new InjectionAuditLog(tempDir);
        assertThat(auditLog.computeHitRate()).isNaN();
    }

    @Test
    void computeHitRate_ignoresInjectionRecords() throws Exception {
        var auditLog = new InjectionAuditLog(tempDir);

        // Only injection records, no outcomes
        auditLog.recordInjection(new InjectionAuditLog.InjectionEvent(
                Instant.now(), "s1", "query", List.of(), 0, 1200, 0, 0));

        assertThat(auditLog.computeHitRate()).isNaN();
    }

    @Test
    void summarize_aggregatesCorrectly() throws Exception {
        var auditLog = new InjectionAuditLog(tempDir);

        auditLog.recordInjection(new InjectionAuditLog.InjectionEvent(
                Instant.now(), "s1", "query",
                List.of(new InjectionAuditLog.InjectedCandidate(
                        "c1", "content", "bash", 0.9, 5, 0.8, 30)),
                30, 1200, 10, 1));
        auditLog.recordInjection(new InjectionAuditLog.InjectionEvent(
                Instant.now(), "s2", "query", List.of(), 0, 1200, 5, 0));
        auditLog.recordOutcome(new InjectionAuditLog.InjectionOutcome(
                Instant.now(), "s1", List.of("c1"), true, false, "end_turn"));
        auditLog.recordOutcome(new InjectionAuditLog.InjectionOutcome(
                Instant.now(), "s2", List.of("c1"), false, false, "max_tokens"));

        var summary = auditLog.summarize();
        assertThat(summary.totalInjections()).isEqualTo(2);
        assertThat(summary.totalOutcomes()).isEqualTo(2);
        assertThat(summary.successfulOutcomes()).isEqualTo(1);
        assertThat(summary.totalCandidatesInjected()).isEqualTo(1);
        assertThat(summary.hitRate()).isCloseTo(0.5, within(0.01));
    }

    @Test
    void summarize_emptyLog_returnsZeros() {
        var auditLog = new InjectionAuditLog(tempDir);
        var summary = auditLog.summarize();
        assertThat(summary.totalInjections()).isEqualTo(0);
        assertThat(summary.hitRate()).isNaN();
    }
}
