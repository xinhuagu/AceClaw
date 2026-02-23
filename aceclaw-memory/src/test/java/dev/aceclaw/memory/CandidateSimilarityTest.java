package dev.aceclaw.memory;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CandidateSimilarityTest {

    @Test
    void scoreReturnsZeroWhenCategoryDiffers() {
        var a = candidate(MemoryEntry.Category.ERROR_RECOVERY, "bash", "timeout command");
        var b = candidate(MemoryEntry.Category.PREFERENCE, "bash", "timeout command");
        assertThat(CandidateSimilarity.score(a, b)).isEqualTo(0.0);
    }

    @Test
    void scoreReturnsZeroWhenToolTagDiffers() {
        var a = candidate(MemoryEntry.Category.ERROR_RECOVERY, "bash", "timeout command");
        var b = candidate(MemoryEntry.Category.ERROR_RECOVERY, "read_file", "timeout command");
        assertThat(CandidateSimilarity.score(a, b)).isEqualTo(0.0);
    }

    @Test
    void scoreIsHighForCloseContentAndTags() {
        var a = candidate(MemoryEntry.Category.ERROR_RECOVERY, "bash",
                "command timeout after 120 seconds");
        var b = candidate(MemoryEntry.Category.ERROR_RECOVERY, "bash",
                "bash command timed out after 120 sec");
        assertThat(CandidateSimilarity.score(a, b)).isGreaterThan(0.5);
    }

    private static LearningCandidate candidate(MemoryEntry.Category category, String toolTag, String content) {
        return new LearningCandidate(
                "id-" + toolTag + "-" + category.name(),
                category,
                CandidateKind.ERROR_RECOVERY,
                CandidateState.SHADOW,
                content,
                toolTag,
                List.of(toolTag, "failure"),
                0.8,
                1,
                1,
                0,
                Instant.parse("2026-02-22T00:00:00Z"),
                Instant.parse("2026-02-22T00:00:00Z"),
                List.of("session:a"),
                "hmac");
    }
}
