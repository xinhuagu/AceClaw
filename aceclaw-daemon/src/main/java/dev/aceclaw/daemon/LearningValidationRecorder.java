package dev.aceclaw.daemon;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * Best-effort recorder for learned-behavior validation decisions.
 */
public final class LearningValidationRecorder {

    private final LearningValidationStore store;

    public LearningValidationRecorder(LearningValidationStore store) {
        this.store = Objects.requireNonNull(store, "store");
    }

    public void recordRuntimeSkillObserved(Path projectRoot,
                                           String sessionId,
                                           String skillName,
                                           String sequenceSignature,
                                           List<String> allowedTools) {
        append(projectRoot, new LearningValidation(
                java.time.Instant.now(),
                "runtime-skill",
                skillName,
                sessionId,
                "runtime-generation",
                "runtime-skill-policy",
                LearningValidation.Verdict.OBSERVED_USEFUL,
                "Observed a useful repeated tool sequence and created a session-scoped runtime skill.",
                List.of(
                        new LearningValidation.Reason("REPEATED_SEQUENCE_OBSERVED",
                                "Repeated non-bash tool sequence reached the runtime generation threshold."),
                        new LearningValidation.Reason("SESSION_SCOPED_ONLY",
                                "Runtime skills are session-scoped and not yet validated for durable reuse.")
                ),
                List.of(
                        new LearningValidation.EvidenceRef("sequence", sequenceSignature, ""),
                        new LearningValidation.EvidenceRef("allowed-tools", String.join(",", allowedTools), "")
                )));
    }

    public void recordRuntimeSkillAwaitingDurableValidation(Path projectRoot,
                                                            String sessionId,
                                                            String skillName,
                                                            String draftPath) {
        append(projectRoot, new LearningValidation(
                java.time.Instant.now(),
                "skill-draft",
                draftPath,
                sessionId,
                "session-end",
                "runtime-skill-policy",
                LearningValidation.Verdict.HOLD,
                "Runtime skill persisted as draft and is awaiting durable validation.",
                List.of(new LearningValidation.Reason(
                        "AWAITING_DURABLE_VALIDATION",
                        "Observed useful in-session behavior must still pass draft validation and release gates.")),
                List.of(new LearningValidation.EvidenceRef("runtime-skill", skillName, draftPath))));
    }

    public void recordRuntimeSkillConflict(Path projectRoot,
                                           String sessionId,
                                           String durableSkillName,
                                           String sequenceSignature,
                                           List<String> allowedTools) {
        append(projectRoot, new LearningValidation(
                java.time.Instant.now(),
                "runtime-skill",
                durableSkillName,
                sessionId,
                "runtime-governance",
                "runtime-skill-policy",
                LearningValidation.Verdict.HOLD,
                "Skipped runtime skill generation because durable skill already covers the workflow.",
                List.of(new LearningValidation.Reason(
                        "DURABLE_SKILL_CONFLICT",
                        "A durable skill already matches the repeated workflow, so runtime generation was suppressed.")),
                List.of(
                        new LearningValidation.EvidenceRef("durable-skill", durableSkillName, ""),
                        new LearningValidation.EvidenceRef("sequence", sequenceSignature, ""),
                        new LearningValidation.EvidenceRef("allowed-tools", String.join(",", allowedTools), ""))));
    }

    public void recordRuntimeSkillSuppressed(Path projectRoot,
                                             String sessionId,
                                             String skillName,
                                             String reason,
                                             int successes,
                                             int failures,
                                             int corrections) {
        append(projectRoot, new LearningValidation(
                java.time.Instant.now(),
                "runtime-skill",
                skillName,
                sessionId,
                "runtime-governance",
                "runtime-skill-policy",
                LearningValidation.Verdict.REJECT,
                "Suppressed runtime skill '" + skillName + "': " + reason,
                List.of(new LearningValidation.Reason("RUNTIME_SKILL_SUPPRESSED", reason)),
                List.of(
                        new LearningValidation.EvidenceRef("successes", Integer.toString(successes), ""),
                        new LearningValidation.EvidenceRef("failures", Integer.toString(failures), ""),
                        new LearningValidation.EvidenceRef("corrections", Integer.toString(corrections), ""))));
    }

    public void recordRuntimeSkillExpired(Path projectRoot,
                                          String sessionId,
                                          String skillName,
                                          String reason) {
        append(projectRoot, new LearningValidation(
                java.time.Instant.now(),
                "runtime-skill",
                skillName,
                sessionId,
                "runtime-governance",
                "runtime-skill-policy",
                LearningValidation.Verdict.HOLD,
                "Expired runtime skill '" + skillName + "': " + reason,
                List.of(new LearningValidation.Reason("RUNTIME_SKILL_EXPIRED", reason)),
                List.of(new LearningValidation.EvidenceRef("reason", reason, ""))));
    }

    public void recordRuntimeSkillNotPromoted(Path projectRoot,
                                              String sessionId,
                                              String skillName,
                                              String reason,
                                              int successes,
                                              int failures,
                                              int corrections) {
        append(projectRoot, new LearningValidation(
                java.time.Instant.now(),
                "runtime-skill",
                skillName,
                sessionId,
                "session-end",
                "runtime-skill-policy",
                LearningValidation.Verdict.HOLD,
                "Runtime skill '" + skillName + "' did not meet durable promotion criteria.",
                List.of(new LearningValidation.Reason("INSUFFICIENT_RUNTIME_EVIDENCE", reason)),
                List.of(
                        new LearningValidation.EvidenceRef("successes", Integer.toString(successes), ""),
                        new LearningValidation.EvidenceRef("failures", Integer.toString(failures), ""),
                        new LearningValidation.EvidenceRef("corrections", Integer.toString(corrections), ""))));
    }

