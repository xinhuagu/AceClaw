package dev.aceclaw.daemon;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LearningValidationStoreTest {

    @TempDir
    Path tempDir;

    @Test
    void appendAndReadRecentValidations() throws Exception {
        var store = new LearningValidationStore();
        store.append(tempDir, new LearningValidation(
                Instant.parse("2026-03-13T10:15:30Z"),
                "skill-draft",
                ".aceclaw/skills-drafts/review/SKILL.md",
                "session-1",
                "manual",
                "draft-validation-gate",
                LearningValidation.Verdict.HOLD,
                "Draft awaiting durable validation.",
                List.of(new LearningValidation.Reason("AWAITING_DURABLE_VALIDATION", "Waiting for draft validation.")),
                List.of(new LearningValidation.EvidenceRef("draft-path", "review", "SKILL.md"))));

        var recent = store.recent(tempDir, 10);

        assertThat(recent).hasSize(1);
        assertThat(recent.getFirst().policy()).isEqualTo("draft-validation-gate");
        assertThat(recent.getFirst().verdict()).isEqualTo(LearningValidation.Verdict.HOLD);
    }

    @Test
    void recentSkipsMalformedJsonlRows() throws Exception {
        var store = new LearningValidationStore();
        var file = store.validationsFile(tempDir);
        Files.createDirectories(file.getParent());
        Files.writeString(file, """
                {"timestamp":"2026-03-13T10:15:30Z","targetType":"skill","targetId":"ok","sessionId":"","trigger":"x","policy":"p","verdict":"PASS","summary":"ok","reasons":[],"evidence":[]}
                not-json
                """);

        var recent = store.recent(tempDir, 10);

        assertThat(recent).hasSize(1);
        assertThat(recent.getFirst().targetId()).isEqualTo("ok");
    }
}
