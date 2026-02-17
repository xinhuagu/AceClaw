package dev.aceclaw.daemon;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import dev.aceclaw.core.agent.StreamingAgentLoop;
import dev.aceclaw.core.agent.ToolRegistry;
import dev.aceclaw.security.DefaultPermissionPolicy;
import dev.aceclaw.security.PermissionManager;
import dev.aceclaw.tools.*;
import org.junit.jupiter.api.*;
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
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end integration test for the AceClaw daemon.
 *
 * <p>Starts a real UDS listener with the full request pipeline wired up,
 * but uses a {@link MockLlmClient} instead of a real LLM provider.
 * Tests verify the complete flow: UDS connection → JSON-RPC routing →
 * session management → streaming agent loop → tool execution → permissions.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DaemonIntegrationTest {

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
        socketPath = tempDir.resolve("test.sock");
        workDir = tempDir.resolve("workspace");
        Files.createDirectories(workDir);

        // Infrastructure
        objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

        sessionManager = new SessionManager();
        var router = new RequestRouter(sessionManager, objectMapper);
        var connectionBridge = new ConnectionBridge(router, objectMapper);

        // Mock LLM
        mockLlm = new MockLlmClient();

        // Tool registry with temp working directory
        var toolRegistry = new ToolRegistry();
        toolRegistry.register(new ReadFileTool(workDir));
        toolRegistry.register(new WriteFileTool(workDir));
        toolRegistry.register(new EditFileTool(workDir));
        toolRegistry.register(new BashExecTool(workDir));
        toolRegistry.register(new GlobSearchTool(workDir));
        toolRegistry.register(new GrepSearchTool(workDir));

        // Permission manager
        permissionManager = new PermissionManager(new DefaultPermissionPolicy());

        // Streaming agent loop + handler
        var agentLoop = new StreamingAgentLoop(
                mockLlm, toolRegistry, "mock-model", "You are a test agent.");
        var agentHandler = new StreamingAgentHandler(
                sessionManager, agentLoop, toolRegistry, permissionManager, objectMapper);
        agentHandler.setLlmConfig(mockLlm, "mock-model", "You are a test agent.");
        agentHandler.register(router);

        // Start UDS listener
        udsListener = new UdsListener(socketPath, connectionBridge);
        udsListener.start();

        // Wait for listener to be ready
        Thread.sleep(200);
    }

    @AfterAll
    static void stopDaemon() {
        if (udsListener != null) {
            udsListener.stop();
        }
    }

    @BeforeEach
    void resetMock() {
        mockLlm.reset();
        permissionManager.clearSessionApprovals();
    }

    // -- Session lifecycle tests --

    @Test
    @Order(1)
    void testHealthStatus() throws Exception {
        try (var channel = connectToSocket()) {
            var response = sendAndReceive(channel, "health.status", null, 1);

            assertThat(response.has("result")).isTrue();
            var result = response.get("result");
            assertThat(result.get("status").asText()).isEqualTo("healthy");
            assertThat(result.has("timestamp")).isTrue();
            assertThat(result.get("version").asText()).isEqualTo("0.1.0-SNAPSHOT");
        }
    }

    @Test
    @Order(2)
    void testSessionCreateAndList() throws Exception {
        try (var channel = connectToSocket()) {
            // Create a session
            var createResponse = sendAndReceive(channel, "session.create",
                    objectMapper.createObjectNode().put("project", workDir.toString()), 1);

            assertThat(createResponse.has("result")).isTrue();
            var createResult = createResponse.get("result");
            assertThat(createResult.has("sessionId")).isTrue();
            assertThat(createResult.get("project").asText()).isEqualTo(workDir.toString());

            String sessionId = createResult.get("sessionId").asText();

            // List sessions
            var listResponse = sendAndReceive(channel, "session.list", null, 2);
            assertThat(listResponse.has("result")).isTrue();
            var sessions = listResponse.get("result");
            assertThat(sessions.isArray()).isTrue();
            assertThat(sessions.size()).isGreaterThanOrEqualTo(1);

            boolean found = false;
            for (var sess : sessions) {
                if (sessionId.equals(sess.get("sessionId").asText())) {
                    found = true;
                    assertThat(sess.get("active").asBoolean()).isTrue();
                    break;
                }
            }
            assertThat(found).isTrue();

            // Destroy session
            var destroyResponse = sendAndReceive(channel, "session.destroy",
                    objectMapper.createObjectNode().put("sessionId", sessionId), 3);
            assertThat(destroyResponse.get("result").get("destroyed").asBoolean()).isTrue();
        }
    }

    @Test
    @Order(3)
    void testUnknownMethod() throws Exception {
        try (var channel = connectToSocket()) {
            var response = sendAndReceive(channel, "nonexistent.method", null, 1);

            assertThat(response.has("error")).isTrue();
            assertThat(response.get("error").get("code").asInt()).isEqualTo(JsonRpc.METHOD_NOT_FOUND);
        }
    }

    @Test
    @Order(4)
    void testAgentPromptWithInvalidSession() throws Exception {
        try (var channel = connectToSocket()) {
            mockLlm.enqueueResponse(MockLlmClient.textResponse("Hello"));

            var params = objectMapper.createObjectNode();
            params.put("sessionId", "nonexistent-session-id");
            params.put("prompt", "Hello");

            var response = sendAndReceive(channel, "agent.prompt", params, 1);

            assertThat(response.has("error")).isTrue();
            assertThat(response.get("error").get("message").asText())
                    .contains("Session not found");
        }
    }

    @Test
    @Order(5)
    void testAgentPromptTextOnly() throws Exception {
        try (var channel = connectToSocket()) {
            // Create a session
            String sessionId = createSession(channel, 1);

            // Enqueue a simple text response
            mockLlm.enqueueResponse(MockLlmClient.textResponse("Hello! I'm AceClaw, your AI assistant."));

            // Send the agent.prompt request and collect streaming events
            var promptParams = objectMapper.createObjectNode();
            promptParams.put("sessionId", sessionId);
            promptParams.put("prompt", "Hello, who are you?");

            var result = sendPromptAndCollectEvents(channel, promptParams, 2);

            // Verify streaming notifications were sent
            assertThat(result.notifications()).isNotEmpty();
            boolean hasTextDelta = result.notifications().stream()
                    .anyMatch(n -> "stream.text".equals(n.get("method").asText()));
            assertThat(hasTextDelta).isTrue();

            // Verify the final response
            var response = result.finalResponse();
            assertThat(response.has("result")).isTrue();
            var responseResult = response.get("result");
            assertThat(responseResult.get("sessionId").asText()).isEqualTo(sessionId);
            assertThat(responseResult.get("response").asText())
                    .isEqualTo("Hello! I'm AceClaw, your AI assistant.");
            assertThat(responseResult.get("stopReason").asText()).isEqualTo("END_TURN");
            assertThat(responseResult.has("usage")).isTrue();

            // Verify the LLM was called with the right prompt
            var requests = mockLlm.capturedRequests();
            assertThat(requests).hasSize(1);
            assertThat(requests.getFirst().model()).isEqualTo("mock-model");

            // Clean up
            destroySession(channel, sessionId, 3);
        }
    }

    @Test
    @Order(6)
    void testAgentPromptWithReadFileTool() throws Exception {
        // Create a test file in the workspace
        var testFile = workDir.resolve("greeting.txt");
        Files.writeString(testFile, "Hello from the test file!");

        try (var channel = connectToSocket()) {
            String sessionId = createSession(channel, 1);

            // Enqueue responses:
            // 1st call: LLM requests read_file tool
            mockLlm.enqueueResponse(MockLlmClient.toolUseResponse(
                    "Let me read that file for you.",
                    "toolu_001", "read_file",
                    "{\"file_path\":\"greeting.txt\"}"
            ));
            // 2nd call: LLM returns final text after seeing tool result
            mockLlm.enqueueResponse(MockLlmClient.textResponse(
                    "The file greeting.txt contains: Hello from the test file!"));

            var promptParams = objectMapper.createObjectNode();
            promptParams.put("sessionId", sessionId);
            promptParams.put("prompt", "Read greeting.txt");

            var result = sendPromptAndCollectEvents(channel, promptParams, 2);

            // Verify tool use notification was sent
            boolean hasToolUse = result.notifications().stream()
                    .anyMatch(n -> "stream.tool_use".equals(n.get("method").asText()));
            assertThat(hasToolUse).isTrue();

            // Verify the final response includes the file content
            var response = result.finalResponse();
            assertThat(response.has("result")).isTrue();
            assertThat(response.get("result").get("response").asText())
                    .contains("Hello from the test file!");

            // Verify LLM was called twice (tool use → tool result → final response)
            assertThat(mockLlm.capturedRequests()).hasSize(2);

            // The second request should contain the tool result in the messages
            var secondRequest = mockLlm.capturedRequests().get(1);
            assertThat(secondRequest.messages().size()).isGreaterThan(1);

            destroySession(channel, sessionId, 3);
        }
    }

    @Test
    @Order(7)
    void testAgentPromptWithWriteFileTool() throws Exception {
        try (var channel = connectToSocket()) {
            String sessionId = createSession(channel, 1);

            // Enqueue responses:
            // 1st: LLM wants to write a file (write_file is WRITE level → needs permission)
            mockLlm.enqueueResponse(MockLlmClient.toolUseResponse(
                    "I'll create the file for you.",
                    "toolu_002", "write_file",
                    "{\"file_path\":\"hello.py\",\"content\":\"print('Hello World!')\"}"
            ));
            // 2nd: final text after tool result
            mockLlm.enqueueResponse(MockLlmClient.textResponse(
                    "Done! I created hello.py with a Hello World program."));

            var promptParams = objectMapper.createObjectNode();
            promptParams.put("sessionId", sessionId);
            promptParams.put("prompt", "Create a hello world Python script");

            // Send the request and handle permission prompts (approve all)
            var result = sendPromptAndHandlePermissions(channel, promptParams, 2, true);

            // Verify the file was actually created
            var createdFile = workDir.resolve("hello.py");
            assertThat(Files.exists(createdFile)).isTrue();
            assertThat(Files.readString(createdFile)).isEqualTo("print('Hello World!')");

            // Verify the final response
            assertThat(result.finalResponse().has("result")).isTrue();
            assertThat(result.finalResponse().get("result").get("response").asText())
                    .contains("hello.py");

            destroySession(channel, sessionId, 3);
        }
    }

    @Test
    @Order(8)
    void testAgentPromptWithPermissionDenied() throws Exception {
        try (var channel = connectToSocket()) {
            String sessionId = createSession(channel, 1);

            // LLM wants to run bash (EXECUTE level → needs permission)
            mockLlm.enqueueResponse(MockLlmClient.toolUseResponse(
                    "Let me run that command.",
                    "toolu_003", "bash",
                    "{\"command\":\"echo hello\"}"
            ));
            // After the denied tool result, LLM responds with text
            mockLlm.enqueueResponse(MockLlmClient.textResponse(
                    "I was unable to execute the command because permission was denied."));

            var promptParams = objectMapper.createObjectNode();
            promptParams.put("sessionId", sessionId);
            promptParams.put("prompt", "Run echo hello");

            // Deny the permission
            var result = sendPromptAndHandlePermissions(channel, promptParams, 2, false);

            // The response should indicate the agent handled the denial
            assertThat(result.finalResponse().has("result")).isTrue();
            assertThat(result.finalResponse().get("result").get("response").asText())
                    .contains("denied");

            destroySession(channel, sessionId, 3);
        }
    }

    @Test
    @Order(9)
    void testMultiTurnConversation() throws Exception {
        try (var channel = connectToSocket()) {
            String sessionId = createSession(channel, 1);

            // Turn 1: simple text response
            mockLlm.enqueueResponse(MockLlmClient.textResponse("Hello! How can I help you?"));
            var turn1Params = objectMapper.createObjectNode();
            turn1Params.put("sessionId", sessionId);
            turn1Params.put("prompt", "Hi there");
            var turn1 = sendPromptAndCollectEvents(channel, turn1Params, 2);
            assertThat(turn1.finalResponse().get("result").get("response").asText())
                    .isEqualTo("Hello! How can I help you?");

            // Turn 2: another text response
            mockLlm.enqueueResponse(MockLlmClient.textResponse("Sure, I can help with Java code."));
            var turn2Params = objectMapper.createObjectNode();
            turn2Params.put("sessionId", sessionId);
            turn2Params.put("prompt", "Can you help me with Java?");
            var turn2 = sendPromptAndCollectEvents(channel, turn2Params, 3);
            assertThat(turn2.finalResponse().get("result").get("response").asText())
                    .isEqualTo("Sure, I can help with Java code.");

            // Verify conversation history was preserved: 2nd LLM call should have
            // messages from both turns
            var requests = mockLlm.capturedRequests();
            assertThat(requests).hasSize(2);
            // 2nd request should have messages from 1st turn + 2nd turn user message
            var secondRequest = requests.get(1);
            assertThat(secondRequest.messages().size()).isGreaterThanOrEqualTo(3);

            destroySession(channel, sessionId, 4);
        }
    }

    @Test
    @Order(10)
    void testSessionApprovalRemember() throws Exception {
        try (var channel = connectToSocket()) {
            String sessionId = createSession(channel, 1);

            // First tool call: approve with remember=true
            mockLlm.enqueueResponse(MockLlmClient.toolUseResponse(
                    "Writing file.",
                    "toolu_010", "write_file",
                    "{\"file_path\":\"test1.txt\",\"content\":\"one\"}"
            ));
            mockLlm.enqueueResponse(MockLlmClient.textResponse("Created test1.txt."));

            var params1 = objectMapper.createObjectNode();
            params1.put("sessionId", sessionId);
            params1.put("prompt", "Create test1.txt");
            sendPromptAndHandlePermissions(channel, params1, 2, true, true); // approve + remember

            // Verify session approval was recorded
            assertThat(permissionManager.hasSessionApproval("write_file")).isTrue();

            // Second tool call: should NOT prompt for permission (session-approved)
            mockLlm.enqueueResponse(MockLlmClient.toolUseResponse(
                    "Writing another file.",
                    "toolu_011", "write_file",
                    "{\"file_path\":\"test2.txt\",\"content\":\"two\"}"
            ));
            mockLlm.enqueueResponse(MockLlmClient.textResponse("Created test2.txt."));

            var params2 = objectMapper.createObjectNode();
            params2.put("sessionId", sessionId);
            params2.put("prompt", "Create test2.txt");

            // This should NOT trigger any permission.request notifications
            var result = sendPromptAndCollectEvents(channel, params2, 3);
            boolean hasPermissionRequest = result.notifications().stream()
                    .anyMatch(n -> "permission.request".equals(n.get("method").asText()));
            assertThat(hasPermissionRequest).isFalse();

            // Verify both files were created
            assertThat(Files.exists(workDir.resolve("test1.txt"))).isTrue();
            assertThat(Files.exists(workDir.resolve("test2.txt"))).isTrue();

            destroySession(channel, sessionId, 4);
        }
    }

    @Test
    @Order(11)
    void testReadFileAutoApproved() throws Exception {
        // read_file is READ level → auto-approved, no permission prompt
        var testFile = workDir.resolve("auto.txt");
        Files.writeString(testFile, "auto content");

        try (var channel = connectToSocket()) {
            String sessionId = createSession(channel, 1);

            mockLlm.enqueueResponse(MockLlmClient.toolUseResponse(
                    "Reading the file.",
                    "toolu_020", "read_file",
                    "{\"file_path\":\"auto.txt\"}"
            ));
            mockLlm.enqueueResponse(MockLlmClient.textResponse("File content is: auto content"));

            var params = objectMapper.createObjectNode();
            params.put("sessionId", sessionId);
            params.put("prompt", "Read auto.txt");

            // No permission prompts expected for read_file
            var result = sendPromptAndCollectEvents(channel, params, 2);
            boolean hasPermissionRequest = result.notifications().stream()
                    .anyMatch(n -> "permission.request".equals(n.get("method").asText()));
            assertThat(hasPermissionRequest).isFalse();

            assertThat(result.finalResponse().has("result")).isTrue();
            destroySession(channel, sessionId, 3);
        }
    }

    // -- Helper methods --

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

    /**
     * Sends a JSON-RPC request and waits for the response.
     * For non-streaming methods only.
     */
    private JsonNode sendAndReceive(SocketChannel channel, String method,
                                    JsonNode params, int requestId) throws Exception {
        sendRequest(channel, method, params, requestId);
        return readMessage(channel);
    }

    /**
     * Sends an agent.prompt request and collects all streaming notifications
     * until the final response arrives.
     * Does NOT handle permission requests (use for auto-approved tools or text-only).
     */
    private PromptResult sendPromptAndCollectEvents(SocketChannel channel,
                                                    JsonNode params, int requestId) throws Exception {
        sendRequest(channel, "agent.prompt", params, requestId);
        return collectEventsUntilResponse(channel, false, false);
    }

    /**
     * Sends an agent.prompt request, handles permission requests, and collects events.
     */
    private PromptResult sendPromptAndHandlePermissions(SocketChannel channel,
                                                        JsonNode params, int requestId,
                                                        boolean approve) throws Exception {
        return sendPromptAndHandlePermissions(channel, params, requestId, approve, false);
    }

    private PromptResult sendPromptAndHandlePermissions(SocketChannel channel,
                                                        JsonNode params, int requestId,
                                                        boolean approve, boolean remember) throws Exception {
        sendRequest(channel, "agent.prompt", params, requestId);
        return collectEventsUntilResponse(channel, approve, remember);
    }

    /**
     * Reads messages from the channel until a response (has "id" field) is received.
     * Handles permission.request notifications by sending permission.response messages.
     */
    private PromptResult collectEventsUntilResponse(SocketChannel channel,
                                                    boolean approvePermissions,
                                                    boolean rememberPermissions) throws Exception {
        var notifications = new ArrayList<JsonNode>();
        var timeout = System.currentTimeMillis() + 10_000; // 10s timeout

        while (System.currentTimeMillis() < timeout) {
            var msg = readMessage(channel);
            if (msg == null) {
                throw new IOException("Connection closed while waiting for response");
            }

            // Check if this is the final response (has "id" field)
            if (msg.has("id") && !msg.get("id").isNull()) {
                return new PromptResult(notifications, msg);
            }

            // Check if it's a permission request
            if (msg.has("method") && "permission.request".equals(msg.get("method").asText())) {
                var permParams = msg.get("params");
                var permRequestId = permParams.get("requestId").asText();

                // Send permission response
                var responseNode = objectMapper.createObjectNode();
                responseNode.put("method", "permission.response");
                var responseParams = objectMapper.createObjectNode();
                responseParams.put("requestId", permRequestId);
                responseParams.put("approved", approvePermissions);
                responseParams.put("remember", rememberPermissions);
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

    /**
     * Reads a single newline-delimited JSON message from the channel.
     */
    private JsonNode readMessage(SocketChannel channel) throws Exception {
        var buffer = ByteBuffer.allocate(65536);
        var lineBuilder = new StringBuilder();

        while (true) {
            int newlineIdx = lineBuilder.indexOf("\n");
            if (newlineIdx != -1) {
                var line = lineBuilder.substring(0, newlineIdx).trim();
                lineBuilder.delete(0, newlineIdx + 1);
                if (!line.isEmpty()) {
                    return objectMapper.readTree(line);
                }
            }

            buffer.clear();
            int bytesRead = channel.read(buffer);
            if (bytesRead == -1) return null;
            if (bytesRead == 0) continue;

            buffer.flip();
            lineBuilder.append(StandardCharsets.UTF_8.decode(buffer));
        }
    }

    /**
     * Result of an agent.prompt request, containing streaming notifications
     * and the final JSON-RPC response.
     */
    record PromptResult(List<JsonNode> notifications, JsonNode finalResponse) {}
}
