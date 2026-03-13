package dev.aceclaw.daemon;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LearningValidationRecorderTest {

    @TempDir
    Path tempDir;

    @Test
    void mapsDraftReleaseAndRefinementValidationStates() {
        var store = new LearningValidationStore();
        var recorder = new LearningValidationRecorder(store);

        recorder.recordDraftValidation(tempDir, "manual", new ValidationGateEngine.DraftDecision(
                ".aceclaw/skills-drafts/review/SKILL.md",
                ValidationGateEngine.Verdict.HOLD,
                List.of(new ValidationGateEngine.ReasonCode(
                        "replay",
                        "REPLAY_GATE_FAILED",
                        ValidationGateEngine.Verdict.HOLD,
                        "Replay metrics not good enough")),
                Instant.parse("2026-03-13T10:15:30Z"),
                "manual"));

        recorder.recordReleaseValidation(tempDir, "release-eval", new AutoReleaseController.ReleaseEvent(
                Instant.parse("2026-03-13T10:16:30Z"),
                "release-eval",
                "review",
                AutoReleaseController.Stage.CANARY,
                AutoReleaseController.Stage.SHADOW,
                "AUTO_ROLLBACK_GUARDRAIL_BREACH",
                "Guardrail breach"));

        recorder.recordRefinementValidation(
                tempDir,
                "review",
                SkillRefinementEngine.RefinementAction.STABILIZED,
                "Observation window met baseline");

        var recent = store.recent(tempDir, 10);

        assertThat(recent).extracting(LearningValidation::verdict)
                .contains(LearningValidation.Verdict.HOLD,
                        LearningValidation.Verdict.ROLLBACK,
                        LearningValidation.Verdict.PASS);
    }
}
