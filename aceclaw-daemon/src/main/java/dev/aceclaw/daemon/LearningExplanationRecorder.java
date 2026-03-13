package dev.aceclaw.daemon;

import dev.aceclaw.memory.CandidateTransition;
import dev.aceclaw.memory.LearningCandidate;
import dev.aceclaw.memory.MemoryEntry;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Best-effort recorder that turns adaptive actions into explanation records.
 */
public final class LearningExplanationRecorder {

    private final LearningExplanationStore store;

    public LearningExplanationRecorder(LearningExplanationStore store) {
        this.store = Objects.requireNonNull(store, "store");
    }

    public void recordMemoryWrite(Path projectRoot,
                                  String sessionId,
                                  String trigger,
                                  MemoryEntry.Category category,
                                  String content,
                                  List<String> tags,
                                  List<LearningExplanation.EvidenceRef> evidence) {
        append(projectRoot, new LearningExplanation(
                Instant.now(),
                "memory_write",
                "memory",
                category.name().toLowerCase() + ":" + Integer.toHexString(Objects.hash(category, content)),
                sessionId,
                trigger,
                content,
                tags,
                evidence));
    }

    public void recordCandidateObservation(Path projectRoot,
                                           String sessionId,
                                           String trigger,
                                           LearningCandidate candidate,
                                           String summary,
                                           List<LearningExplanation.EvidenceRef> evidence) {
        append(projectRoot, new LearningExplanation(
                Instant.now(),
                "candidate_observed",
                "candidate",
                candidate.id(),
                sessionId,
                trigger,
                summary,
                candidate.tags(),
                evidence));
    }

    public void recordCandidateTransition(Path projectRoot,
                                          String sessionId,
                                          String trigger,
                                          CandidateTransition transition) {
        append(projectRoot, new LearningExplanation(
                transition.timestamp(),
                "candidate_transition",
                "candidate",
                transition.candidateId(),
                sessionId,
                trigger,
                transition.fromState().name().toLowerCase() + " -> "
                        + transition.toState().name().toLowerCase() + ": " + transition.reason(),
                List.of("candidate-transition", transition.toState().name().toLowerCase()),
                List.of(new LearningExplanation.EvidenceRef("reason-code", transition.reasonCode(), transition.triggeredBy()))));
    }

    public void recordSkillDraft(Path projectRoot,
                                 String trigger,
                                 String skillName,
                                 String draftPath,
                                 String sourceCandidateId) {
        append(projectRoot, new LearningExplanation(
                Instant.now(),
                "skill_draft_created",
                "skill-draft",
                draftPath,
                "",
                trigger,
                "Created skill draft '" + skillName + "'.",
                List.of("skill-draft", skillName),
                List.of(new LearningExplanation.EvidenceRef("candidate", sourceCandidateId, draftPath))));
    }

    public void recordRuntimeSkill(Path projectRoot,
                                   String sessionId,
                                   String skillName,
                                   String sequenceSignature,
                                   List<String> allowedTools) {
        append(projectRoot, new LearningExplanation(
                Instant.now(),
                "runtime_skill_created",
                "runtime-skill",
                skillName,
                sessionId,
                "runtime-generation",
                "Created runtime skill '" + skillName + "' for repeated tool sequence.",
                List.of("runtime-skill", skillName),
                List.of(
                        new LearningExplanation.EvidenceRef("sequence", sequenceSignature, ""),
                        new LearningExplanation.EvidenceRef("allowed-tools", String.join(",", allowedTools), ""))));
    }

    public void recordRuntimeSkillConflict(Path projectRoot,
                                           String sessionId,
                                           String durableSkillName,
                                           String sequenceSignature,
                                           List<String> allowedTools) {
        append(projectRoot, new LearningExplanation(
                Instant.now(),
                "runtime_skill_conflict",
                "runtime-skill",
                durableSkillName,
                sessionId,
                "runtime-governance",
                "Skipped runtime skill generation because durable skill '" + durableSkillName + "' already covers the workflow.",
                List.of("runtime-skill", "conflict", durableSkillName),
                List.of(
                        new LearningExplanation.EvidenceRef("sequence", sequenceSignature, ""),
                        new LearningExplanation.EvidenceRef("allowed-tools", String.join(",", allowedTools), ""))));
    }

