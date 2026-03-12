package dev.aceclaw.memory;

import dev.aceclaw.core.agent.ToolMetrics;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable session-close snapshot used to incrementally build the historical learning index.
 */
public record HistoricalSessionSnapshot(
        String sessionId,
        String workspaceHash,
        Instant closedAt,
        List<String> executedCommands,
        List<String> errorsEncountered,
        List<String> extractedFilePaths,
        Map<String, ToolMetrics> toolMetrics,
        boolean backtrackingDetected,
        String endToEndStrategy
) {
    public HistoricalSessionSnapshot {
        sessionId = Objects.requireNonNull(sessionId, "sessionId");
        workspaceHash = Objects.requireNonNull(workspaceHash, "workspaceHash");
        if (workspaceHash.isBlank()) {
            throw new IllegalArgumentException("workspaceHash must not be blank");
        }
        executedCommands = executedCommands != null ? List.copyOf(executedCommands) : List.of();
        errorsEncountered = errorsEncountered != null ? List.copyOf(errorsEncountered) : List.of();
        extractedFilePaths = extractedFilePaths != null ? List.copyOf(extractedFilePaths) : List.of();
        toolMetrics = toolMetrics != null ? Map.copyOf(toolMetrics) : Map.of();
        endToEndStrategy = endToEndStrategy != null ? endToEndStrategy : "";
        closedAt = closedAt != null ? closedAt : Instant.now();
    }
}
