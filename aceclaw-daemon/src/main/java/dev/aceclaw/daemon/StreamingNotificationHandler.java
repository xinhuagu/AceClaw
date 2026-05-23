package dev.aceclaw.daemon;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.aceclaw.core.llm.ContentBlock;
import dev.aceclaw.core.llm.StreamEvent;
import dev.aceclaw.core.llm.StreamEventHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Forwards stream events from the agent loop to the client as JSON-RPC
 * notifications.
 *
 * <p>Extracted from {@code StreamingAgentHandler} (originally a static inner
 * class). Package-private — only the handler and its sibling permission-aware
 * tool reach for this directly; nothing outside the package needs the type.
 *
 * <p>Holds a {@code currentStepId} reference that the plan executor's event
 * listener sets/clears at step boundaries so each {@code stream.tool_use}
 * notification carries an explicit parent reference. {@link PermissionAwareTool}
 * also reads it to stamp the per-call {@link dev.aceclaw.security.Provenance}
 * with the step that triggered each capability check (#480 PR 3 / #485 PR 3).
 */
final class StreamingNotificationHandler implements StreamEventHandler {

    private static final Logger log = LoggerFactory.getLogger(StreamingNotificationHandler.class);

    private final StreamContext context;
    private final ObjectMapper objectMapper;

    private final AtomicReference<String> currentStepId = new AtomicReference<>();

    StreamingNotificationHandler(StreamContext context, ObjectMapper objectMapper) {
        this.context = context;
        this.objectMapper = objectMapper;
    }

    /**
     * Sets (or clears with {@code null}) the dashboard-form step id used
     * to tag {@code stream.tool_use} notifications. Caller is responsible
     * for setting on step start and clearing on plan termination.
     */
    void setCurrentStepId(String stepId) {
        currentStepId.set(stepId);
    }

    /**
     * Returns the currently active plan step id, or {@code null} if no
     * plan is in progress. Exposed so {@link PermissionAwareTool} can
     * stamp the per-call {@link dev.aceclaw.security.Provenance} with
     * the step that triggered each capability check.
     */
    String getCurrentStepId() {
        return currentStepId.get();
    }

    @Override
    public void onThinkingDelta(StreamEvent.ThinkingDelta event) {
        try {
            var params = objectMapper.createObjectNode();
            params.put("delta", event.text());
            String stepId = currentStepId.get();
            if (stepId != null) {
                params.put("parentStepId", stepId);
            }
            context.sendNotification("stream.thinking", params);
        } catch (IOException e) {
            log.warn("Failed to send thinking delta notification: {}", e.getMessage());
        }
    }

    @Override
    public void onTextDelta(StreamEvent.TextDelta event) {
        try {
            var params = objectMapper.createObjectNode();
            params.put("delta", event.text());
            String stepId = currentStepId.get();
            if (stepId != null) {
                params.put("parentStepId", stepId);
            }
            context.sendNotification("stream.text", params);
        } catch (IOException e) {
            log.warn("Failed to send text delta notification: {}", e.getMessage());
        }
    }

    @Override
    public void onContentBlockStart(StreamEvent.ContentBlockStart event) {
        if (event.block() instanceof ContentBlock.ToolUse toolUse) {
            try {
                var params = objectMapper.createObjectNode();
                params.put("name", toolUse.name());
                params.put("id", toolUse.id());
                String summary = summarizeToolInput(toolUse.name(), toolUse.inputJson(), objectMapper);
                if (!summary.isBlank()) {
                    params.put("summary", summary);
                }
                String stepId = currentStepId.get();
                if (stepId != null) {
                    params.put("parentStepId", stepId);
                }
                context.sendNotification("stream.tool_use", params);
            } catch (IOException e) {
                log.warn("Failed to send tool use notification: {}", e.getMessage());
            }
        }
    }

    @Override
    public void onToolCompleted(String toolUseId, String toolName,
                                long durationMs, boolean isError, String error) {
        try {
            var params = objectMapper.createObjectNode();
            params.put("id", toolUseId);
            params.put("name", toolName);
            params.put("durationMs", durationMs);
            params.put("isError", isError);
            if (error != null && !error.isBlank()) {
                params.put("error", truncate(error, 160));
            }
            context.sendNotification("stream.tool_completed", params);
        } catch (IOException e) {
            log.warn("Failed to send tool completed notification: {}", e.getMessage());
        }
    }

    @Override
    public void onHeartbeat(StreamEvent.Heartbeat event) {
        try {
            var params = objectMapper.createObjectNode();
            params.put("phase", event.phase());
            context.sendNotification("stream.heartbeat", params);
        } catch (IOException e) {
            log.debug("Failed to send heartbeat notification: {}", e.getMessage());
        }
    }

    @Override
    public void onError(StreamEvent.StreamError event) {
        try {
            var params = objectMapper.createObjectNode();
            params.put("error", event.error().getMessage());
            context.sendNotification("stream.error", params);
        } catch (IOException e) {
            log.warn("Failed to send error notification: {}", e.getMessage());
        }
    }

    @Override
    public void onCompaction(int originalTokens, int compactedTokens, String phase) {
        try {
            var params = objectMapper.createObjectNode();
            params.put("originalTokens", originalTokens);
            params.put("compactedTokens", compactedTokens);
            params.put("phase", phase);
            context.sendNotification("stream.compaction", params);
        } catch (IOException e) {
            log.warn("Failed to send compaction notification: {}", e.getMessage());
        }
    }

    @Override
    public void onSubAgentStart(String agentId, String prompt) {
        try {
            var params = objectMapper.createObjectNode();
            params.put("agentType", agentId);
            params.put("prompt", prompt);
            context.sendNotification("stream.subagent.start", params);
        } catch (IOException e) {
            log.warn("Failed to send subagent start notification: {}", e.getMessage());
        }
    }

    @Override
    public void onSubAgentEnd(String agentId) {
        try {
            var params = objectMapper.createObjectNode();
            params.put("agentType", agentId);
            context.sendNotification("stream.subagent.end", params);
        } catch (IOException e) {
            log.warn("Failed to send subagent end notification: {}", e.getMessage());
        }
    }

    @Override
    public void onUsageUpdate(long lastInputTokens, long totalInputTokens, long totalOutputTokens) {
        try {
            var params = objectMapper.createObjectNode();
            params.put("inputTokens", lastInputTokens);
            params.put("totalInputTokens", totalInputTokens);
            params.put("totalOutputTokens", totalOutputTokens);
            context.sendNotification("stream.usage", params);
        } catch (IOException e) {
            log.debug("Failed to send usage update notification: {}", e.getMessage());
        }
    }

    private static String summarizeToolInput(String toolName, String inputJson, ObjectMapper mapper) {
        if (inputJson == null || inputJson.isBlank()) {
            return "";
        }
        try {
            var node = mapper.readTree(inputJson);
            if (!node.isObject()) {
                return truncate(inputJson, 80);
            }
            String value = switch (toolName) {
                case "bash" -> firstNonBlank(node, "command", "cmd");
                case "read_file", "write_file", "edit_file" -> firstNonBlank(node, "file_path", "path");
                case "grep" -> firstNonBlank(node, "pattern", "query");
                case "glob" -> firstNonBlank(node, "pattern", "path");
                case "list_directory" -> firstNonBlank(node, "path");
                case "web_fetch" -> firstNonBlank(node, "url");
                case "web_search" -> firstNonBlank(node, "query");
                case "browser" -> firstNonBlank(node, "action", "url");
                case "task" -> firstNonBlank(node, "prompt", "description");
                case "skill" -> firstNonBlank(node, "name", "skill", "prompt");
                default -> firstNonBlank(node, "path", "file_path", "query", "url", "command");
            };
            if (value == null || value.isBlank()) {
                return "";
            }
            return truncate(value.replace('\n', ' '), 80);
        } catch (Exception ignored) {
            return "";
        }
    }

    private static String firstNonBlank(JsonNode node, String... fields) {
        for (var field : fields) {
            var value = node.path(field).asText("");
            if (!value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    /**
     * Local copy of the truncation helper previously inherited from
     * {@code StreamingAgentHandler.truncate}. Kept private — same shape and
     * behaviour as the outer original, but inlined here so the handler is
     * self-contained and the outer-class extraction stays mechanical.
     */
    private static String truncate(String text, int maxLen) {
        if (text == null || text.isEmpty()) return "";
        text = text.strip().replace("\n", " ");
        if (text.length() <= maxLen) return text;
        return text.substring(0, maxLen) + "...";
    }
}
