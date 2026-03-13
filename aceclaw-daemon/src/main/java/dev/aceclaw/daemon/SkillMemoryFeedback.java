package dev.aceclaw.daemon;

import dev.aceclaw.core.agent.SkillMetrics;
import dev.aceclaw.core.agent.SkillOutcome;
import dev.aceclaw.memory.AutoMemoryStore;
import dev.aceclaw.memory.MemoryEntry;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Writes skill outcomes back into memory as reinforcement, anti-patterns, and corrections.
 */
public final class SkillMemoryFeedback {

    private static final int MAX_CONTENT_LENGTH = 220;

    private final AutoMemoryStore memoryStore;
    private final LearningExplanationRecorder learningExplanationRecorder;

    public SkillMemoryFeedback(AutoMemoryStore memoryStore) {
        this(memoryStore, null);
    }

    public SkillMemoryFeedback(AutoMemoryStore memoryStore,
                               LearningExplanationRecorder learningExplanationRecorder) {
        this.memoryStore = Objects.requireNonNull(memoryStore, "memoryStore");
        this.learningExplanationRecorder = learningExplanationRecorder;
    }

    /**
     * Persists memory feedback for a single skill outcome.
     */
    public void onOutcome(String skillName, SkillOutcome outcome, SkillMetrics metrics, Path projectPath) {
        if (skillName == null || skillName.isBlank() || outcome == null || projectPath == null) {
            return;
        }

        switch (outcome) {
            case SkillOutcome.Success success -> {
                var content = truncate(buildSuccessContent(skillName, success));
                addIfAbsent(
                        MemoryEntry.Category.SUCCESSFUL_STRATEGY,
                        content,
                        List.of(skillName, "skill-feedback", "successful-strategy"),
                        projectPath,
                        "skill:" + skillName);
                recordMemoryExplanation(projectPath, skillName, "skill-success", MemoryEntry.Category.SUCCESSFUL_STRATEGY, content);
            }
            case SkillOutcome.Failure failure -> {
                var content = truncate("Avoid relying on skill '" + skillName
                        + "' in its current form: " + normalize(failure.reason()) + ".");
                addIfAbsent(
                        MemoryEntry.Category.ANTI_PATTERN,
                        content,
                        List.of(skillName, "skill-feedback", "anti-pattern"),
                        projectPath,
                        "skill:" + skillName);
                recordMemoryExplanation(projectPath, skillName, "skill-failure", MemoryEntry.Category.ANTI_PATTERN, content);
                if (metrics != null && metrics.failureCount() >= 3) {
                    var recovery = truncate("Before reusing skill '" + skillName
                            + "', review recent failures and apply the latest corrected workflow.");
                    addIfAbsent(
                            MemoryEntry.Category.RECOVERY_RECIPE,
                            recovery,
                            List.of(skillName, "skill-feedback", "recovery-recipe"),
                            projectPath,
                            "skill:" + skillName);
                    recordMemoryExplanation(projectPath, skillName, "skill-recovery", MemoryEntry.Category.RECOVERY_RECIPE, recovery);
                }
            }
            case SkillOutcome.UserCorrected corrected -> {
                String normalized = normalize(corrected.correction());
                var correction = truncate("User corrected skill '" + skillName + "': " + normalized);
                addIfAbsent(
                        MemoryEntry.Category.CORRECTION,
                        correction,
                        List.of(skillName, "skill-feedback", "user-correction"),
                        projectPath,
                        "skill:" + skillName);
                recordMemoryExplanation(projectPath, skillName, "skill-correction", MemoryEntry.Category.CORRECTION, correction);
                var preference = truncate("When using skill '" + skillName + "', prefer: " + normalized);
                addIfAbsent(
                        MemoryEntry.Category.PREFERENCE,
                        preference,
                        List.of(skillName, "skill-feedback", "user-preference"),
                        projectPath,
                        "skill:" + skillName);
                recordMemoryExplanation(projectPath, skillName, "skill-preference", MemoryEntry.Category.PREFERENCE, preference);
            }
        }
    }

    /**
     * Persists an anti-pattern when a refined skill is rolled back.
     */
    public void onRollback(String skillName, String reason, Path projectPath) {
        if (skillName == null || skillName.isBlank() || projectPath == null) {
            return;
        }
        addIfAbsent(
                MemoryEntry.Category.ANTI_PATTERN,
                truncate("Avoid reusing the last refinement of skill '" + skillName + "': " + normalize(reason) + "."),
                List.of(skillName, "skill-feedback", "rollback", "anti-pattern"),
                projectPath,
                "skill-refinement:" + skillName);
        recordMemoryExplanation(
                projectPath,
                skillName,
                "skill-rollback",
                MemoryEntry.Category.ANTI_PATTERN,
                truncate("Avoid reusing the last refinement of skill '" + skillName + "': " + normalize(reason) + "."));
    }

    private String buildSuccessContent(String skillName, SkillOutcome.Success success) {
        if (success.turnsUsed() <= 1) {
            return "Skill '" + skillName + "' completed successfully and reinforced its current strategy.";
        }
        return "Skill '" + skillName + "' completed successfully in "
                + success.turnsUsed() + " turns and reinforced its current strategy.";
    }

    private void addIfAbsent(MemoryEntry.Category category,
                             String content,
                             List<String> tags,
                             Path projectPath,
                             String source) {
        memoryStore.addIfAbsent(category, content, tags, source, false, projectPath);
    }

    private void recordMemoryExplanation(Path projectPath,
                                         String skillName,
                                         String trigger,
                                         MemoryEntry.Category category,
                                         String content) {
        if (learningExplanationRecorder == null || projectPath == null) {
            return;
        }
        learningExplanationRecorder.recordMemoryWrite(
                projectPath,
                "",
                trigger,
                category,
                content,
                List.of(skillName, "skill-feedback"),
                List.of(new LearningExplanation.EvidenceRef("skill", skillName, trigger)));
    }

    private static String normalize(String text) {
        if (text == null) {
            return "";
        }
        return text.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    private static String truncate(String text) {
        if (text == null) {
            return "";
        }
        if (text.length() <= MAX_CONTENT_LENGTH) {
            return text;
        }
        return text.substring(0, MAX_CONTENT_LENGTH - 3) + "...";
    }
}
