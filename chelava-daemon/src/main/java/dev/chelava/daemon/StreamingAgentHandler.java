package dev.chelava.daemon;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.chelava.core.agent.StreamingAgentLoop;
import dev.chelava.core.agent.Tool;
import dev.chelava.core.agent.ToolRegistry;
import dev.chelava.core.llm.ContentBlock;
import dev.chelava.core.llm.Message;
import dev.chelava.core.llm.StreamEvent;
import dev.chelava.core.llm.StreamEventHandler;
import dev.chelava.security.PermissionDecision;
import dev.chelava.security.PermissionLevel;
import dev.chelava.security.PermissionManager;
import dev.chelava.security.PermissionRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Handles {@code agent.prompt} JSON-RPC requests with streaming support.
 *
 * <p>Uses {@link StreamingAgentLoop} to run the agent's ReAct loop while
 * forwarding LLM stream events (text deltas, tool use) as JSON-RPC notifications
 * to the client via a {@link StreamContext}.
 *
 * <p>Integrates with {@link PermissionManager} to check each tool call before
 * execution. When user approval is needed, sends a {@code permission.request}
 * notification and reads back the client's {@code permission.response}.
 */
public final class StreamingAgentHandler {

    private static final Logger log = LoggerFactory.getLogger(StreamingAgentHandler.class);

    /** Permission level assignments for known tools. */
    private static final Map<String, PermissionLevel> TOOL_PERMISSION_LEVELS = Map.of(
            "read_file", PermissionLevel.READ,
            "glob", PermissionLevel.READ,
            "grep", PermissionLevel.READ,
            "write_file", PermissionLevel.WRITE,
            "edit_file", PermissionLevel.WRITE,
            "bash", PermissionLevel.EXECUTE
    );

    private final SessionManager sessionManager;
    private final StreamingAgentLoop agentLoop;
    private final ToolRegistry toolRegistry;
    private final PermissionManager permissionManager;
    private final ObjectMapper objectMapper;

    /**
     * Creates a streaming agent handler.
     *
     * @param sessionManager    the session manager for looking up sessions
     * @param agentLoop         the streaming agent loop to execute prompts
     * @param toolRegistry      the tool registry for permission-aware tool wrapping
     * @param permissionManager the permission manager for tool access control
     * @param objectMapper      Jackson mapper for building JSON responses
     */
    public StreamingAgentHandler(
            SessionManager sessionManager,
            StreamingAgentLoop agentLoop,
            ToolRegistry toolRegistry,
            PermissionManager permissionManager,
            ObjectMapper objectMapper) {
        this.sessionManager = sessionManager;
        this.agentLoop = agentLoop;
        this.toolRegistry = toolRegistry;
        this.permissionManager = permissionManager;
        this.objectMapper = objectMapper;
    }

    /**
     * Registers the {@code agent.prompt} streaming handler with the given router.
     */
    public void register(RequestRouter router) {
        router.registerStreaming("agent.prompt", this::handlePrompt);
    }

    private Object handlePrompt(JsonNode params, StreamContext context) throws Exception {
        var sessionId = requireString(params, "sessionId");
        var prompt = requireString(params, "prompt");

        var session = sessionManager.getSession(sessionId);
        if (session == null) {
            throw new IllegalArgumentException("Session not found: " + sessionId);
        }
        if (!session.isActive()) {
            throw new IllegalArgumentException("Session is not active: " + sessionId);
        }

        log.info("Streaming agent prompt: sessionId={}, promptLength={}", sessionId, prompt.length());

        // Convert session conversation history to LLM messages
        var conversationHistory = toMessages(session.messages());

        // Create a StreamEventHandler that forwards events via the StreamContext
        var eventHandler = new StreamingNotificationHandler(context, objectMapper);

        // Wrap tools with permission checking for this request
        var permissionAwareRegistry = createPermissionAwareRegistry(context);

        // Create a temporary agent loop with the permission-aware registry
        var permissionAwareLoop = new StreamingAgentLoop(
                getLlmClient(), permissionAwareRegistry,
                getModel(), getSystemPrompt());

        // Run the streaming turn with error handling
        try {
            var turn = permissionAwareLoop.runTurn(prompt, conversationHistory, eventHandler);

            // Store messages in the session
            session.addMessage(new AgentSession.ConversationMessage.User(prompt));
            var responseText = turn.text();
            if (!responseText.isEmpty()) {
                session.addMessage(new AgentSession.ConversationMessage.Assistant(responseText));
            }

            // Build the final response
            var result = objectMapper.createObjectNode();
            result.put("sessionId", sessionId);
            result.put("response", responseText);
            result.put("stopReason", turn.finalStopReason().name());

            var usageNode = objectMapper.createObjectNode();
            usageNode.put("inputTokens", turn.totalUsage().inputTokens());
            usageNode.put("outputTokens", turn.totalUsage().outputTokens());
            usageNode.put("totalTokens", turn.totalUsage().totalTokens());
            result.set("usage", usageNode);

            log.info("Streaming turn complete: sessionId={}, stopReason={}, tokens={}",
                    sessionId, turn.finalStopReason(), turn.totalUsage().totalTokens());

            return result;

        } catch (dev.chelava.core.llm.LlmException e) {
            // Translate LLM errors to user-friendly messages
            log.error("LLM error during prompt: statusCode={}, message={}",
                    e.statusCode(), e.getMessage(), e);

            session.addMessage(new AgentSession.ConversationMessage.User(prompt));

            String userMessage = formatLlmError(e);
            throw new IllegalStateException(userMessage);
        }
    }