    public void recordDraftValidation(Path projectRoot,
                                      String trigger,
                                      ValidationGateEngine.DraftDecision decision) {
        append(projectRoot, new LearningValidation(
                decision.evaluatedAt(),
                "skill-draft",
                decision.draftPath(),
                "",
                trigger,
                "draft-validation-gate",
                mapDraftVerdict(decision.verdict()),
                "Draft validation " + decision.verdict().name().toLowerCase() + " for " + decision.draftPath(),
                decision.reasons().stream()
                        .map(reason -> new LearningValidation.Reason(reason.code(), reason.message()))
                        .toList(),
                List.of(new LearningValidation.EvidenceRef("draft-path", decision.draftPath(), trigger))));
    }

    public void recordReleaseValidation(Path projectRoot,
                                        String trigger,
                                        AutoReleaseController.ReleaseEvent event) {
        append(projectRoot, new LearningValidation(
                event.timestamp(),
                "skill",
                event.skillName(),
                "",
                trigger,
                "auto-release-controller",
                mapReleaseVerdict(event),
                "Release stage moved from " + stageName(event.fromStage()) + " to "
                        + event.toStage().name().toLowerCase() + " for " + event.skillName(),
                List.of(new LearningValidation.Reason(event.reasonCode(), event.reason())),
                List.of(new LearningValidation.EvidenceRef(
                        "stage-transition",
                        stageName(event.fromStage()) + "->" + event.toStage().name().toLowerCase(),
                        event.trigger()))));
    }

    public void recordRefinementValidation(Path projectRoot,
                                           String skillName,
                                           SkillRefinementEngine.RefinementAction action,
                                           String reason) {
        if (action == null || action == SkillRefinementEngine.RefinementAction.NONE) {
            return;
        }
        append(projectRoot, new LearningValidation(
                java.time.Instant.now(),
                "skill",
                skillName,
                "",
                "skill-refinement",
                "refinement-policy",
                mapRefinementVerdict(action),
                action.name().toLowerCase() + " for " + skillName,
                List.of(new LearningValidation.Reason(action.name(), reason)),
                List.of(new LearningValidation.EvidenceRef("skill", skillName, reason))));
    }

    public void recordHumanReview(Path projectRoot,
                                  String targetType,
                                  String targetId,
                                  LearningSignalReview.Action action,
                                  String note,
                                  String reviewer,
                                  String sessionId) {
        append(projectRoot, new LearningValidation(
                java.time.Instant.now(),
                targetType,
                targetId,
                sessionId,
                "human-review",
                "human-review-policy",
                mapHumanReviewVerdict(action),
                "Human review marked " + targetType + " '" + targetId + "' as "
                        + action.name().toLowerCase().replace('_', '-') + ".",
                List.of(new LearningValidation.Reason(action.name(), note)),
                List.of(new LearningValidation.EvidenceRef("reviewer", reviewer, note))));
    }

    private static LearningValidation.Verdict mapDraftVerdict(ValidationGateEngine.Verdict verdict) {
        return switch (verdict) {
            case PASS -> LearningValidation.Verdict.PASS;
            case HOLD -> LearningValidation.Verdict.HOLD;
            case BLOCK -> LearningValidation.Verdict.REJECT;
        };
    }

    private static LearningValidation.Verdict mapReleaseVerdict(AutoReleaseController.ReleaseEvent event) {
        if (event.toStage() == AutoReleaseController.Stage.ACTIVE) {
            return LearningValidation.Verdict.PASS;
        }
        if (event.toStage() == AutoReleaseController.Stage.CANARY) {
            return LearningValidation.Verdict.PROVISIONAL;
        }
        if (event.toStage() == AutoReleaseController.Stage.SHADOW
                && event.fromStage() != null
                && event.fromStage() != AutoReleaseController.Stage.SHADOW) {
            return LearningValidation.Verdict.ROLLBACK;
        }
        return LearningValidation.Verdict.HOLD;
    }

    private static LearningValidation.Verdict mapRefinementVerdict(SkillRefinementEngine.RefinementAction action) {
        return switch (action) {
            case REFINED -> LearningValidation.Verdict.PROVISIONAL;
            case STABILIZED -> LearningValidation.Verdict.PASS;
            case DEPRECATED -> LearningValidation.Verdict.REJECT;
            case ROLLED_BACK -> LearningValidation.Verdict.ROLLBACK;
            case NONE -> LearningValidation.Verdict.HOLD;
        };
    }

    private static String stageName(AutoReleaseController.Stage stage) {
        return stage == null ? "none" : stage.name().toLowerCase();
    }

    private static LearningValidation.Verdict mapHumanReviewVerdict(LearningSignalReview.Action action) {
        return switch (action) {
            case SUPPRESS, LOW_VALUE, INCORRECT -> LearningValidation.Verdict.REJECT;
            case PIN, USEFUL -> LearningValidation.Verdict.PASS;
            case UNSUPPRESS, UNPIN -> LearningValidation.Verdict.HOLD;
        };
    }

    private void append(Path projectRoot, LearningValidation validation) {
        if (projectRoot == null || validation == null) {
            return;
        }
        try {
            store.append(projectRoot, validation);
        } catch (Exception ignored) {
            // Validation logging must never break the learning pipeline.
        }
    }
}
