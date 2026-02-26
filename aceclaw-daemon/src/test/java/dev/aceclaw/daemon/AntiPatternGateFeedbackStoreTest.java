package dev.aceclaw.daemon;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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
}
