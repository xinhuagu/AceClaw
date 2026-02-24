package dev.aceclaw.daemon;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import dev.aceclaw.core.agent.*;
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
 * Integration tests for the skill system via UDS.
 *
 * <p>Uses MockLlmClient with FIFO queue to sequence parent and sub-agent responses.
 * Tests cover inline skill execution, fork skill execution, and unknown skill errors.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class SkillIntegrationTest {

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
        socketPath = tempDir.resolve("skill-test.sock");
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

        // Create test skills in the workspace
        createInlineSkill(workDir);
        createForkSkill(workDir);

        // Sub-agent infrastructure (required for fork-mode skills)
        var agentTypeRegistry = AgentTypeRegistry.withBuiltins();
        var subAgentRunner = new SubAgentRunner(
                mockLlm, toolRegistry, "mock-model", workDir, 4096, 0);
        toolRegistry.register(new TaskTool(subAgentRunner, agentTypeRegistry));

        // Skill infrastructure
        var skillRegistry = SkillRegistry.load(workDir);
        var contentResolver = new SkillContentResolver(workDir);
        var skillTool = new SkillTool(skillRegistry, contentResolver, subAgentRunner);
        toolRegistry.register(skillTool);

        // Permission manager — auto-approve all
        permissionManager = new PermissionManager(new DefaultPermissionPolicy(true));

        // Agent loop + handler
        var agentLoop = new StreamingAgentLoop(
                mockLlm, toolRegistry, "mock-model",
                "You are a test agent with skill support.");
        var agentHandler = new StreamingAgentHandler(
                sessionManager, agentLoop, toolRegistry, permissionManager, objectMapper);
        agentHandler.setLlmConfig(mockLlm, "mock-model",
                "You are a test agent with skill support.");
        agentHandler.register(router);

        udsListener = new UdsListener(socketPath, connectionBridge);
        udsListener.start();

        TestAwait.waitForSocketReady(socketPath, 5000);
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
    void testInlineSkillExecution() throws Exception {
        try (var channel = connectToSocket()) {
            String sessionId = createSession(channel, 1);

            // 1. Parent response: invoke the inline skill tool
            mockLlm.enqueueResponse(MockLlmClient.toolUseResponse(
                    "Let me use the review skill.",
                    "toolu_skill_001", "skill",
                    "{\"name\":\"review\",\"arguments\":\"src/main\"}"
            ));
            // 2. After receiving the skill content as tool result, parent responds
            mockLlm.enqueueResponse(MockLlmClient.textResponse(
                    "I've reviewed the code following the skill instructions for src/main."));

            var promptParams = objectMapper.createObjectNode();
            promptParams.put("sessionId", sessionId);
            promptParams.put("prompt", "Review the src/main directory");

            var result = sendPromptAndCollectEvents(channel, promptParams, 2);

            var response = result.finalResponse();
            assertThat(response.has("result")).isTrue();
            assertThat(response.get("result").get("response").asText())
                    .contains("reviewed");

            // Verify only 2 LLM calls: parent tool_use + parent follow-up
            // (inline skill does NOT make an LLM call — it returns resolved content directly)
            assertThat(mockLlm.capturedRequests()).hasSize(2);

            destroySession(channel, sessionId, 3);
        }
    }

    @Test
    @Order(2)
    void testForkSkillExecution() throws Exception {
        try (var channel = connectToSocket()) {
            String sessionId = createSession(channel, 1);

            // 1. Parent invokes the fork skill
            mockLlm.enqueueResponse(MockLlmClient.toolUseResponse(
                    "I'll deploy using the skill.",
                    "toolu_skill_002", "skill",
                    "{\"name\":\"deploy\",\"arguments\":\"staging\"}"
            ));
            // 2. Sub-agent (forked skill) response
            mockLlm.enqueueResponse(MockLlmClient.textResponse(
                    "Deployed to staging environment successfully."));
            // 3. Parent follow-up
            mockLlm.enqueueResponse(MockLlmClient.textResponse(
                    "The deployment to staging completed."));

            var promptParams = objectMapper.createObjectNode();
            promptParams.put("sessionId", sessionId);
            promptParams.put("prompt", "Deploy to staging");

            var result = sendPromptAndCollectEvents(channel, promptParams, 2);

            var response = result.finalResponse();
            assertThat(response.has("result")).isTrue();
            assertThat(response.get("result").get("response").asText())
                    .contains("staging");

            // Verify 3 LLM calls: parent tool_use + sub-agent + parent follow-up
            assertThat(mockLlm.capturedRequests()).hasSize(3);

            // Verify sub-agent start/end notifications were sent
            var methods = result.notifications().stream()
                    .filter(n -> n.has("method"))
                    .map(n -> n.get("method").asText())
                    .toList();

            assertThat(methods).contains("stream.subagent.start");
            assertThat(methods).contains("stream.subagent.end");

            // Verify the subagent.start notification identifies the skill
            var startNotif = result.notifications().stream()
                    .filter(n -> "stream.subagent.start".equals(n.path("method").asText()))
                    .findFirst()
                    .orElseThrow();
            assertThat(startNotif.path("params").path("agentType").asText())
                    .isEqualTo("skill:deploy");

            destroySession(channel, sessionId, 3);
        }
    }

    @Test
    @Order(3)
    void testUnknownSkillReturnsError() throws Exception {
        try (var channel = connectToSocket()) {
            String sessionId = createSession(channel, 1);

            // Parent tries to invoke a non-existent skill
            mockLlm.enqueueResponse(MockLlmClient.toolUseResponse(
                    "Let me use the nonexistent skill.",
                    "toolu_skill_003", "skill",
                    "{\"name\":\"nonexistent\"}"
            ));
            // Parent handles the error
            mockLlm.enqueueResponse(MockLlmClient.textResponse(
                    "The skill 'nonexistent' is not available."));

            var promptParams = objectMapper.createObjectNode();
            promptParams.put("sessionId", sessionId);
            promptParams.put("prompt", "Use the nonexistent skill");

            var result = sendPromptAndCollectEvents(channel, promptParams, 2);

            var response = result.finalResponse();
            assertThat(response.has("result")).isTrue();
            assertThat(response.get("result").get("response").asText())
                    .contains("nonexistent");

            // Only 2 LLM calls (no sub-agent spawned for the error case)
            assertThat(mockLlm.capturedRequests()).hasSize(2);

            destroySession(channel, sessionId, 3);
        }
    }

    // -- Skill creation helpers --

    private static void createInlineSkill(Path projectDir) throws IOException {
        var skillDir = projectDir.resolve(".aceclaw/skills/review");
        Files.createDirectories(skillDir);
        Files.writeString(skillDir.resolve("SKILL.md"), """
                ---
                description: "Review code changes"
                context: inline
                ---

                # Code Review Skill

                Review the code in the $ARGUMENTS directory.

                Guidelines:
                - Check for bugs and logic errors
                - Verify naming conventions
                - Look for security vulnerabilities
                """);
    }

    private static void createForkSkill(Path projectDir) throws IOException {
        var skillDir = projectDir.resolve(".aceclaw/skills/deploy");
        Files.createDirectories(skillDir);
        Files.writeString(skillDir.resolve("SKILL.md"), """
                ---
                description: "Deploy to environment"
                argument-hint: "<environment>"
                context: fork
                allowed-tools: [bash, read_file]
                max-turns: 5
                ---

                # Deploy Skill

                Deploy the application to $1 environment.

                Steps:
                1. Verify build
                2. Push to registry
                3. Apply manifests
                """);
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
        var timeoutDeadline = System.currentTimeMillis() + 15_000;

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
