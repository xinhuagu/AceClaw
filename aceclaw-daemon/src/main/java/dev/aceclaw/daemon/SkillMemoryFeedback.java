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

    public SkillMemoryFeedback(AutoMemoryStore memoryStore) {
        this.memoryStore = Objects.requireNonNull(memoryStore, "memoryStore");
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
                if (metrics != null && metrics.failureCount() >= 3) {
                    var recovery = truncate("Before reusing skill '" + skillName
                            + "', review recent failures and apply the latest corrected workflow.");
                    addIfAbsent(
                            MemoryEntry.Category.RECOVERY_RECIPE,
                            recovery,
                            List.of(skillName, "skill-feedback", "recovery-recipe"),
                            projectPath,
                            "skill:" + skillName);
                }
            }
            case SkillOutcome.UserCorrected corrected -> {
                String normalized = normalize(corrected.correction());
                addIfAbsent(
                        MemoryEntry.Category.CORRECTION,
                        truncate("User corrected skill '" + skillName + "': " + normalized),
                        List.of(skillName, "skill-feedback", "user-correction"),
                        projectPath,
                        "skill:" + skillName);
                addIfAbsent(
                        MemoryEntry.Category.PREFERENCE,
                        truncate("When using skill '" + skillName + "', prefer: " + normalized),
                        List.of(skillName, "skill-feedback", "user-preference"),
                        projectPath,
                        "skill:" + skillName);
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
