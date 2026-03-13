package dev.aceclaw.daemon;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Compact explanation payload for learned artifacts and adaptive actions.
 */
public record LearningExplanation(
        Instant timestamp,
        String actionType,
        String targetType,
        String targetId,
        String sessionId,
        String trigger,
        String summary,
        List<String> tags,
        List<EvidenceRef> evidence
) {
    public LearningExplanation {
        Objects.requireNonNull(timestamp, "timestamp");
        Objects.requireNonNull(actionType, "actionType");
        Objects.requireNonNull(targetType, "targetType");
        targetId = targetId != null ? targetId : "";
        sessionId = sessionId != null ? sessionId : "";
        trigger = trigger != null ? trigger : "";
        summary = summary != null ? summary : "";
        tags = tags != null ? List.copyOf(tags) : List.of();
        evidence = evidence != null ? List.copyOf(evidence) : List.of();
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
