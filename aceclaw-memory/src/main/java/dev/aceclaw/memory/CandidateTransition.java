package dev.aceclaw.memory;

import java.time.Instant;
import java.util.Objects;

/**
 * Immutable audit record for a candidate state transition.
 */
public record CandidateTransition(
        String candidateId,
        CandidateState fromState,
        CandidateState toState,
        String reason,
        String reasonCode,
        String triggeredBy,
        int windowAttempts,
        double windowFailureRate,
        int correctionConflictCount,
        Instant cooldownUntil,
        Instant timestamp
) {
    public CandidateTransition(
            String candidateId,
            CandidateState fromState,
            CandidateState toState,
            String reason,
            Instant timestamp
    ) {
        this(candidateId, fromState, toState, reason, "GENERIC", "manual",
                0, 0.0, 0, null, timestamp);
    }

    public CandidateTransition {
        Objects.requireNonNull(candidateId, "candidateId");
        Objects.requireNonNull(fromState, "fromState");
        Objects.requireNonNull(toState, "toState");
        Objects.requireNonNull(reason, "reason");
        reasonCode = reasonCode != null ? reasonCode : "GENERIC";
        triggeredBy = triggeredBy != null ? triggeredBy : "manual";
        Objects.requireNonNull(timestamp, "timestamp");
    }
}
