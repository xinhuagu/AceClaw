package dev.aceclaw.daemon;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class AntiPatternGateFeedbackStoreTest {

    @TempDir
    Path tempDir;

    @Test
    void persistsAndReloadsFeedback() {
        var store = new AntiPatternGateFeedbackStore(tempDir);
        store.recordBlocked("candidate:r1");
        store.recordBlocked("candidate:r1");
        store.recordFalsePositive("candidate:r1");

        var reloaded = new AntiPatternGateFeedbackStore(tempDir);
        var stats = reloaded.statsFor("candidate:r1");
        assertThat(stats.blockedCount()).isEqualTo(2);
        assertThat(stats.falsePositiveCount()).isEqualTo(1);
    }

    @Test
    void falsePositiveThresholdRequestsRollback() {
        var store = new AntiPatternGateFeedbackStore(tempDir);
        store.recordBlocked("candidate:r2");
        store.recordBlocked("candidate:r2");
        store.recordBlocked("candidate:r2");

        boolean rollback1 = store.recordFalsePositive("candidate:r2");
        boolean rollback2 = store.recordFalsePositive("candidate:r2");

        assertThat(rollback1).isFalse();
        assertThat(rollback2).isTrue();
    }

    @Test
    void customThresholdsAreApplied() {
        var store = new AntiPatternGateFeedbackStore(tempDir, 5, 0.6);
        store.recordBlocked("candidate:r3");
        store.recordBlocked("candidate:r3");
        store.recordBlocked("candidate:r3");
        store.recordBlocked("candidate:r3");
        store.recordBlocked("candidate:r3");
        store.recordFalsePositive("candidate:r3");
        store.recordFalsePositive("candidate:r3");

        boolean rollback = store.recordFalsePositive("candidate:r3");
        assertThat(rollback).isTrue();
    }

    @Test
    void skipsMalformedEntriesOnLoad() throws Exception {
        Path feedbackFile = tempDir.resolve(".aceclaw/metrics/continuous-learning/anti-pattern-gate-feedback.json");
        Files.createDirectories(feedbackFile.getParent());
        Files.writeString(feedbackFile, """
                [
                  {"ruleId":"candidate:ok","blockedCount":2,"falsePositiveCount":1,"updatedAt":"2026-02-25T12:00:00Z"},
                  {"ruleId":"","blockedCount":3,"falsePositiveCount":1,"updatedAt":"2026-02-25T12:00:00Z"},
                  {"ruleId":"candidate:no-updated","blockedCount":1,"falsePositiveCount":0}
                ]
                """);

        var store = new AntiPatternGateFeedbackStore(tempDir);

        assertThat(store.statsFor("candidate:ok").blockedCount()).isEqualTo(2);
        assertThat(store.statsFor("candidate:ok").falsePositiveCount()).isEqualTo(1);
        assertThat(store.statsFor("candidate:no-updated").blockedCount()).isZero();
    }
}
