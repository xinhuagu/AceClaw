package dev.aceclaw.daemon;

import dev.aceclaw.core.agent.Turn;
import dev.aceclaw.core.llm.ContentBlock;
import dev.aceclaw.core.llm.Message;
import dev.aceclaw.memory.AutoMemoryStore;
import dev.aceclaw.memory.Insight.ErrorInsight;
import dev.aceclaw.memory.MemoryEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Detects error-correction patterns from structured tool results within a turn.
 *
 * <p>Scans {@link Turn#newMessages()} for {@link ContentBlock.ToolResult} blocks
 * where {@code isError=true}, then searches forward for a subsequent successful
 * call to the same tool. When found, produces an {@link ErrorInsight}.
 *
 * <p>Optionally queries an {@link AutoMemoryStore} for cross-session frequency
 * boosting: each prior {@code ERROR_RECOVERY} entry for the same tool increases
 * confidence by 0.2 (capped at 1.0).
 */
public final class ErrorDetector {

    private static final Logger log = LoggerFactory.getLogger(ErrorDetector.class);

    private static final double BASE_CONFIDENCE = 0.4;
    private static final double CROSS_SESSION_BOOST = 0.2;
    private static final double MAX_CONFIDENCE = 1.0;
    private static final int MAX_ERROR_MESSAGE_CHARS = 500;

    private final AutoMemoryStore memoryStore;

    /**
     * Creates a detector with cross-session boosting via the given memory store.
     */
    public ErrorDetector(AutoMemoryStore memoryStore) {
        this.memoryStore = memoryStore;
    }

    /**
     * Creates a detector without cross-session boosting.
     */
    public ErrorDetector() {
        this(null);
    }

    /**
     * Analyzes a completed turn for error-correction patterns.
     *
     * @param turn the completed turn to analyze
     * @return detected error insights (never null, may be empty)
     */
    public List<ErrorInsight> analyze(Turn turn) {
        if (turn == null || turn.newMessages().isEmpty()) {
            return List.of();
        }

        // Flatten all content blocks with their message order preserved
        var toolUseMap = new LinkedHashMap<String, ToolCall>();
        var errorResults = new ArrayList<ErrorResult>();
        var successByTool = new HashMap<String, List<SuccessResult>>();

        collectToolData(turn.newMessages(), toolUseMap, errorResults, successByTool);

        if (errorResults.isEmpty()) {
            return List.of();
        }

        var insights = new ArrayList<ErrorInsight>();
        var resolved = new HashSet<String>();

        for (var error : errorResults) {
            var toolCall = toolUseMap.get(error.toolUseId);
            if (toolCall == null) continue;

            String toolName = toolCall.name;

            // Find a subsequent success for the same tool
            var successes = successByTool.getOrDefault(toolName, List.of());
            var resolution = successes.stream()
                    .filter(s -> s.order > error.order)
                    .filter(s -> !resolved.contains(s.toolUseId))
                    .findFirst();

            if (resolution.isEmpty()) continue;

            resolved.add(resolution.get().toolUseId);

            String errorMessage = truncate(error.content, MAX_ERROR_MESSAGE_CHARS);
            String resolutionDesc = describeResolution(toolName, toolCall, toolUseMap.get(resolution.get().toolUseId));

            double confidence = computeConfidence(toolName, errorMessage);

            insights.add(new ErrorInsight(toolName, errorMessage, resolutionDesc, confidence));
            log.debug("Detected error-correction: tool={}, confidence={}", toolName, confidence);
        }

        return List.copyOf(insights);
    }

    private void collectToolData(
            List<Message> messages,
            LinkedHashMap<String, ToolCall> toolUseMap,
            List<ErrorResult> errorResults,
            HashMap<String, List<SuccessResult>> successByTool) {

        int order = 0;

        for (var message : messages) {
            List<ContentBlock> blocks;
            if (message instanceof Message.AssistantMessage am) {
                blocks = am.content();
            } else if (message instanceof Message.UserMessage um) {
                blocks = um.content();
            } else {
                continue;
            }

            for (var block : blocks) {
                switch (block) {
                    case ContentBlock.ToolUse tu ->
                            toolUseMap.put(tu.id(), new ToolCall(tu.name(), tu.inputJson(), order++));
                    case ContentBlock.ToolResult tr -> {
                        var call = toolUseMap.get(tr.toolUseId());
                        if (call == null) continue;

                        int resultOrder = order++;
                        if (tr.isError()) {
                            errorResults.add(new ErrorResult(tr.toolUseId(), call.name, tr.content(), resultOrder));
                        } else {
                            successByTool.computeIfAbsent(call.name, _ -> new ArrayList<>())
                                    .add(new SuccessResult(tr.toolUseId(), resultOrder));
                        }
                    }
                    case ContentBlock.Text _, ContentBlock.Thinking _ -> { }
                }
            }
        }
    }

    private double computeConfidence(String toolName, String errorMessage) {
        double confidence = BASE_CONFIDENCE;

        if (memoryStore != null) {
            try {
                var priorEntries = memoryStore.query(
                        MemoryEntry.Category.ERROR_RECOVERY, List.of(toolName), 0);
                int matchCount = (int) priorEntries.stream()
                        .filter(e -> e.content().contains(toolName))
                        .count();
                confidence += matchCount * CROSS_SESSION_BOOST;
            } catch (Exception e) {
                log.warn("Failed to query memory store for cross-session boosting: {}", e.getMessage());
            }
        }

        return Math.min(confidence, MAX_CONFIDENCE);
    }

    private static String describeResolution(String toolName, ToolCall failed, ToolCall succeeded) {
        if (succeeded == null) {
            return "Retried " + toolName + " successfully";
        }
        return "Retried " + toolName + " with different parameters";
    }

    private static String truncate(String text, int maxChars) {
        if (text == null) return "";
        if (text.length() <= maxChars) return text;
        return text.substring(0, maxChars - 3) + "...";
    }

    private record ToolCall(String name, String inputJson, int order) {}
    private record ErrorResult(String toolUseId, String toolName, String content, int order) {}
    private record SuccessResult(String toolUseId, int order) {}
}
