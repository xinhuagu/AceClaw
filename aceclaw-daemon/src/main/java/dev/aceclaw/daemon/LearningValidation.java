package dev.aceclaw.daemon;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Compact validation payload for learned behaviors and adaptive actions.
 */
public record LearningValidation(
        Instant timestamp,
        String targetType,
        String targetId,
        String sessionId,
        String trigger,
        String policy,
        Verdict verdict,
        String summary,
        List<Reason> reasons,
        List<EvidenceRef> evidence
) {
    public LearningValidation {
        Objects.requireNonNull(timestamp, "timestamp");
        Objects.requireNonNull(targetType, "targetType");
        targetId = targetId != null ? targetId : "";
        sessionId = sessionId != null ? sessionId : "";
        trigger = trigger != null ? trigger : "";
        policy = Objects.requireNonNull(policy, "policy");
        verdict = Objects.requireNonNull(verdict, "verdict");
        summary = summary != null ? summary : "";
        reasons = reasons != null ? List.copyOf(reasons) : List.of();
        evidence = evidence != null ? List.copyOf(evidence) : List.of();
    }

    public enum Verdict {
        OBSERVED_USEFUL,
        PROVISIONAL,
        PASS,
        HOLD,
        REJECT,
        ROLLBACK
    }

    public record Reason(
            String code,
            String message
    ) {
        public Reason {
            code = Objects.requireNonNull(code, "code");
            message = message != null ? message : "";
        }
    }

    public record EvidenceRef(
            String type,
            String ref,
            String detail
    ) {
        public EvidenceRef {
            type = Objects.requireNonNull(type, "type");
            ref = ref != null ? ref : "";
            detail = detail != null ? detail : "";
        }
    }
}
