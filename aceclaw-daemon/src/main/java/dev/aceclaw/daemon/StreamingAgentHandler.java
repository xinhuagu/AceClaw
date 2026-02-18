package dev.aceclaw.daemon;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.aceclaw.core.agent.CancellationToken;
import dev.aceclaw.core.agent.MessageCompactor;
import dev.aceclaw.core.agent.StreamingAgentLoop;
import dev.aceclaw.core.agent.Tool;
import dev.aceclaw.core.agent.ToolRegistry;
import dev.aceclaw.core.llm.ContentBlock;
import dev.aceclaw.core.llm.Message;
import dev.aceclaw.core.llm.StreamEvent;
import dev.aceclaw.core.llm.StreamEventHandler;
import dev.aceclaw.memory.AutoMemoryStore;
import dev.aceclaw.memory.DailyJournal;
import dev.aceclaw.memory.MemoryEntry;
import dev.aceclaw.security.PermissionDecision;
import dev.aceclaw.security.PermissionLevel;
import dev.aceclaw.security.PermissionManager;
import dev.aceclaw.security.PermissionRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

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
    private static final Map<String, PermissionLevel> TOOL_PERMISSION_LEVELS = Map.ofEntries(
            Map.entry("read_file", PermissionLevel.READ),
            Map.entry("glob", PermissionLevel.READ),
            Map.entry("grep", PermissionLevel.READ),
            Map.entry("list_directory", PermissionLevel.READ),
            Map.entry("web_fetch", PermissionLevel.READ),
            Map.entry("web_search", PermissionLevel.READ),
            Map.entry("screen_capture", PermissionLevel.READ),
            Map.entry("memory", PermissionLevel.READ),
            Map.entry("write_file", PermissionLevel.WRITE),
            Map.entry("edit_file", PermissionLevel.WRITE),
            Map.entry("bash", PermissionLevel.EXECUTE),
            Map.entry("browser", PermissionLevel.EXECUTE),
            Map.entry("applescript", PermissionLevel.EXECUTE)
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

        // Set up cancellation support: the CancelAwareStreamContext runs a monitor
        // thread that reads from the socket and dispatches agent.cancel and
        // permission.response messages accordingly.
        var cancellationToken = new CancellationToken();
        var cancelContext = new CancelAwareStreamContext(context, cancellationToken, objectMapper);

        // Create a StreamEventHandler that forwards events via the cancel-aware context
        var eventHandler = new StreamingNotificationHandler(cancelContext, objectMapper);

        // Wrap tools with permission checking for this request
        var permissionAwareRegistry = createPermissionAwareRegistry(cancelContext);

        // Create a temporary agent loop with the permission-aware registry and compaction
        var permissionAwareLoop = new StreamingAgentLoop(
                getLlmClient(), permissionAwareRegistry,
                getModel(), getSystemPrompt(),
                maxTokens, thinkingBudget, compactor);

        // Start the cancel monitor thread to read from the socket
        cancelContext.startMonitor();
        try {
            var turn = permissionAwareLoop.runTurn(prompt, conversationHistory,
                    eventHandler, cancellationToken);

            // Send cancelled notification if the turn was cancelled
            if (cancellationToken.isCancelled()) {
                try {
                    var cancelParams = objectMapper.createObjectNode();
                    cancelParams.put("sessionId", sessionId);
                    cancelContext.sendNotification("stream.cancelled", cancelParams);
                } catch (IOException e) {
                    log.warn("Failed to send stream.cancelled notification: {}", e.getMessage());
                }
            }

            // Handle compaction: if compaction occurred, replace session history
            // and persist extracted context items to auto-memory
            if (turn.wasCompacted()) {
                handleCompactionResult(session, turn.compactionResult());
            }

            // Store messages in the session
            session.addMessage(new AgentSession.ConversationMessage.User(prompt));
            var responseText = turn.text();
            if (!responseText.isEmpty()) {
                session.addMessage(new AgentSession.ConversationMessage.Assistant(responseText));
            }

            // Log activity to daily journal for cross-session memory
            if (dailyJournal != null) {
                logTurnToJournal(prompt, turn);
            }

            // Build the final response
            var result = objectMapper.createObjectNode();
            result.put("sessionId", sessionId);
            result.put("response", responseText);
            result.put("stopReason", turn.finalStopReason().name());
            if (cancellationToken.isCancelled()) {
                result.put("cancelled", true);
            }

            var usageNode = objectMapper.createObjectNode();
            usageNode.put("inputTokens", turn.totalUsage().inputTokens());
            usageNode.put("outputTokens", turn.totalUsage().outputTokens());
            usageNode.put("totalTokens", turn.totalUsage().totalTokens());
            result.set("usage", usageNode);

            if (turn.wasCompacted()) {
                result.put("compacted", true);
                result.put("compactionPhase", turn.compactionResult().phaseReached().name());
            }

            log.info("Streaming turn complete: sessionId={}, stopReason={}, tokens={}, cancelled={}, compacted={}",
                    sessionId, turn.finalStopReason(), turn.totalUsage().totalTokens(),
                    cancellationToken.isCancelled(), turn.wasCompacted());

            return result;

        } catch (dev.aceclaw.core.llm.LlmException e) {
            // Translate LLM errors to user-friendly messages
            log.error("LLM error during prompt: statusCode={}, message={}",
                    e.statusCode(), e.getMessage(), e);

            session.addMessage(new AgentSession.ConversationMessage.User(prompt));

            String userMessage = formatLlmError(e);
            throw new IllegalStateException(userMessage);
        } finally {
            cancelContext.stopMonitor();
        }
    }

    /**
     * Translates an {@link dev.aceclaw.core.llm.LlmException} into a user-friendly message
     * without exposing stack traces or internal details.
     */
    private static String formatLlmError(dev.aceclaw.core.llm.LlmException e) {
        int status = e.statusCode();
        if (status == 401) {
            return "Invalid API key. Please check your API key configuration in env vars or ~/.aceclaw/config.json.";
        } else if (status == 429) {
            return "Rate limit exceeded. Please wait a moment and try again.";
        } else if (status == 529) {
            return "The API is temporarily overloaded. Please try again shortly.";
        } else if (status >= 500 && status < 600) {
            return "The LLM service is temporarily unavailable (HTTP " + status + "). Please try again.";
        } else if (status == 400) {
            return "Bad request to LLM API: " + e.getMessage();
        } else if (e.getMessage() != null && e.getMessage().contains("not-configured")) {
            return "API key not configured. Set ANTHROPIC_API_KEY (or OPENAI_API_KEY) or add apiKey to ~/.aceclaw/config.json.";
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

    private dev.aceclaw.core.llm.LlmClient llmClient;
    private String model;
    private String systemPrompt;
    private int maxTokens = 16384;
    private int thinkingBudget = 10240;
    private MessageCompactor compactor;
    private AutoMemoryStore memoryStore;
    private DailyJournal dailyJournal;
    private Path workingDir;

    /**
     * Sets the LLM configuration for permission-aware agent loop creation.
     * Must be called before registering with the router.
     */
    public void setLlmConfig(dev.aceclaw.core.llm.LlmClient llmClient, String model, String systemPrompt) {
        this.llmClient = llmClient;
        this.model = model;
        this.systemPrompt = systemPrompt;
    }

    /**
     * Sets the token configuration for permission-aware agent loop creation.
     */
    public void setTokenConfig(int maxTokens, int thinkingBudget) {
        this.maxTokens = maxTokens;
        this.thinkingBudget = thinkingBudget;
    }

    /**
     * Sets the message compactor for context compaction support.
     */
    public void setCompactor(MessageCompactor compactor) {
        this.compactor = compactor;
    }

    /**
     * Sets the auto-memory store for persisting context extracted during compaction.
     */
    public void setMemoryStore(AutoMemoryStore memoryStore, Path workingDir) {
        this.memoryStore = memoryStore;
        this.workingDir = workingDir;
    }

    /**
     * Sets the daily journal for appending compaction events.
     */
    public void setDailyJournal(DailyJournal dailyJournal) {
        this.dailyJournal = dailyJournal;
    }

    private dev.aceclaw.core.llm.LlmClient getLlmClient() {
        return llmClient;
    }

    private String getModel() {
        return model;
    }

    private String getSystemPrompt() {
        return systemPrompt;
    }

    /**
     * Handles the result of a context compaction during a turn.
     * Replaces session conversation history with compacted messages and
     * persists extracted context items to auto-memory.
     */
    private void handleCompactionResult(AgentSession session,
                                        dev.aceclaw.core.agent.CompactionResult result) {
        // Replace session history with compacted summary messages
        var compactedConversation = new ArrayList<AgentSession.ConversationMessage>();
        for (var msg : result.compactedMessages()) {
            switch (msg) {
                case Message.UserMessage u -> {
                    String text = u.content().stream()
                            .filter(b -> b instanceof ContentBlock.Text)
                            .map(b -> ((ContentBlock.Text) b).text())
                            .reduce("", (a, b) -> a + b);
                    if (!text.isEmpty()) {
                        compactedConversation.add(
                                new AgentSession.ConversationMessage.User(text));
                    }
                }
                case Message.AssistantMessage a -> {
                    String text = a.content().stream()
                            .filter(b -> b instanceof ContentBlock.Text)
                            .map(b -> ((ContentBlock.Text) b).text())
                            .reduce("", (a2, b) -> a2 + b);
                    if (!text.isEmpty()) {
                        compactedConversation.add(
                                new AgentSession.ConversationMessage.Assistant(text));
                    }
                }
            }
        }
        session.replaceMessages(compactedConversation);

        log.info("Session {} history replaced with {} compacted messages (was {})",
                session.id(), compactedConversation.size(),
                result.originalTokenEstimate() + " estimated tokens");

        // Persist extracted context items to auto-memory (Phase 0 memory flush)
        if (memoryStore != null && !result.extractedContext().isEmpty()) {
            for (var item : result.extractedContext()) {
                try {
                    memoryStore.add(
                            MemoryEntry.Category.CODEBASE_INSIGHT,
                            item,
                            List.of("compaction", "auto-extracted"),
                            "compaction:" + session.id(),
                            false,
                            workingDir);
                } catch (Exception e) {
                    log.warn("Failed to persist compaction context to memory: {}", e.getMessage());
                }
            }
            log.info("Persisted {} context items to auto-memory from compaction",
                    result.extractedContext().size());
        }

        // Append compaction event to daily journal
        if (dailyJournal != null) {
            dailyJournal.append("Context compacted: " + result.phaseReached().name() +
                    " (original ~" + result.originalTokenEstimate() +
                    " tokens, extracted " + result.extractedContext().size() + " items)");
        }
    }

    // -- Per-turn journal logging -------------------------------------------

    /**
     * Logs a brief summary of a completed turn to the daily journal.
     * This enables cross-session memory: new sessions see what happened in previous ones.
     */
    private void logTurnToJournal(String userPrompt,
                                  dev.aceclaw.core.agent.Turn turn) {
        try {
            var toolsUsed = extractToolNames(turn.newMessages());
            var promptSummary = truncate(userPrompt, 100);
            var responseSummary = truncate(turn.text(), 150);

            var sb = new StringBuilder();
            sb.append("User: ").append(promptSummary);
            if (!responseSummary.isEmpty()) {
                sb.append(" -> Agent: ").append(responseSummary);
            }
            if (!toolsUsed.isEmpty()) {
                sb.append(" | Tools: ").append(String.join(", ", toolsUsed));
            }
            sb.append(" | Tokens: ").append(turn.totalUsage().totalTokens());

            dailyJournal.append(sb.toString());
        } catch (Exception e) {
            log.warn("Failed to log turn to journal: {}", e.getMessage());
        }
    }

    /**
     * Extracts unique tool names from the turn's messages.
     */
    private static List<String> extractToolNames(List<Message> messages) {
        return messages.stream()
                .filter(m -> m instanceof Message.AssistantMessage)
                .flatMap(m -> ((Message.AssistantMessage) m).content().stream())
                .filter(b -> b instanceof ContentBlock.ToolUse)
                .map(b -> ((ContentBlock.ToolUse) b).name())
                .distinct()
                .toList();
    }

    private static String truncate(String text, int maxLen) {
        if (text == null || text.isEmpty()) return "";
        text = text.strip().replace("\n", " ");
        if (text.length() <= maxLen) return text;
        return text.substring(0, maxLen) + "...";
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
        public void onThinkingDelta(StreamEvent.ThinkingDelta event) {
            try {
                var params = objectMapper.createObjectNode();
                params.put("delta", event.text());
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
    }

    // -- Cancel-aware stream context -----------------------------------------

    /**
     * A {@link StreamContext} wrapper that runs a background monitor thread
     * to read from the socket during streaming. This is necessary because
     * the connection thread is blocked in {@code handlePrompt()}, so
     * {@code agent.cancel} notifications buffered in the socket go unread.
     *
     * <p>The monitor thread uses non-blocking NIO with a {@link Selector}
     * to poll the socket with 100ms timeout intervals. This allows the
     * thread to exit cleanly when stopped, without interrupting (which
     * would close the NIO channel).
     *
     * <p>The monitor thread reads messages from the socket and:
     * <ul>
     *   <li>On {@code agent.cancel}: triggers the cancellation token</li>
     *   <li>On {@code permission.response}: enqueues to a blocking queue
     *       so the permission flow can consume it</li>
     *   <li>On connection close: triggers cancellation</li>
     * </ul>
     */
    private static final class CancelAwareStreamContext implements StreamContext {

        private static final int READ_BUFFER_SIZE = 65536;
        private static final long SELECT_TIMEOUT_MS = 100;

        private final StreamContext delegate;
        private final CancellationToken cancellationToken;
        private final ObjectMapper objectMapper;
        private final SocketChannel channel;
        private final StringBuilder lineBuilder;
        private final BlockingQueue<JsonNode> permissionResponses = new LinkedBlockingQueue<>();
        private volatile boolean stopped = false;
        private volatile Thread monitorThread;
        private volatile Selector selector;

        CancelAwareStreamContext(StreamContext delegate, CancellationToken cancellationToken,
                                 ObjectMapper objectMapper) {
            this.delegate = delegate;
            this.cancellationToken = cancellationToken;
            this.objectMapper = objectMapper;

            // Extract the channel and lineBuilder from the underlying ChannelStreamContext
            if (delegate instanceof ConnectionBridge.ChannelStreamContext channelCtx) {
                this.channel = channelCtx.channel();
                this.lineBuilder = channelCtx.lineBuilder();
            } else {
                this.channel = null;
                this.lineBuilder = null;
            }
        }

        /**
         * Starts the background monitor thread that reads from the socket.
         * Switches the channel to non-blocking mode for the duration of monitoring.
         * Must be called before the agent loop starts.
         */
        void startMonitor() {
            if (channel == null) {
                log.debug("Cancel monitor: no socket channel available, skipping monitor");
                return;
            }
            monitorThread = Thread.ofVirtual()
                    .name("aceclaw-cancel-monitor")
                    .start(this::monitorLoop);
        }

        /**
         * Stops the monitor thread cleanly without interrupting.
         * Sets the stopped flag and wakes the selector so the thread exits promptly.
         */
        void stopMonitor() {
            stopped = true;
            var sel = selector;
            if (sel != null) {
                sel.wakeup();
            }
            var thread = monitorThread;
            if (thread != null) {
                try {
                    thread.join(2000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                monitorThread = null;
            }
        }

        private void monitorLoop() {
            try {
                channel.configureBlocking(false);
                selector = Selector.open();
                channel.register(selector, SelectionKey.OP_READ);

                var buffer = ByteBuffer.allocate(READ_BUFFER_SIZE);

                while (!stopped && !cancellationToken.isCancelled()) {
                    int ready = selector.select(SELECT_TIMEOUT_MS);
                    if (stopped || cancellationToken.isCancelled()) break;
                    if (ready == 0) continue;

                    selector.selectedKeys().clear();

                    buffer.clear();
                    int bytesRead = channel.read(buffer);
                    if (bytesRead == -1) {
                        log.debug("Cancel monitor: connection closed, triggering cancellation");
                        cancellationToken.cancel();
                        return;
                    }
                    if (bytesRead == 0) continue;

                    buffer.flip();
                    synchronized (lineBuilder) {
                        lineBuilder.append(StandardCharsets.UTF_8.decode(buffer));

                        // Process complete lines
                        int newlineIdx;
                        while ((newlineIdx = lineBuilder.indexOf("\n")) != -1) {
                            var line = lineBuilder.substring(0, newlineIdx).trim();
                            lineBuilder.delete(0, newlineIdx + 1);

                            if (line.isEmpty()) continue;

                            try {
                                var message = objectMapper.readTree(line);
                                String method = message.has("method")
                                        ? message.get("method").asText("") : "";

                                if ("agent.cancel".equals(method)) {
                                    log.info("Cancel monitor: received agent.cancel");
                                    cancellationToken.cancel();
                                    return;
                                } else if ("permission.response".equals(method)) {
                                    log.debug("Cancel monitor: routing permission.response");
                                    permissionResponses.offer(message);
                                } else {
                                    log.debug("Cancel monitor: ignoring '{}'", method);
                                }
                            } catch (Exception e) {
                                log.warn("Cancel monitor: failed to parse message: {}", e.getMessage());
                            }
                        }
                    }
                }
            } catch (IOException e) {
                if (!stopped && !cancellationToken.isCancelled()) {
                    log.debug("Cancel monitor: I/O error: {}", e.getMessage());
                    cancellationToken.cancel();
                }
            } finally {
                // Restore blocking mode so subsequent reads work normally
                try {
                    if (selector != null) selector.close();
                    if (channel != null && channel.isOpen()) {
                        channel.configureBlocking(true);
                    }
                } catch (IOException e) {
                    log.warn("Cancel monitor: failed to restore blocking mode: {}", e.getMessage());
                }
                selector = null;
            }
        }

        @Override
        public void sendNotification(String method, Object params) throws IOException {
            delegate.sendNotification(method, params);
        }

        /**
         * Reads the next permission response from the queue.
         * Polls with a timeout and checks for cancellation between polls.
         * Returns null if cancelled or the monitor thread is no longer running.
         */
        @Override
        public JsonNode readMessage() throws IOException {
            while (!cancellationToken.isCancelled()) {
                try {
                    JsonNode msg = permissionResponses.poll(500, TimeUnit.MILLISECONDS);
                    if (msg != null) {
                        return msg;
                    }
                    // Check if the monitor thread is still alive
                    var thread = monitorThread;
                    if (thread == null || !thread.isAlive()) {
                        return null;
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return null;
                }
            }
            return null;
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
            // MCP tools default to EXECUTE since they can have side effects
            var level = delegate.name().startsWith("mcp__")
                    ? PermissionLevel.EXECUTE
                    : TOOL_PERMISSION_LEVELS.getOrDefault(delegate.name(), PermissionLevel.EXECUTE);

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
                    case "list_directory" -> "List directory: " + (input.has("path")
                            ? input.get("path").asText() : "(working directory)");
                    case "web_fetch" -> "Fetch URL: " + (input.has("url")
                            ? input.get("url").asText() : "(unknown url)");
                    case "web_search" -> "Web search: " + (input.has("query")
                            ? input.get("query").asText() : "(unknown query)");
                    case "browser" -> "Browser " + (input.has("action")
                            ? input.get("action").asText() : "(unknown action)") +
                            (input.has("url") ? ": " + input.get("url").asText() : "");
                    case "applescript" -> "Execute AppleScript (" +
                            (input.has("script") ? input.get("script").asText().length() + " chars" : "unknown") + ")";
                    case "screen_capture" -> "Capture screenshot" +
                            (input.has("region") ? " (region: " + input.get("region").asText() + ")" : "");
                    default -> {
                        if (toolName.startsWith("mcp__")) {
                            // MCP tools: show server and tool name
                            var parts = toolName.split("__", 3);
                            var server = parts.length > 1 ? parts[1] : "unknown";
                            var tool = parts.length > 2 ? parts[2] : "unknown";
                            yield "MCP [" + server + "] " + tool;
                        } else {
                            yield "Execute tool: " + toolName;
                        }
                    }
                };
            } catch (Exception e) {
                return "Execute tool: " + toolName;
            }
        }
    }
}
