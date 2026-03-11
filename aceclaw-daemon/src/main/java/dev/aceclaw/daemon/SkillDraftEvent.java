package dev.aceclaw.daemon;

import java.time.Instant;
import java.util.List;

/**
 * Event emitted by the daemon skill-draft pipeline for REPL notifications.
 */
public record SkillDraftEvent(
        Instant timestamp,
        String type,
        String trigger,
        String skillName,
        String draftPath,
        String candidateId,
        String verdict,
        String releaseStage,
        boolean paused,
        List<String> reasons
) {
    public SkillDraftEvent {
        timestamp = timestamp != null ? timestamp : Instant.EPOCH;
        type = type != null ? type : "";
        trigger = trigger != null ? trigger : "manual";
        skillName = skillName != null ? skillName : "";
        draftPath = draftPath != null ? draftPath : "";
        candidateId = candidateId != null ? candidateId : "";
        verdict = verdict != null ? verdict : "";
        releaseStage = releaseStage != null ? releaseStage : "";
        reasons = reasons != null ? List.copyOf(reasons) : List.of();
    }
}