    /**
     * Translates an {@link dev.chelava.core.llm.LlmException} into a user-friendly message
     * without exposing stack traces or internal details.
     */
    private static String formatLlmError(dev.chelava.core.llm.LlmException e) {
        int status = e.statusCode();
        if (status == 401) {
            return "Invalid API key. Please check your ANTHROPIC_API_KEY or ~/.chelava/config.json.";
        } else if (status == 429) {
            return "Rate limit exceeded. Please wait a moment and try again.";
        } else if (status == 529) {
            return "The API is temporarily overloaded. Please try again shortly.";
        } else if (status >= 500 && status < 600) {
            return "The LLM service is temporarily unavailable (HTTP " + status + "). Please try again.";
        } else if (status == 400) {
            return "Bad request to LLM API: " + e.getMessage();
        } else if (e.getMessage() != null && e.getMessage().contains("not-configured")) {
            return "API key not configured. Set ANTHROPIC_API_KEY or add apiKey to ~/.chelava/config.json.";
        } else {
            return "LLM error: " + e.getMessage();
        }
    }

    /**
     * Creates a ToolRegistry where each tool is wrapped with permission checking.
     * Tools that need user approval will use the StreamContext to ask the client.
     */
    private ToolRegistry createPermissionAwareRegistry(StreamContext context) {
        var registry = new ToolRegistry();
        for (var tool : toolRegistry.all()) {
            registry.register(new PermissionAwareTool(tool, permissionManager, context, objectMapper));
        }
        return registry;
    }

    // Access helpers so the permission-aware loop can use the same LLM config.
    // These reach into the original agentLoop via reflection-free accessors.
    // Since StreamingAgentLoop does not expose these, we store them at construction time.

    private dev.chelava.core.llm.LlmClient llmClient;
    private String model;
    private String systemPrompt;

    /**
     * Sets the LLM configuration for permission-aware agent loop creation.
     * Must be called before registering with the router.
     */
    public void setLlmConfig(dev.chelava.core.llm.LlmClient llmClient, String model, String systemPrompt) {
        this.llmClient = llmClient;
        this.model = model;
        this.systemPrompt = systemPrompt;
    }

    private dev.chelava.core.llm.LlmClient getLlmClient() {
        return llmClient;
    }

    private String getModel() {
        return model;
    }

    private String getSystemPrompt() {
        return systemPrompt;
    }

    // -- Message conversion ------------------------------------------------

    /**
     * Converts the session's conversation messages into LLM {@link Message} format.
     */
    private static List<Message> toMessages(List<AgentSession.ConversationMessage> conversationMessages) {
        var messages = new ArrayList<Message>();
        for (var msg : conversationMessages) {
            switch (msg) {
                case AgentSession.ConversationMessage.User u ->
                        messages.add(Message.user(u.content()));
                case AgentSession.ConversationMessage.Assistant a ->
                        messages.add(Message.assistant(a.content()));
                case AgentSession.ConversationMessage.System ignored -> {
                    // System messages are handled via the system prompt, not in conversation history
                }
            }
        }
        return messages;
    }

    private static String requireString(JsonNode params, String field) {
        if (params == null || !params.has(field) || params.get(field).isNull()) {
            throw new IllegalArgumentException("Missing required parameter: " + field);
        }
        return params.get(field).asText();
    }

    // -- StreamEventHandler that forwards events as JSON-RPC notifications --

    /**
     * Forwards stream events from the agent loop to the client as JSON-RPC notifications.
     */
    private static final class StreamingNotificationHandler implements StreamEventHandler {

        private final StreamContext context;
        private final ObjectMapper objectMapper;

        StreamingNotificationHandler(StreamContext context, ObjectMapper objectMapper) {
            this.context = context;
            this.objectMapper = objectMapper;
        }

        @Override
        public void onTextDelta(StreamEvent.TextDelta event) {
            try {
                var params = objectMapper.createObjectNode();
                params.put("delta", event.text());
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
                    context.sendNotification("stream.tool_use", params);
                } catch (IOException e) {
                    log.warn("Failed to send tool use notification: {}", e.getMessage());
                }
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
    }

