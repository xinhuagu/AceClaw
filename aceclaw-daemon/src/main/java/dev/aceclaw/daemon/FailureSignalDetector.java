package dev.aceclaw.daemon;

import dev.aceclaw.core.agent.Turn;
import dev.aceclaw.core.llm.ContentBlock;
import dev.aceclaw.core.llm.Message;
import dev.aceclaw.memory.FailureType;
import dev.aceclaw.memory.Insight;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;

/**
 * Detects normalized runtime failure signals from structured tool results in a turn.
 */
public final class FailureSignalDetector {

    private static final Logger log = LoggerFactory.getLogger(FailureSignalDetector.class);

    public List<Insight.FailureInsight> analyze(Turn turn) {
        if (turn == null || turn.newMessages().isEmpty()) {
            return List.of();
        }

        var toolUseById = new LinkedHashMap<String, String>();
        var insights = new ArrayList<Insight.FailureInsight>();

        for (var message : turn.newMessages()) {
            var blocks = switch (message) {
                case Message.AssistantMessage am -> am.content();
                case Message.UserMessage um -> um.content();
            };

            for (var block : blocks) {
                switch (block) {
                    case ContentBlock.ToolUse tu -> toolUseById.put(tu.id(), tu.name());
                    case ContentBlock.ToolResult tr -> {
                        if (!tr.isError()) {
                            continue;
                        }
                        String toolName = toolUseById.getOrDefault(tr.toolUseId(), "unknown");
                        var insight = mapToInsight(toolName, tr.content());
                        if (insight != null) {
                            insights.add(insight);
                        }
                    }
                    case ContentBlock.Text _, ContentBlock.Thinking _ -> {
                    }
                }
            }
        }

        return List.copyOf(insights);
    }

    private Insight.FailureInsight mapToInsight(String toolName, String content) {
        String reason = sanitizeReason(content);
        if (reason.isBlank()) {
            return null;
        }

        String normalized = reason.toLowerCase(Locale.ROOT);
        FailureType type = classify(toolName, normalized);
        if (type == null) {
            return null;
        }

        String source = sourceFor(toolName, type);
        boolean retryable = retryable(type);
        double confidence = confidence(type);

        var insight = new Insight.FailureInsight(
                type,
                source,
                toolName,
                reason,
                retryable,
                Instant.now(),
                confidence);

        log.debug("Detected failure signal: type={}, source={}, toolOrAgent={}, retryable={}",
                type.wireName(), source, toolName, retryable);
        return insight;
    }

    private static FailureType classify(String toolName, String normalizedReason) {
        if (normalizedReason.contains("permission pending timeout")) {
            return FailureType.PERMISSION_PENDING_TIMEOUT;
        }
        if (normalizedReason.contains("permission denied")) {
            return FailureType.PERMISSION_DENIED;
        }
        if (normalizedReason.contains("timed out") || normalizedReason.contains("timeout")) {
            return FailureType.TIMEOUT;
        }
        if (normalizedReason.contains("cancelled") || normalizedReason.contains("canceled")) {
            return FailureType.CANCELLED;
        }
        if (("task_output".equals(toolName) && normalizedReason.contains("status: failed"))
                || normalizedReason.startsWith("sub-agent error:")
                || normalizedReason.contains("broken pipe")) {
            return FailureType.BROKEN;
        }
        return null;
    }

    private static String sourceFor(String toolName, FailureType type) {
        if (type == FailureType.PERMISSION_DENIED || type == FailureType.PERMISSION_PENDING_TIMEOUT) {
            return "permission-gate";
        }
        if ("task_output".equals(toolName)) {
            return "background-task";
        }
        if ("task".equals(toolName)) {
            return "sub-agent";
        }
        return "tool";
    }

    private static boolean retryable(FailureType type) {
        return switch (type) {
            case PERMISSION_DENIED -> false;
            case PERMISSION_PENDING_TIMEOUT, TIMEOUT, BROKEN, CANCELLED -> true;
        };
    }

    private static double confidence(FailureType type) {
        return switch (type) {
            case PERMISSION_DENIED, PERMISSION_PENDING_TIMEOUT -> 0.95;
            case TIMEOUT, BROKEN, CANCELLED -> 0.85;
        };
    }

    private static String sanitizeReason(String content) {
        if (content == null) {
            return "";
        }
        String oneLine = content.replace('\n', ' ').replace('\r', ' ').trim();
        if (oneLine.length() <= 400) {
            return oneLine;
        }
        return oneLine.substring(0, 397) + "...";
    }
}
