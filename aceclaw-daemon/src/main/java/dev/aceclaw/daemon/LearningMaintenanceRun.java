package dev.aceclaw.daemon;

import java.time.Instant;
import java.util.Objects;

/**
 * Compact record for one deferred learning-maintenance run.
 */
public record LearningMaintenanceRun(
        Instant timestamp,
        String trigger,
        String workspaceHash,
        String projectPath,
        int deduped,
        int merged,
        int pruned,
        int errorChains,
        int stableWorkflows,
        int convergingStrategies,
        int degradationSignals,
        int trends,
        int candidateObservations,
        int candidateTransitions,
        int candidatePromoted,
        String summary
) {
    public LearningMaintenanceRun {
        Objects.requireNonNull(timestamp, "timestamp");
        trigger = Objects.requireNonNull(trigger, "trigger");
        workspaceHash = workspaceHash != null ? workspaceHash : "";
        projectPath = projectPath != null ? projectPath : "";
        summary = summary != null ? summary : "";
    }
}
