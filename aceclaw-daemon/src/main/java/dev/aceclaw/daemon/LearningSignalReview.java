package dev.aceclaw.daemon;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Human review decision over a learned signal or adaptive action.
 */
public record LearningSignalReview(
        Instant timestamp,
        String targetType,
        String targetId,
        Action action,
        String summary,
        String note,
        String reviewer,
        String sessionId,
        List<String> tags
) {
    public LearningSignalReview {
        timestamp = Objects.requireNonNull(timestamp, "timestamp");
        targetType = Objects.requireNonNull(targetType, "targetType");
        targetId = targetId != null ? targetId : "";
        action = Objects.requireNonNull(action, "action");
        summary = summary != null ? summary : "";
        note = note != null ? note : "";
        reviewer = reviewer != null ? reviewer : "";
        sessionId = sessionId != null ? sessionId : "";
        tags = tags != null ? List.copyOf(tags) : List.of();
        if (targetType.isBlank()) {
            throw new IllegalArgumentException("targetType must not be blank");
        }
        if (targetId.isBlank()) {
            throw new IllegalArgumentException("targetId must not be blank");
        }
    }

    public String targetKey() {
        return targetType + ":" + targetId;
    }

    public enum Action {
        SUPPRESS,
        PIN,
        LOW_VALUE,
        INCORRECT,
        USEFUL,
        UNSUPPRESS,
        UNPIN
    }
}