    public void recordRuntimeSkillDraftPersisted(Path projectRoot,
                                                 String sessionId,
                                                 String skillName,
                                                 String draftPath) {
        append(projectRoot, new LearningExplanation(
                Instant.now(),
                "runtime_skill_persisted",
                "skill-draft",
                draftPath,
                sessionId,
                "session-end",
                "Persisted runtime skill '" + skillName + "' as draft.",
                List.of("runtime-skill", "skill-draft", skillName),
                List.of(new LearningExplanation.EvidenceRef("draft-path", draftPath, skillName))));
    }

    public void recordRuntimeSkillSuppressed(Path projectRoot,
                                             String sessionId,
                                             String skillName,
                                             String reason,
                                             int successes,
                                             int failures,
                                             int corrections) {
        append(projectRoot, new LearningExplanation(
                Instant.now(),
                "runtime_skill_suppressed",
                "runtime-skill",
                skillName,
                sessionId,
                "runtime-governance",
                "Suppressed runtime skill '" + skillName + "': " + reason,
                List.of("runtime-skill", "suppressed", skillName),
                List.of(
                        new LearningExplanation.EvidenceRef("successes", Integer.toString(successes), ""),
                        new LearningExplanation.EvidenceRef("failures", Integer.toString(failures), ""),
                        new LearningExplanation.EvidenceRef("corrections", Integer.toString(corrections), ""))));
    }

    public void recordRuntimeSkillExpired(Path projectRoot,
                                          String sessionId,
                                          String skillName,
                                          String reason) {
        append(projectRoot, new LearningExplanation(
                Instant.now(),
                "runtime_skill_expired",
                "runtime-skill",
                skillName,
                sessionId,
                "runtime-governance",
                "Expired runtime skill '" + skillName + "': " + reason,
                List.of("runtime-skill", "expired", skillName),
                List.of(new LearningExplanation.EvidenceRef("reason", reason, ""))));
    }

    public void recordRuntimeSkillNotPromoted(Path projectRoot,
                                              String sessionId,
                                              String skillName,
                                              String reason,
                                              int successes,
                                              int failures,
                                              int corrections) {
        append(projectRoot, new LearningExplanation(
                Instant.now(),
                "runtime_skill_not_promoted",
                "runtime-skill",
                skillName,
                sessionId,
                "runtime-governance",
                "Did not persist runtime skill '" + skillName + "' as draft: " + reason,
                List.of("runtime-skill", "not-promoted", skillName),
                List.of(
                        new LearningExplanation.EvidenceRef("successes", Integer.toString(successes), ""),
                        new LearningExplanation.EvidenceRef("failures", Integer.toString(failures), ""),
                        new LearningExplanation.EvidenceRef("corrections", Integer.toString(corrections), ""))));
    }

    public void recordRefinement(Path projectRoot,
                                 String skillName,
                                 String action,
                                 String reason) {
        append(projectRoot, new LearningExplanation(
                Instant.now(),
                "skill_refinement",
                "skill",
                skillName,
                "",
                "skill-refinement",
                action + " for '" + skillName + "': " + reason,
                List.of("skill-refinement", action.toLowerCase(), skillName),
                List.of(new LearningExplanation.EvidenceRef("reason", action, reason))));
    }

    private void append(Path projectRoot, LearningExplanation explanation) {
        if (projectRoot == null || explanation == null) {
            return;
        }
        try {
            store.append(projectRoot, explanation);
        } catch (Exception ignored) {
            // Explainability must never break the learning pipeline.
        }
    }
}
