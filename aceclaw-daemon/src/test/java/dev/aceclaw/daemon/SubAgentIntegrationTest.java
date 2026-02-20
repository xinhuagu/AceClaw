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
 * Integration tests for sub-agent delegation via the TaskTool.
 *
 * <p>Uses MockLlmClient with FIFO queue to sequence parent and sub-agent responses.
 * The parent and sub-agent share the same LlmClient (as in production).
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class SubAgentIntegrationTest {

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
        socketPath = tempDir.resolve("subagent-test.sock");
        workDir = tempDir.resolve("workspace");
        Files.createDirectories(workDir);

        objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

        sessionManager = new SessionManager();
        var router = new RequestRouter(sessionManager, objectMapper);
        var connectionBridge = new ConnectionBridge(router, objectMapper);

        mockLlm = new MockLlmClient();

        // Tool registry with temp working directory
        var toolRegistry = new ToolRegistry();
        toolRegistry.register(new ReadFileTool(workDir));
        toolRegistry.register(new WriteFileTool(workDir));
        toolRegistry.register(new GlobSearchTool(workDir));
        toolRegistry.register(new GrepSearchTool(workDir));

        // Permission manager — auto-approve READ, prompt for WRITE/EXECUTE
        // Created early because sub-agent permission checker references it.
        permissionManager = new PermissionManager(new DefaultPermissionPolicy());

        // Sub-agent infrastructure (with permission checker)
        var agentTypeRegistry = AgentTypeRegistry.withBuiltins();
        var readOnlyTools = java.util.Set.of("read_file", "glob", "grep");
        var subAgentPermChecker = new SubAgentPermissionChecker(
                readOnlyTools, permissionManager::hasSessionApproval);
        var subAgentRunner = new SubAgentRunner(
                mockLlm, toolRegistry, "mock-model", workDir, 4096, 0,
                subAgentPermChecker, null);
        toolRegistry.register(new TaskTool(subAgentRunner, agentTypeRegistry));

        // Agent loop + handler
        var agentLoop = new StreamingAgentLoop(
                mockLlm, toolRegistry, "mock-model", "You are a test agent with sub-agent support.");
        var agentHandler = new StreamingAgentHandler(
                sessionManager, agentLoop, toolRegistry, permissionManager, objectMapper);
        agentHandler.setLlmConfig(mockLlm, "mock-model", "You are a test agent with sub-agent support.");
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
        permissionManager.clearSessionApprovals();
        channelLineBuffer.setLength(0);
    }

    @Test
    @Order(1)
    void testExploreSubAgentDelegation() throws Exception {
        // Create a test file for the sub-agent to find
        Files.writeString(workDir.resolve("AuthService.java"),
                "public class AuthService { public void login() {} }");

        try (var channel = connectToSocket()) {
            String sessionId = createSession(channel, 1);

            // FIFO queue sequencing:
            // 1. Parent response: tool_use for "task" (explore agent)
            mockLlm.enqueueResponse(MockLlmClient.toolUseResponse(
                    "Let me search the codebase for authentication files.",
                    "toolu_task_001", "task",
                    "{\"agent_type\":\"explore\",\"prompt\":\"Find all authentication-related files and report their locations and key classes.\"}"
            ));
            // 2. Sub-agent response: text (consumed by SubAgentRunner)
            mockLlm.enqueueResponse(MockLlmClient.textResponse(
                    "Found AuthService.java at workspace root. Contains class AuthService with login() method."));
            // 3. Parent follow-up: text (after receiving sub-agent result)
            mockLlm.enqueueResponse(MockLlmClient.textResponse(
                    "I found the authentication files. AuthService.java contains the AuthService class with a login() method."));

            var promptParams = objectMapper.createObjectNode();
            promptParams.put("sessionId", sessionId);
            promptParams.put("prompt", "Find all auth-related files in this project");

            var result = sendPromptAndCollectEvents(channel, promptParams, 2);

            // Verify the parent got the sub-agent's result and produced a final response
            var response = result.finalResponse();
            assertThat(response.has("result")).isTrue();
            assertThat(response.get("result").get("response").asText())
                    .contains("AuthService");

            // Verify all 3 mock responses were consumed (parent tool_use + sub-agent + parent follow-up)
            assertThat(mockLlm.capturedRequests()).hasSize(3);

            destroySession(channel, sessionId, 3);
        }
    }

    @Test
    @Order(2)
    void testUnknownAgentTypeReturnsError() throws Exception {
        try (var channel = connectToSocket()) {
            String sessionId = createSession(channel, 1);

            // Parent requests a non-existent agent type
            mockLlm.enqueueResponse(MockLlmClient.toolUseResponse(
                    "Let me delegate this task.",
                    "toolu_task_002", "task",
                    "{\"agent_type\":\"nonexistent\",\"prompt\":\"Do something.\"}"
            ));
            // After the error tool result, parent responds with text
            mockLlm.enqueueResponse(MockLlmClient.textResponse(
                    "The task delegation failed because 'nonexistent' is not a valid agent type."));

            var promptParams = objectMapper.createObjectNode();
            promptParams.put("sessionId", sessionId);
            promptParams.put("prompt", "Use a nonexistent agent");

            var result = sendPromptAndCollectEvents(channel, promptParams, 2);

            // Verify the parent handled the error
            var response = result.finalResponse();
            assertThat(response.has("result")).isTrue();
            assertThat(response.get("result").get("response").asText())
                    .contains("nonexistent");

            // Verify only 2 requests: parent tool_use (→ error) + parent follow-up
            // The sub-agent was never spawned so only 2 LLM calls
            assertThat(mockLlm.capturedRequests()).hasSize(2);

            destroySession(channel, sessionId, 3);
        }
    }

    @Test
    @Order(3)
    void testSubAgentCannotNest() throws Exception {
        try (var channel = connectToSocket()) {
            String sessionId = createSession(channel, 1);

            // Parent delegates to general sub-agent
            mockLlm.enqueueResponse(MockLlmClient.toolUseResponse(
                    "Delegating to general agent.",
                    "toolu_task_003", "task",
                    "{\"agent_type\":\"general\",\"prompt\":\"Try to use the task tool.\"}"
            ));
            // Sub-agent tries to use "task" tool (which should not be available)
            // The sub-agent will get back a text response since "task" is filtered out
            mockLlm.enqueueResponse(MockLlmClient.textResponse(
                    "I completed the general task. The task tool is not available to me."));
            // Parent follow-up
            mockLlm.enqueueResponse(MockLlmClient.textResponse(
                    "The sub-agent completed its work successfully."));

            var promptParams = objectMapper.createObjectNode();
            promptParams.put("sessionId", sessionId);
            promptParams.put("prompt", "Run a general agent that tries nesting");

            var result = sendPromptAndCollectEvents(channel, promptParams, 2);

            var response = result.finalResponse();
            assertThat(response.has("result")).isTrue();

            // Verify the sub-agent's LLM request did NOT include "task" in its tools
            // Request #1 = parent, Request #2 = sub-agent, Request #3 = parent follow-up
            assertThat(mockLlm.capturedRequests()).hasSize(3);
            var subAgentRequest = mockLlm.capturedRequests().get(1);
            if (subAgentRequest.tools() != null) {
                boolean hasTaskTool = subAgentRequest.tools().stream()
                        .anyMatch(t -> "task".equals(t.name()));
                assertThat(hasTaskTool)
                        .as("Sub-agent should not have access to 'task' tool")
                        .isFalse();
            }

            destroySession(channel, sessionId, 3);
        }
    }

    @Test
    @Order(4)
    void testSubAgentWriteDeniedWithoutSessionApproval() throws Exception {
        try (var channel = connectToSocket()) {
            String sessionId = createSession(channel, 1);

            // Parent delegates to "general" sub-agent
            mockLlm.enqueueResponse(MockLlmClient.toolUseResponse(
                    "Let me delegate this write task.",
                    "toolu_task_004", "task",
                    "{\"agent_type\":\"general\",\"prompt\":\"Write a file called test.txt with content hello.\"}"
            ));
            // Sub-agent tries to use write_file — should get "Permission denied" tool result
            // because write_file is not read-only and not session-approved.
            // The sub-agent receives the error and responds with text.
            mockLlm.enqueueResponse(MockLlmClient.toolUseResponse(
                    "I will write the file.",
                    "toolu_write_001", "write_file",
                    "{\"file_path\":\"test.txt\",\"content\":\"hello\"}"
            ));
            // After permission denied tool result, sub-agent gives up
            mockLlm.enqueueResponse(MockLlmClient.textResponse(
                    "I cannot write files — permission was denied."));
            // Parent follow-up after receiving sub-agent result
            mockLlm.enqueueResponse(MockLlmClient.textResponse(
                    "The sub-agent was unable to write the file due to permission restrictions."));

            var promptParams = objectMapper.createObjectNode();
            promptParams.put("sessionId", sessionId);
            promptParams.put("prompt", "Write test.txt via a sub-agent");

            var result = sendPromptAndCollectEvents(channel, promptParams, 2);

            var response = result.finalResponse();
            assertThat(response.has("result")).isTrue();
            assertThat(response.get("result").get("response").asText())
                    .containsIgnoringCase("permission");

            // Verify the file was NOT created
            assertThat(workDir.resolve("test.txt")).doesNotExist();

            destroySession(channel, sessionId, 3);
        }
    }

    // -- Helper methods (same pattern as DaemonIntegrationTest) --

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
        var timeoutDeadline = System.currentTimeMillis() + 15_000; // 15s for sub-agent tests

        while (System.currentTimeMillis() < timeoutDeadline) {
            var msg = readMessage(channel);
            if (msg == null) {
                throw new IOException("Connection closed while waiting for response");
            }

            if (msg.has("id") && !msg.get("id").isNull()) {
                return new PromptResult(notifications, msg);
            }

            // Handle permission requests (auto-approve for sub-agent tests)
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