    // -- Permission-aware tool wrapper -------------------------------------

    /**
     * Wraps a tool with permission checking. Before executing, checks
     * the permission manager and, if needed, sends a permission request
     * to the client and waits for the response.
     */
    private static final class PermissionAwareTool implements Tool {

        private final Tool delegate;
        private final PermissionManager permissionManager;
        private final StreamContext context;
        private final ObjectMapper objectMapper;

        PermissionAwareTool(Tool delegate, PermissionManager permissionManager,
                            StreamContext context, ObjectMapper objectMapper) {
            this.delegate = delegate;
            this.permissionManager = permissionManager;
            this.context = context;
            this.objectMapper = objectMapper;
        }

        @Override
        public String name() {
            return delegate.name();
        }

        @Override
        public String description() {
            return delegate.description();
        }

        @Override
        public JsonNode inputSchema() {
            return delegate.inputSchema();
        }

        @Override
        public ToolResult execute(String inputJson) throws Exception {
            // Determine the permission level for this tool
            var level = TOOL_PERMISSION_LEVELS.getOrDefault(delegate.name(), PermissionLevel.EXECUTE);

            // Build a human-readable description of what the tool will do
            var toolDescription = buildToolDescription(delegate.name(), inputJson);

            var permRequest = new PermissionRequest(delegate.name(), toolDescription, level);
            var decision = permissionManager.check(permRequest);

            switch (decision) {
                case PermissionDecision.Approved ignored -> {
                    // Proceed with execution
                    return delegate.execute(inputJson);
                }

                case PermissionDecision.Denied denied -> {
                    log.info("Tool {} denied: {}", delegate.name(), denied.reason());
                    return new ToolResult("Permission denied: " + denied.reason(), true);
                }

                case PermissionDecision.NeedsUserApproval approval -> {
                    // Send permission request to the client
                    var requestId = "perm-" + UUID.randomUUID().toString().substring(0, 8);

                    try {
                        var params = objectMapper.createObjectNode();
                        params.put("tool", delegate.name());
                        params.put("description", approval.prompt());
                        params.put("requestId", requestId);
                        context.sendNotification("permission.request", params);

                        // Wait for the client's response
                        var responseMsg = context.readMessage();
                        if (responseMsg == null) {
                            return new ToolResult("Permission denied: client disconnected", true);
                        }

                        // Parse the permission response
                        var responseParams = responseMsg.get("params");
                        if (responseParams == null) {
                            return new ToolResult("Permission denied: invalid response from client", true);
                        }

                        var responseRequestId = responseParams.has("requestId")
                                ? responseParams.get("requestId").asText() : "";
                        boolean approved = responseParams.has("approved")
                                && responseParams.get("approved").asBoolean(false);
                        boolean remember = responseParams.has("remember")
                                && responseParams.get("remember").asBoolean(false);

                        if (!approved) {
                            log.info("Tool {} denied by user (requestId={})", delegate.name(), responseRequestId);
                            return new ToolResult("Permission denied by user", true);
                        }

                        // If user chose "remember", grant session-level approval
                        if (remember) {
                            permissionManager.approveForSession(delegate.name());
                        }

                        log.info("Tool {} approved by user (requestId={}, remember={})",
                                delegate.name(), responseRequestId, remember);
                        return delegate.execute(inputJson);

                    } catch (IOException e) {
                        log.error("Failed to communicate permission request for tool {}: {}",
                                delegate.name(), e.getMessage());
                        return new ToolResult("Permission check failed: " + e.getMessage(), true);
                    }
                }
            }
        }

        /**
         * Builds a human-readable description of what the tool will do based on its input.
         */
        private String buildToolDescription(String toolName, String inputJson) {
            try {
                var input = objectMapper.readTree(inputJson);
                return switch (toolName) {
                    case "bash" -> "Execute: " + (input.has("command")
                            ? input.get("command").asText() : "(unknown command)");
                    case "write_file" -> "Write to file: " + (input.has("file_path")
                            ? input.get("file_path").asText() : "(unknown path)");
                    case "edit_file" -> "Edit file: " + (input.has("file_path")
                            ? input.get("file_path").asText() : "(unknown path)");
                    case "read_file" -> "Read file: " + (input.has("file_path")
                            ? input.get("file_path").asText() : "(unknown path)");
                    case "glob" -> "Search files: " + (input.has("pattern")
                            ? input.get("pattern").asText() : "(unknown pattern)");
                    case "grep" -> "Search content: " + (input.has("pattern")
                            ? input.get("pattern").asText() : "(unknown pattern)");
                    default -> "Execute tool: " + toolName;
                };
            } catch (Exception e) {
                return "Execute tool: " + toolName;
            }
        }
    }
}
