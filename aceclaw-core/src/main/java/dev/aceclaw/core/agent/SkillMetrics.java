package dev.aceclaw.core.agent;

import java.time.Instant;

/**
 * Immutable snapshot of per-skill effectiveness metrics.
 *
 * @param skillName        skill name
 * @param invocationCount  total skill invocations (success + failure)
 * @param successCount     successful invocations
 * @param failureCount     failed invocations
 * @param correctionCount  user corrections recorded after invocation
 * @param avgTurnsUsed     average turn segments for successful invocations
 * @param lastUsed         latest outcome timestamp
 * @param timeDecayScore   recent weighted success score in [0.0, 1.0]
 */
public record SkillMetrics(
        String skillName,
        int invocationCount,
        int successCount,
        int failureCount,
        int correctionCount,
        double avgTurnsUsed,
        Instant lastUsed,
        double timeDecayScore
) {

    /**
     * Success rate across all recorded outcomes.
     */
    public double successRate() {
        return invocationCount == 0 ? 0.0 : (double) successCount / invocationCount;
    }

    /**
     * Correction rate across all recorded outcomes.
     */
    public double correctionRate() {
        return invocationCount == 0 ? 0.0 : (double) correctionCount / invocationCount;
    }
}
