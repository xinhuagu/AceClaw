package dev.aceclaw.daemon;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class LearningSignalReviewStoreTest {

    @TempDir
    Path tempDir;

    @Test
    void appendAndRecent_roundTripsReviewsNewestFirst() throws Exception {
        var store = new LearningSignalReviewStore();
        store.append(tempDir, new LearningSignalReview(
                Instant.parse("2026-03-14T10:15:30Z"),
                "trend",
                "tool-error-rate",
                LearningSignalReview.Action.SUPPRESS,
                "Human review marked trend 'tool-error-rate' as suppress.",
                "too noisy",
                "cli",
                "session-1",
                java.util.List.of("human-review", "suppress", "trend")));
        store.append(tempDir, new LearningSignalReview(
                Instant.parse("2026-03-14T11:15:30Z"),
                "runtime_skill",
                "retry-flow",
                LearningSignalReview.Action.PIN,
                "Human review marked runtime_skill 'retry-flow' as pin.",
                "résumé",
                "cli",
                "session-2",
                java.util.List.of("human-review", "pin", "runtime_skill")));

        var recent = store.recent(tempDir, 10);
        assertThat(recent).hasSize(2);
        assertThat(recent.get(0).targetType()).isEqualTo("runtime_skill");
        assertThat(recent.get(0).note()).isEqualTo("résumé");
        assertThat(recent.get(1).targetId()).isEqualTo("tool-error-rate");
    }

    @Test
    void latestByTarget_usesNewestReviewPerTargetAndSkipsMalformedRows() throws Exception {
        var store = new LearningSignalReviewStore();
        store.append(tempDir, new LearningSignalReview(
                Instant.parse("2026-03-14T09:00:00Z"),
                "trend",
                "same-target",
                LearningSignalReview.Action.LOW_VALUE,
                "older",
                "",
                "cli",
                "session-1",
                java.util.List.of("human-review")));
        Path file = tempDir.resolve(".aceclaw/metrics/learning-signal-reviews.jsonl");
        Files.writeString(
                file,
                "{bad-json}\n",
                StandardCharsets.UTF_8,
                java.nio.file.StandardOpenOption.APPEND);
        store.append(tempDir, new LearningSignalReview(
                Instant.parse("2026-03-14T12:00:00Z"),
                "trend",
                "same-target",
                LearningSignalReview.Action.UNSUPPRESS,
                "newer",
                "",
                "cli",
                "session-2",
                java.util.List.of("human-review")));

        var latest = store.latestByTarget(tempDir, 10);
        assertThat(latest).hasSize(1);
        assertThat(latest.get("trend:same-target").action()).isEqualTo(LearningSignalReview.Action.UNSUPPRESS);
    }
}
