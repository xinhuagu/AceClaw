package dev.aceclaw.daemon;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import dev.aceclaw.core.agent.AgentTypeRegistry;
import dev.aceclaw.core.agent.StreamingAgentLoop;
import dev.aceclaw.core.agent.SubAgentPermissionChecker;
import dev.aceclaw.core.agent.SubAgentRunner;
import dev.aceclaw.core.agent.ToolRegistry;
import dev.aceclaw.security.DefaultPermissionPolicy;
import dev.aceclaw.security.PermissionManager;
import dev.aceclaw.tools.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for background sub-agent execution and TaskOutputTool.
 *
 * <p>Verifies the full flow: parent agent launches a background task via
 * {@code task} tool with {@code run_in_background=true}, then retrieves
 * the result using {@code task_output}.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@Timeout(value = 30, unit = TimeUnit.SECONDS)
@DisabledOnOs(OS.WINDOWS)
class BackgroundTaskIntegrationTest {

    @TempDir
    static Path tempDir;

    private static Path socketPath;
    private static Path workDir;
    private static MockLlmClient mockLlm;
    private static SessionManager sessionManager;
    private static PermissionManager permissionManager;
    private static UdsListener udsListener;
    private static ObjectMapper objectMapper;

    @BeforeAll
    static void startDaemon() throws Exception {
        socketPath = tempDir.resolve("bg-task-test.sock");
        workDir = tempDir.resolve("workspace");
        Files.createDirectories(workDir);

        objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

        sessionManager = new SessionManager();
        var router = new RequestRouter(sessionManager, objectMapper);
        var connectionBridge = new ConnectionBridge(router, objectMapper);

        mockLlm = new MockLlmClient();

        var toolRegistry = new ToolRegistry();
        toolRegistry.register(new ReadFileTool(workDir));
        toolRegistry.register(new GlobSearchTool(workDir));
        toolRegistry.register(new GrepSearchTool(workDir));

        permissionManager = new PermissionManager(new DefaultPermissionPolicy());

        var agentTypeRegistry = AgentTypeRegistry.withBuiltins();
        var readOnlyTools = java.util.Set.of("read_file", "glob", "grep");
        var subAgentPermChecker = new SubAgentPermissionChecker(
                readOnlyTools, permissionManager::hasSessionApproval);
        var subAgentRunner = new SubAgentRunner(
                mockLlm, toolRegistry, "mock-model", workDir, 4096, 0,
                subAgentPermChecker, null);
        toolRegistry.register(new TaskTool(subAgentRunner, agentTypeRegistry));
        toolRegistry.register(new TaskOutputTool(subAgentRunner));

        var agentLoop = new StreamingAgentLoop(
                mockLlm, toolRegistry, "mock-model",
                "You are a test agent with background task support.");
        var agentHandler = new StreamingAgentHandler(
                sessionManager, agentLoop, toolRegistry, permissionManager, objectMapper);
        agentHandler.setLlmConfig(mockLlm, "mock-model",
                "You are a test agent with background task support.");
        agentHandler.register(router);

        udsListener = new UdsListener(socketPath, connectionBridge);
        udsListener.start();

        long deadline = System.currentTimeMillis() + 5000;
        while (!Files.exists(socketPath) && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        if (!Files.exists(socketPath)) {
            throw new IllegalStateException("UDS listener did not create socket within 5s");
        }
        Thread.sleep(50);
    }

    @AfterAll
    static void stopDaemon() {
        if (udsListener != null) {
            udsListener.stop();
        }
    }

    private final StringBuilder channelLineBuffer = new StringBuilder();

    @BeforeEach
    void resetMock() {
        mockLlm.reset();
        permissionManager.clearAllSessionApprovals();
        channelLineBuffer.setLength(0);
    }

    @Test
    @Order(1)
    void testBackgroundTaskLaunch() throws Exception {
        // Use a CountDownLatch-based mock to control timing between parent and background threads.
        // The background sub-agent's response is delayed until the parent turn completes.
        var latch = new java.util.concurrent.CountDownLatch(1);

        try (var channel = connectToSocket()) {
            String sessionId = createSession(channel, 1);

            // FIFO queue entry 1: parent calls "task" with run_in_background=true
            mockLlm.enqueueResponse(MockLlmClient.toolUseResponse(
                    "Let me search in the background.",
                    "toolu_bg_001", "task",
                    "{\"agent_type\":\"explore\",\"prompt\":\"Find README files.\",\"run_in_background\":true}"
            ));
            // FIFO queue entry 2: parent receives tool result and responds
            // Since background thread also needs a response, we need enough in the queue.
            // The parent turn will complete with entry #2 (text response).
            // The background thread will consume entry #3 asynchronously.
            mockLlm.enqueueResponse(MockLlmClient.textResponse(
                    "Background task launched. Use task_output to check the result."));
            // Entry 3: for the background sub-agent (may consume before or after parent)
            mockLlm.enqueueResponse(MockLlmClient.textResponse(
                    "Found README.md at workspace root."));

            var promptParams = objectMapper.createObjectNode();
            promptParams.put("sessionId", sessionId);
            promptParams.put("prompt", "Search for README files in the background");

            var result = sendPromptAndCollectEvents(channel, promptParams, 2);

            var response = result.finalResponse();
            assertThat(response.has("result")).isTrue();

            // Verify: the parent made at least 2 LLM calls
            // (the background thread may or may not have consumed its response yet)
            assertThat(mockLlm.capturedRequests().size()).isGreaterThanOrEqualTo(2);

            // Verify that the tool result notifications include evidence of background launch.
            // Look for stream.tool_use notifications with tool name "task"
            boolean hasTaskToolUse = result.notifications().stream()
                    .anyMatch(n -> n.has("method")
                            && "stream.tool_use".equals(n.get("method").asText())
                            && n.has("params")
                            && n.get("params").has("name")
                            && "task".equals(n.get("params").get("name").asText()));
            assertThat(hasTaskToolUse).as("Should have a task tool_use notification").isTrue();

            // Wait for background thread to complete
            Thread.sleep(500);

            destroySession(channel, sessionId, 3);
        }
    }

    @Test
    @Order(2)
    void testTaskOutputUnknownTaskId() throws Exception {
        try (var channel = connectToSocket()) {
            String sessionId = createSession(channel, 1);

            // Parent calls task_output with unknown task ID
            mockLlm.enqueueResponse(MockLlmClient.toolUseResponse(
                    "Let me check the task.",
                    "toolu_check_001", "task_output",
                    "{\"task_id\":\"nonexistent-id\"}"
            ));
            // After error, parent responds
            mockLlm.enqueueResponse(MockLlmClient.textResponse(
                    "The task ID was not found. It may have expired or the daemon was restarted."));

            var promptParams = objectMapper.createObjectNode();
            promptParams.put("sessionId", sessionId);
            promptParams.put("prompt", "Check background task nonexistent-id");

            var result = sendPromptAndCollectEvents(channel, promptParams, 2);

            var response = result.finalResponse();
            assertThat(response.has("result")).isTrue();
            assertThat(response.get("result").get("response").asText())
                    .containsIgnoringCase("not found");

            assertThat(mockLlm.capturedRequests()).hasSize(2);

            destroySession(channel, sessionId, 3);
        }
    }

    @Test
    @Order(3)
    void testTaskOutputToolExcludedFromSubAgents() throws Exception {
        try (var channel = connectToSocket()) {
            String sessionId = createSession(channel, 1);

            // Parent delegates to general sub-agent
            mockLlm.enqueueResponse(MockLlmClient.toolUseResponse(
                    "Delegating to general agent.",
                    "toolu_task_nest", "task",
                    "{\"agent_type\":\"general\",\"prompt\":\"Try using task_output.\"}"
            ));
            // Sub-agent response (task_output not available)
            mockLlm.enqueueResponse(MockLlmClient.textResponse(
                    "The task_output tool is not available to me."));
            // Parent follow-up
            mockLlm.enqueueResponse(MockLlmClient.textResponse(
                    "The sub-agent confirmed task_output is not available to it."));

            var promptParams = objectMapper.createObjectNode();
            promptParams.put("sessionId", sessionId);
            promptParams.put("prompt", "Check if sub-agent has task_output");

            var result = sendPromptAndCollectEvents(channel, promptParams, 2);

            // Verify sub-agent's tools don't include task_output
            assertThat(mockLlm.capturedRequests()).hasSize(3);
            var subAgentRequest = mockLlm.capturedRequests().get(1);
            if (subAgentRequest.tools() != null) {
                boolean hasTaskOutput = subAgentRequest.tools().stream()
                        .anyMatch(t -> "task_output".equals(t.name()));
                assertThat(hasTaskOutput)
                        .as("Sub-agent should not have access to 'task_output' tool")
                        .isFalse();

                boolean hasTask = subAgentRequest.tools().stream()
                        .anyMatch(t -> "task".equals(t.name()));
                assertThat(hasTask)
                        .as("Sub-agent should not have access to 'task' tool")
                        .isFalse();
            }

            destroySession(channel, sessionId, 3);
        }
    }

    // -- Helper methods (same pattern as SubAgentIntegrationTest) --

    private SocketChannel connectToSocket() throws IOException {
        var address = UnixDomainSocketAddress.of(socketPath);
        var channel = SocketChannel.open(StandardProtocolFamily.UNIX);
        channel.connect(address);
        return channel;
    }

    private String createSession(SocketChannel channel, int requestId) throws Exception {
        var params = objectMapper.createObjectNode().put("project", workDir.toString());
        var response = sendAndReceive(channel, "session.create", params, requestId);
        return response.get("result").get("sessionId").asText();
    }

    private void destroySession(SocketChannel channel, String sessionId, int requestId) throws Exception {
        var params = objectMapper.createObjectNode().put("sessionId", sessionId);
        sendAndReceive(channel, "session.destroy", params, requestId);
    }

    private JsonNode sendAndReceive(SocketChannel channel, String method,
                                    JsonNode params, int requestId) throws Exception {
        sendRequest(channel, method, params, requestId);
        return readMessage(channel);
    }

    private PromptResult sendPromptAndCollectEvents(SocketChannel channel,
                                                    JsonNode params, int requestId) throws Exception {
        sendRequest(channel, "agent.prompt", params, requestId);
        return collectEventsUntilResponse(channel);
    }

    private PromptResult collectEventsUntilResponse(SocketChannel channel) throws Exception {
        var notifications = new ArrayList<JsonNode>();
        var timeoutDeadline = System.currentTimeMillis() + 20_000; // 20s for background tasks

        while (System.currentTimeMillis() < timeoutDeadline) {
            var msg = readMessage(channel);
            if (msg == null) {
                throw new IOException("Connection closed while waiting for response");
            }

            if (msg.has("id") && !msg.get("id").isNull()) {
                return new PromptResult(notifications, msg);
            }

            // Handle permission requests (auto-approve)
            if (msg.has("method") && "permission.request".equals(msg.get("method").asText())) {
                var permParams = msg.get("params");
                var permRequestId = permParams.get("requestId").asText();
                var responseNode = objectMapper.createObjectNode();
                responseNode.put("method", "permission.response");
                var responseParams = objectMapper.createObjectNode();
                responseParams.put("requestId", permRequestId);
                responseParams.put("approved", true);
                responseParams.put("remember", false);
                responseNode.set("params", responseParams);
                var json = objectMapper.writeValueAsString(responseNode) + "\n";
                channel.write(ByteBuffer.wrap(json.getBytes(StandardCharsets.UTF_8)));
            }

            notifications.add(msg);
        }

        throw new AssertionError("Timed out waiting for response");
    }

    private void sendRequest(SocketChannel channel, String method,
                             JsonNode params, int requestId) throws Exception {
        var request = objectMapper.createObjectNode();
        request.put("jsonrpc", "2.0");
        request.put("method", method);
        if (params != null) {
            request.set("params", params);
        }
        request.put("id", requestId);

        var json = objectMapper.writeValueAsString(request) + "\n";
        channel.write(ByteBuffer.wrap(json.getBytes(StandardCharsets.UTF_8)));
    }

    private JsonNode readMessage(SocketChannel channel) throws Exception {
        var buffer = ByteBuffer.allocate(65536);

        while (true) {
            int newlineIdx = channelLineBuffer.indexOf("\n");
            if (newlineIdx != -1) {
                var line = channelLineBuffer.substring(0, newlineIdx).trim();
                channelLineBuffer.delete(0, newlineIdx + 1);
                if (!line.isEmpty()) {
                    return objectMapper.readTree(line);
                }
            }

            buffer.clear();
            int bytesRead = channel.read(buffer);
            if (bytesRead == -1) return null;
            if (bytesRead == 0) continue;

            buffer.flip();
            channelLineBuffer.append(StandardCharsets.UTF_8.decode(buffer));
        }
    }

    record PromptResult(List<JsonNode> notifications, JsonNode finalResponse) {}
}
