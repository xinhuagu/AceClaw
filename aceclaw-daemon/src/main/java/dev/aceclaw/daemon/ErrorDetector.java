package dev.aceclaw.daemon;

import dev.aceclaw.core.agent.Turn;
import dev.aceclaw.core.llm.ContentBlock;
import dev.aceclaw.core.llm.Message;
import dev.aceclaw.memory.AutoMemoryStore;
import dev.aceclaw.memory.ErrorClass;
import dev.aceclaw.memory.Insight;
import dev.aceclaw.memory.Insight.ErrorInsight;
import dev.aceclaw.memory.Insight.RecoveryRecipe;
import dev.aceclaw.memory.Insight.RecoveryStep;
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
     * Analyzes a completed turn for error-correction patterns and multi-step recoveries.
     *
     * @param turn the completed turn to analyze
     * @return detected insights (ErrorInsight and/or RecoveryRecipe; never null, may be empty)
     */
    public List<Insight> analyze(Turn turn) {
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

        var insights = new ArrayList<Insight>();
        var resolved = new HashSet<String>();

        // Phase 1: simple error-correction (same tool error → same tool success)
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
            if (hasIntermediateToolBetween(toolUseMap, toolName, error.order, resolution.get().order)) {
                // Leave multi-step cases to Phase 2 to avoid duplicate insights for the same error.
                continue;
            }

            resolved.add(resolution.get().toolUseId);
            resolved.add(error.toolUseId);

            String errorMessage = truncate(error.content, MAX_ERROR_MESSAGE_CHARS);
            String resolutionDesc = describeResolution(toolName, toolCall, toolUseMap.get(resolution.get().toolUseId));

            double confidence = computeConfidence(toolName, errorMessage);

            insights.add(ErrorInsight.of(toolName, errorMessage, resolutionDesc, confidence));
            log.debug("Detected error-correction: tool={}, confidence={}", toolName, confidence);
        }

        // Phase 2: multi-step recovery (error → intermediate tools → success)
        insights.addAll(detectMultiStepRecoveries(toolUseMap, errorResults, successByTool, resolved));

        return List.copyOf(insights);
    }

    /**
     * Detects multi-step recovery patterns where an error on tool A is resolved
     * by using intermediate tools B, C, etc. before retrying tool A successfully.
     * Multi-step paths are emitted as RecoveryRecipe only (to avoid duplicate
     * ErrorInsight + RecoveryRecipe for the same underlying error).
     */
    private List<RecoveryRecipe> detectMultiStepRecoveries(
            LinkedHashMap<String, ToolCall> toolUseMap,
            List<ErrorResult> errorResults,
            HashMap<String, List<SuccessResult>> successByTool,
            Set<String> resolved) {

        var recipes = new ArrayList<RecoveryRecipe>();

        for (var error : errorResults) {
            var toolCall = toolUseMap.get(error.toolUseId);
            if (toolCall == null) continue;
            String toolName = toolCall.name;

            // Find a subsequent success for the same tool
            var successes = successByTool.getOrDefault(toolName, List.of());
            var resolution = successes.stream()
                    .filter(s -> s.order > error.order)
                    .findFirst();

            if (resolution.isEmpty()) continue;

            int errorOrder = error.order;
            int successOrder = resolution.get().order;

            // Collect intermediate tool calls between error and success
            var intermediateSteps = new ArrayList<RecoveryStep>();
            for (var entry : toolUseMap.entrySet()) {
                var call = entry.getValue();
                if (call.order > errorOrder && call.order < successOrder && !call.name.equals(toolName)) {
                    intermediateSteps.add(new RecoveryStep(
                            "Use " + call.name, call.name, null));
                }
            }

            // Only produce a recipe if there are intermediate steps (otherwise it's a simple retry)
            if (!intermediateSteps.isEmpty()) {
                var allSteps = new ArrayList<RecoveryStep>();
                allSteps.add(new RecoveryStep("Detect error: " + truncate(error.content, 100), null, null));
                allSteps.addAll(intermediateSteps);
                allSteps.add(new RecoveryStep("Retry " + toolName, toolName, null));

                String triggerPattern = ErrorClass.classify(error.content).name() + ": " + truncate(error.content, 200);
                double confidence = computeConfidence(toolName, error.content);

                recipes.add(new RecoveryRecipe(triggerPattern, allSteps, toolName, confidence));
                log.debug("Detected multi-step recovery: tool={}, steps={}", toolName, allSteps.size());
            }
        }

        return recipes;
    }

    private static boolean hasIntermediateToolBetween(
            LinkedHashMap<String, ToolCall> toolUseMap,
            String toolName,
            int errorOrder,
            int successOrder) {
        Objects.requireNonNull(toolName, "toolName");
        for (var call : toolUseMap.values()) {
            if (call.order > errorOrder && call.order < successOrder && !call.name.equals(toolName)) {
                return true;
            }
        }
        return false;
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
