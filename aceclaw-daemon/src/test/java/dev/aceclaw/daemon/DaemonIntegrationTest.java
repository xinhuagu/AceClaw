package dev.aceclaw.daemon;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import dev.aceclaw.core.agent.Tool;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import dev.aceclaw.core.agent.StreamingAgentLoop;
import dev.aceclaw.core.agent.ToolRegistry;
import dev.aceclaw.core.llm.LlmResponse;
import dev.aceclaw.core.planner.PlanCheckpoint;
import dev.aceclaw.core.planner.PlanCheckpointStore;
import dev.aceclaw.core.planner.PlanStatus;
import dev.aceclaw.core.planner.PlannedStep;
import dev.aceclaw.core.planner.StepResult;
import dev.aceclaw.core.planner.StepStatus;
import dev.aceclaw.core.planner.TaskPlan;
import dev.aceclaw.memory.CandidateKind;
import dev.aceclaw.memory.CandidateState;
import dev.aceclaw.memory.CandidateStateMachine;
import dev.aceclaw.memory.CandidateStore;
import dev.aceclaw.memory.MemoryEntry;
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
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

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
@Timeout(value = 60, unit = TimeUnit.SECONDS)
@DisabledOnOs(OS.WINDOWS)
class DaemonIntegrationTest {

    @TempDir
    static Path tempDir;

    private static Path socketPath;
    private static Path workDir;
    private static Path checkpointDir;
    private static MockLlmClient mockLlm;
    private static SessionManager sessionManager;
    private static PermissionManager permissionManager;
    private static UdsListener udsListener;
    private static ObjectMapper objectMapper;
    private static CandidateStore candidateStore;
    private static String antiPatternCandidateId;
    private static String antiPatternRuleId;
    private static StreamingAgentHandler agentHandlerRef;

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
        agentHandlerRef = agentHandler;

        // Wire plan checkpoint store for checkpoint/resume integration tests
        checkpointDir = tempDir.resolve("checkpoints").resolve("plans");
        Files.createDirectories(checkpointDir);
        var planCheckpointStore = new FilePlanCheckpointStore(checkpointDir, objectMapper);
        agentHandler.setPlanCheckpointStore(planCheckpointStore);

        candidateStore = createPromotedAntiPatternCandidateStore();
        agentHandler.setCandidateStore(candidateStore);
        seedAntiPatternFeedback(workDir, antiPatternRuleId);
        agentHandler.setAntiPatternGateFeedbackStore(new AntiPatternGateFeedbackStore(workDir));
        agentHandler.register(router);
        router.register("antiPatternGate.override.set", params -> {
            if (params == null || !params.has("sessionId")) {
                throw new IllegalArgumentException("Missing required parameter: sessionId");
            }
            if (!params.has("tool")) {
                throw new IllegalArgumentException("Missing required parameter: tool");
            }
            String sessionId = params.get("sessionId").asText();
            String tool = params.get("tool").asText();
            long ttlSeconds = params.has("ttlSeconds") ? Math.max(1L, params.get("ttlSeconds").asLong()) : 300L;
            String reason = params.has("reason") ? params.get("reason").asText() : "manual override";
            agentHandler.setAntiPatternGateOverride(sessionId, tool, ttlSeconds, reason);
            var status = agentHandler.getAntiPatternGateOverride(sessionId, tool);
            var result = objectMapper.createObjectNode();
            result.put("sessionId", status.sessionId());
            result.put("tool", status.tool());
            result.put("active", status.active());
            result.put("ttlSecondsRemaining", status.ttlSecondsRemaining());
            result.put("reason", status.reason());
            return result;
        });
        router.register("antiPatternGate.override.get", params -> {
            if (params == null || !params.has("sessionId")) {
                throw new IllegalArgumentException("Missing required parameter: sessionId");
            }
            if (!params.has("tool")) {
                throw new IllegalArgumentException("Missing required parameter: tool");
            }
            String sessionId = params.get("sessionId").asText();
            String tool = params.get("tool").asText();
            var status = agentHandler.getAntiPatternGateOverride(sessionId, tool);
            var result = objectMapper.createObjectNode();
            result.put("sessionId", status.sessionId());
            result.put("tool", status.tool());
            result.put("active", status.active());
            result.put("ttlSecondsRemaining", status.ttlSecondsRemaining());
            result.put("reason", status.reason());
            return result;
        });
        router.register("antiPatternGate.override.clear", params -> {
            if (params == null || !params.has("sessionId")) {
                throw new IllegalArgumentException("Missing required parameter: sessionId");
            }
            if (!params.has("tool")) {
                throw new IllegalArgumentException("Missing required parameter: tool");
            }
            String sessionId = params.get("sessionId").asText();
            String tool = params.get("tool").asText();
            boolean cleared = agentHandler.clearAntiPatternGateOverride(sessionId, tool);
            var result = objectMapper.createObjectNode();
            result.put("sessionId", sessionId);
            result.put("tool", tool);
            result.put("cleared", cleared);
            return result;
        });

        // Start UDS listener
        udsListener = new UdsListener(socketPath, connectionBridge);
        udsListener.start();

        // Poll for socket readiness instead of blind sleep
        long deadline = System.currentTimeMillis() + 5000;
        while (!Files.exists(socketPath) && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        if (!Files.exists(socketPath)) {
            throw new IllegalStateException("UDS listener did not create socket within 5s");
        }
        // Brief pause for the listener to start accepting connections
        Thread.sleep(50);
    }

    @BeforeEach
    void cleanupCheckpointFiles() throws Exception {
        if (checkpointDir != null && Files.isDirectory(checkpointDir)) {
            try (var files = Files.list(checkpointDir)) {
                files.filter(p -> p.toString().endsWith(".checkpoint.json"))
                        .forEach(p -> { try { Files.deleteIfExists(p); } catch (IOException ignored) {} });
            }
        }
    }

    @AfterAll
    static void stopDaemon() {
        if (udsListener != null) {
            udsListener.stop();
        }
    }

    private static CandidateStore createPromotedAntiPatternCandidateStore() throws Exception {
        var cfg = new CandidateStateMachine.Config(1, 0.3, 1.0, 10, java.util.Set.of());
        var store = new CandidateStore(tempDir.resolve("anti-pattern-store"), cfg);
        store.load();
        var t0 = Instant.parse("2026-02-26T00:00:00Z");
        for (int i = 0; i < 3; i++) {
            store.upsert(new CandidateStore.CandidateObservation(
                    MemoryEntry.Category.ANTI_PATTERN,
                    CandidateKind.ANTI_PATTERN,
                    "Avoid x_anti_pattern_rollback_probe writes due to recurring errors",
                    "write_file",
                    List.of("anti-pattern", "write_file", "x_anti_pattern_rollback_probe"),
                    0.95,
                    0,
                    1,
                    "seed:" + i,
                    t0.plusSeconds(i)));
        }
        var candidate = store.all().getFirst();
        antiPatternCandidateId = candidate.id();
        while (candidate.evidenceCount() < 3) {
            store.recordOutcome(antiPatternCandidateId, new CandidateStore.CandidateOutcome(
                    false, false, false, "seed-outcome", "seed", t0, null));
            candidate = store.byId(antiPatternCandidateId).orElseThrow();
        }
        antiPatternRuleId = "candidate:" + antiPatternCandidateId;
        var promoted = store.transition(antiPatternCandidateId, CandidateState.PROMOTED, "test seed");
        if (promoted.isEmpty()) {
            store.forceTransition(antiPatternCandidateId, CandidateState.PROMOTED,
                    "test seed force", "TEST_FORCE_PROMOTE");
        }
        var finalState = store.byId(antiPatternCandidateId).orElseThrow().state();
        if (finalState != CandidateState.PROMOTED) {
            throw new IllegalStateException("Failed to seed promoted anti-pattern candidate for integration test");
        }
        return store;
    }

    private static void seedAntiPatternFeedback(Path root, String ruleId) throws Exception {
        Path feedbackPath = root.resolve(".aceclaw/metrics/continuous-learning/anti-pattern-gate-feedback.json");
        Files.createDirectories(feedbackPath.getParent());
        String json = """
                [
                  {
                    "ruleId": "%s",
                    "blockedCount": 3,
                    "falsePositiveCount": 1,
                    "updatedAt": "2026-02-26T00:00:00Z"
                  }
                ]
                """.formatted(ruleId);
        Files.writeString(feedbackPath, json);
    }

    private static void resetAntiPatternGateFixtures() throws Exception {
        if (candidateStore != null && antiPatternCandidateId != null) {
            candidateStore.forceTransition(
                    antiPatternCandidateId,
                    CandidateState.PROMOTED,
                    "test reset",
                    "TEST_RESET_PROMOTE");
        }
        if (workDir != null && antiPatternRuleId != null) {
            seedAntiPatternFeedback(workDir, antiPatternRuleId);
        }
    }

    /**
     * Shared line buffer for reading from the socket channel.
     * Must be instance-scoped so that data read from the channel in one
     * {@link #readMessage} call but not yet consumed is available to the next call.
     * Without this, messages coalesced into a single {@code channel.read()} would be lost.
     */
    private final StringBuilder channelLineBuffer = new StringBuilder();

    @BeforeEach
    void resetMock() {
        mockLlm.reset();
        permissionManager.clearSessionApprovals();
        channelLineBuffer.setLength(0);
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
            assertThat(result.get("version").asText()).isNotBlank();
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
            // canonicalizeProjectPath calls toRealPath(), which resolves symlinks
            // (e.g. /var → /private/var on macOS), so compare against the real path
            assertThat(createResult.get("project").asText()).isEqualTo(workDir.toRealPath().toString());

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
    @Order(99)
    void testSessionScopedRelativePathExecutionAcrossProjects() throws Exception {
        Path projectA = workDir.resolve("project-a");
        Path projectB = workDir.resolve("project-b");
        Files.createDirectories(projectA);
        Files.createDirectories(projectB);

        try (var channel = connectToSocket()) {
            String sessionA = createSession(channel, 1, projectA);
            String sessionB = createSession(channel, 2, projectB);

            // Session A: run relative-path bash command.
            mockLlm.enqueueResponse(MockLlmClient.toolUseResponse(
                    "Running in project A.",
                    "toolu_proj_a_001", "bash",
                    "{\"command\":\"pwd > marker-a.txt && echo ok-a\"}"
            ));
            mockLlm.enqueueResponse(MockLlmClient.textResponse("done-a"));

            var promptA = objectMapper.createObjectNode();
            promptA.put("sessionId", sessionA);
            promptA.put("prompt", "Create marker-a.txt via bash");
            var resultA = sendPromptAndHandlePermissions(channel, promptA, 3, true);
            assertThat(resultA.finalResponse().has("error")).isFalse();

            // Session B: run relative-path bash command.
            mockLlm.enqueueResponse(MockLlmClient.toolUseResponse(
                    "Running in project B.",
                    "toolu_proj_b_001", "bash",
                    "{\"command\":\"pwd > marker-b.txt && echo ok-b\"}"
            ));
            mockLlm.enqueueResponse(MockLlmClient.textResponse("done-b"));

            var promptB = objectMapper.createObjectNode();
            promptB.put("sessionId", sessionB);
            promptB.put("prompt", "Create marker-b.txt via bash");
            var resultB = sendPromptAndHandlePermissions(channel, promptB, 4, true);
            assertThat(resultB.finalResponse().has("error")).isFalse();

            Path markerA = projectA.resolve("marker-a.txt");
            Path markerB = projectB.resolve("marker-b.txt");
            assertThat(Files.exists(markerA)).isTrue();
            assertThat(Files.exists(markerB)).isTrue();

            String markerAContent = Files.readString(markerA).trim();
            String markerBContent = Files.readString(markerB).trim();
            Path markerAPath = Path.of(markerAContent).toRealPath();
            Path markerBPath = Path.of(markerBContent).toRealPath();
            assertThat(markerAPath).isEqualTo(projectA.toRealPath());
            assertThat(markerBPath).isEqualTo(projectB.toRealPath());

            destroySession(channel, sessionA, 5);
            destroySession(channel, sessionB, 6);
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

            // Verify tool completion notification is sent with duration metadata
            var toolCompleted = result.notifications().stream()
                    .filter(n -> "stream.tool_completed".equals(n.get("method").asText()))
                    .findFirst();
            assertThat(toolCompleted).isPresent();
            assertThat(toolCompleted.get().path("params").path("durationMs").asLong()).isGreaterThanOrEqualTo(0L);
            assertThat(toolCompleted.get().path("params").path("name").asText()).isEqualTo("read_file");

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
    void testAgentPromptWaitsForMcpInitBeforeBuildingRequestRegistry() throws Exception {
        var toolRegistry = extractToolRegistry(agentHandlerRef);
        var delayedMcpToolName = "mcp__demo__echo";
        var mcpReady = new CompletableFuture<Void>();
        agentHandlerRef.setMcpInitFuture(mcpReady);

        Thread.ofVirtual().start(() -> {
            try {
                Thread.sleep(100);
                toolRegistry.register(new Tool() {
                    @Override
                    public String name() {
                        return delayedMcpToolName;
                    }

                    @Override
                    public String description() {
                        return "Test MCP tool registered after daemon startup.";
                    }

                    @Override
                    public JsonNode inputSchema() {
                        return JsonNodeFactory.instance.objectNode().put("type", "object");
                    }

                    @Override
                    public ToolResult execute(String inputJson) {
                        return new ToolResult("mcp echo ok", false);
                    }
                });
                mcpReady.complete(null);
            } catch (Exception e) {
                mcpReady.completeExceptionally(e);
            }
        });

        try (var channel = connectToSocket()) {
            String sessionId = createSession(channel, 1);

            mockLlm.enqueueResponse(MockLlmClient.toolUseResponse(
                    "Using the MCP tool now.",
                    "toolu_mcp_001",
                    delayedMcpToolName,
                    "{}"
            ));
            mockLlm.enqueueResponse(MockLlmClient.textResponse(
                    "The delayed MCP tool executed successfully."));

            var promptParams = objectMapper.createObjectNode();
            promptParams.put("sessionId", sessionId);
            promptParams.put("prompt", "Use the delayed MCP echo tool.");

            var result = sendPromptAndHandlePermissions(channel, promptParams, 2, true);

            var response = result.finalResponse();
            assertThat(response.has("result")).isTrue();
            assertThat(response.get("result").get("response").asText())
                    .isEqualTo("The delayed MCP tool executed successfully.");

            var toolCompleted = result.notifications().stream()
                    .filter(n -> "stream.tool_completed".equals(n.get("method").asText()))
                    .findFirst();
            assertThat(toolCompleted).isPresent();
            assertThat(toolCompleted.get().path("params").path("name").asText()).isEqualTo(delayedMcpToolName);
            assertThat(toolCompleted.get().path("params").path("isError").asBoolean()).isFalse();

            var requests = mockLlm.capturedRequests();
            assertThat(requests).hasSize(2);
            assertThat(requests.getFirst().tools())
                    .extracting(def -> def.name())
                    .contains(delayedMcpToolName);

            destroySession(channel, sessionId, 3);
        } finally {
            agentHandlerRef.setMcpInitFuture(CompletableFuture.completedFuture(null));
        }
    }

    @Test
    @Order(8)
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
    @Order(9)
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

            // Denied tools should still emit stream.tool_completed so CLI clears running state
            var toolCompleted = result.notifications().stream()
                    .filter(n -> "stream.tool_completed".equals(n.get("method").asText()))
                    .findFirst();
            assertThat(toolCompleted).isPresent();
            assertThat(toolCompleted.get().path("params").path("name").asText()).isEqualTo("bash");
            assertThat(toolCompleted.get().path("params").path("isError").asBoolean()).isTrue();

            // The response should indicate the agent handled the denial
            assertThat(result.finalResponse().has("result")).isTrue();
            assertThat(result.finalResponse().get("result").get("response").asText())
                    .contains("denied");

            destroySession(channel, sessionId, 3);
        }
    }

    @Test
    @Order(10)
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

    @Test
    @Order(12)
    void testCancelDuringStreaming() throws Exception {
        try (var channel = connectToSocket()) {
            String sessionId = createSession(channel, 1);

            // Create a pausing stream session that blocks mid-delivery
            var arrivedLatch = new CountDownLatch(1);
            var continueLatch = new CountDownLatch(1);
            var pausingSession = MockLlmClient.pausingTextResponse(
                    "This is a long response that will be interrupted by cancel",
                    arrivedLatch, continueLatch);
            mockLlm.enqueueSession(pausingSession);

            // Send the agent.prompt request
            var promptParams = objectMapper.createObjectNode();
            promptParams.put("sessionId", sessionId);
            promptParams.put("prompt", "Tell me something long");
            sendRequest(channel, "agent.prompt", promptParams, 2);

            // Wait for the stream to arrive at the pause point (first half delivered)
            assertThat(arrivedLatch.await(5, TimeUnit.SECONDS))
                    .as("Stream should arrive at pause point within 5s")
                    .isTrue();

            // Send agent.cancel notification while the stream is paused
            var cancelParams = objectMapper.createObjectNode();
            cancelParams.put("sessionId", sessionId);
            sendNotification(channel, "agent.cancel", cancelParams);

            // Collect events until the final response
            var result = collectEventsUntilResponse(channel, false, false);

            // Verify the session was cancelled
            assertThat(pausingSession.wasCancelled()).isTrue();

            // Verify we got a stream.cancelled notification
            boolean hasCancelledNotification = result.notifications().stream()
                    .anyMatch(n -> "stream.cancelled".equals(n.get("method").asText()));
            assertThat(hasCancelledNotification).isTrue();

            // Verify the final response has the cancelled flag
            var response = result.finalResponse();
            assertThat(response.has("result")).isTrue();
            assertThat(response.get("result").get("cancelled").asBoolean(false)).isTrue();

            // Verify we got some partial text (first half was delivered)
            boolean hasTextDelta = result.notifications().stream()
                    .anyMatch(n -> "stream.text".equals(n.get("method").asText()));
            assertThat(hasTextDelta).isTrue();

            destroySession(channel, sessionId, 3);
        }
    }

    @Test
    @Order(13)
    void testCancelDuringToolExecution() throws Exception {
        // Create a test file so the tool has something to read
        var testFile = workDir.resolve("cancel-test.txt");
        Files.writeString(testFile, "Cancel test content");

        try (var channel = connectToSocket()) {
            String sessionId = createSession(channel, 1);

            // 1st call: LLM requests read_file (READ level, auto-approved)
            mockLlm.enqueueResponse(MockLlmClient.toolUseResponse(
                    "Let me read that file.",
                    "toolu_cancel_001", "read_file",
                    "{\"file_path\":\"cancel-test.txt\"}"
            ));
            // 2nd call: would normally be the follow-up, but the agent loop
            // should exit at checkpoint 3 before making this call.
            // Enqueue it anyway so we can verify it was NOT consumed.
            var arrivedLatch2 = new CountDownLatch(1);
            var continueLatch2 = new CountDownLatch(1);
            var pausingSession2 = MockLlmClient.pausingTextResponse(
                    "This response should not be delivered",
                    arrivedLatch2, continueLatch2);
            mockLlm.enqueueSession(pausingSession2);

            // Send the agent.prompt request
            var promptParams = objectMapper.createObjectNode();
            promptParams.put("sessionId", sessionId);
            promptParams.put("prompt", "Read cancel-test.txt");
            sendRequest(channel, "agent.prompt", promptParams, 2);

            // Wait briefly for the tool execution to start/complete,
            // then send cancel so it's caught at checkpoint 3
            Thread.sleep(200);
            var cancelParams = objectMapper.createObjectNode();
            cancelParams.put("sessionId", sessionId);
            sendNotification(channel, "agent.cancel", cancelParams);

            // Collect events until the final response
            var result = collectEventsUntilResponse(channel, false, false);

            // Verify we got the final response
            var response = result.finalResponse();
            assertThat(response.has("result")).isTrue();

            // The tool should have been executed (tool_use notification)
            boolean hasToolUse = result.notifications().stream()
                    .anyMatch(n -> "stream.tool_use".equals(n.get("method").asText()));
            assertThat(hasToolUse).isTrue();

            destroySession(channel, sessionId, 3);

            // Release the second pausing session to avoid leaked threads
            continueLatch2.countDown();
        }
    }

    @Test
    @Order(14)
    void testPermissionStaleResponseIgnoredUntilMatchingRequestArrives() throws Exception {
        try (var channel = connectToSocket()) {
            String sessionId = createSession(channel, 1);

            mockLlm.enqueueResponse(MockLlmClient.toolUseResponse(
                    "Writing file.",
                    "toolu_030", "write_file",
                    "{\"file_path\":\"stale-check.txt\",\"content\":\"ok\"}"
            ));
            mockLlm.enqueueResponse(MockLlmClient.textResponse("Created stale-check.txt."));

            var promptParams = objectMapper.createObjectNode();
            promptParams.put("sessionId", sessionId);
            promptParams.put("prompt", "Create stale-check.txt");
            sendRequest(channel, "agent.prompt", promptParams, 2);

            JsonNode finalResponse = null;
            boolean sentStale = false;
            boolean sentMatching = false;
            long timeoutDeadline = System.currentTimeMillis() + 8_000;

            while (System.currentTimeMillis() < timeoutDeadline) {
                var msg = readMessage(channel);
                if (msg == null) {
                    throw new IOException("Connection closed while waiting for response");
                }

                if (msg.has("id") && !msg.get("id").isNull()) {
                    finalResponse = msg;
                    break;
                }

                if (msg.has("method") && "permission.request".equals(msg.get("method").asText())) {
                    var permRequestId = msg.path("params").path("requestId").asText();

                    // Send a stale approval first (wrong requestId).
                    var staleParams = objectMapper.createObjectNode();
                    staleParams.put("requestId", "perm-stale-0000");
                    staleParams.put("approved", true);
                    staleParams.put("remember", false);
                    sendNotification(channel, "permission.response", staleParams);
                    sentStale = true;

                    // Then send the matching approval.
                    var okParams = objectMapper.createObjectNode();
                    okParams.put("requestId", permRequestId);
                    okParams.put("approved", true);
                    okParams.put("remember", false);
                    sendNotification(channel, "permission.response", okParams);
                    sentMatching = true;
                }
            }

            assertThat(finalResponse).as("Should receive final response").isNotNull();
            assertThat(sentStale).isTrue();
            assertThat(sentMatching).isTrue();
            assertThat(finalResponse.has("error")).isFalse();
            assertThat(finalResponse.path("result").path("response").asText())
                    .contains("Created stale-check.txt");
            assertThat(Files.exists(workDir.resolve("stale-check.txt"))).isTrue();

            destroySession(channel, sessionId, 3);
        }
    }

    @Test
    @Order(15)
    void testOverrideApprovalExecutionTriggersFalsePositiveRollback() throws Exception {
        resetAntiPatternGateFixtures();
        try (var channel = connectToSocket()) {
            String sessionId = createSession(channel, 1);

            var overrideParams = objectMapper.createObjectNode();
            overrideParams.put("sessionId", sessionId);
            overrideParams.put("tool", "write_file");
            overrideParams.put("ttlSeconds", 180);
            overrideParams.put("reason", "integration-test");
            var overrideResp = sendAndReceive(channel, "antiPatternGate.override.set", overrideParams, 2);
            assertThat(overrideResp.path("result").path("active").asBoolean()).isTrue();

            mockLlm.enqueueResponse(MockLlmClient.toolUseResponse(
                    "Need to write file.",
                    "toolu_031", "write_file",
                    "{\"file_path\":\"x_anti_pattern_rollback_probe.txt\",\"content\":\"ok\"}"
            ));
            mockLlm.enqueueResponse(MockLlmClient.textResponse("Created probe file."));

            var promptParams = objectMapper.createObjectNode();
            promptParams.put("sessionId", sessionId);
            promptParams.put("prompt", "Create x_anti_pattern_rollback_probe.txt");
            sendRequest(channel, "agent.prompt", promptParams, 3);

            JsonNode finalResponse = null;
            boolean sentStale = false;
            boolean sentMatching = false;
            boolean sawOverrideGate = false;
            String overrideRuleId = "";
            long timeoutDeadline = System.currentTimeMillis() + 8_000;

            while (System.currentTimeMillis() < timeoutDeadline) {
                var msg = readMessage(channel);
                if (msg == null) {
                    throw new IOException("Connection closed while waiting for response");
                }
                if (msg.has("id") && !msg.get("id").isNull()) {
                    finalResponse = msg;
                    break;
                }
                if (msg.has("method") && "stream.gate".equals(msg.get("method").asText())) {
                    var params = msg.path("params");
                    if ("OVERRIDE".equals(params.path("action").asText())
                            && "write_file".equals(params.path("tool").asText())) {
                        sawOverrideGate = true;
                        overrideRuleId = params.path("ruleId").asText("");
                    }
                }
                if (msg.has("method") && "permission.request".equals(msg.get("method").asText())) {
                    var permRequestId = msg.path("params").path("requestId").asText();

                    var staleParams = objectMapper.createObjectNode();
                    staleParams.put("requestId", "perm-stale-9999");
                    staleParams.put("approved", true);
                    staleParams.put("remember", false);
                    sendNotification(channel, "permission.response", staleParams);
                    sentStale = true;

                    var okParams = objectMapper.createObjectNode();
                    okParams.put("requestId", permRequestId);
                    okParams.put("approved", true);
                    okParams.put("remember", true);
                    sendNotification(channel, "permission.response", okParams);
                    sentMatching = true;
                }
            }

            assertThat(finalResponse).isNotNull();
            assertThat(sentStale).isTrue();
            assertThat(sentMatching).isTrue();
            assertThat(sawOverrideGate).isTrue();
            assertThat(overrideRuleId).isEqualTo(antiPatternRuleId);
            assertThat(finalResponse.has("error")).isFalse();
            assertThat(finalResponse.path("result").path("response").asText()).contains("Created probe file.");
            assertThat(Files.exists(workDir.resolve("x_anti_pattern_rollback_probe.txt"))).isTrue();

            var candidate = candidateStore.byId(antiPatternCandidateId).orElseThrow();
            assertThat(candidate.state()).isEqualTo(CandidateState.DEMOTED);

            var feedback = new AntiPatternGateFeedbackStore(workDir);
            var stats = feedback.statsFor(antiPatternRuleId);
            assertThat(stats.blockedCount()).isGreaterThanOrEqualTo(3);
            assertThat(stats.falsePositiveCount()).isGreaterThanOrEqualTo(2);

            destroySession(channel, sessionId, 4);
        }
    }

    // -- Plan checkpoint integration tests --

    @Test
    @Order(20)
    void testCheckpointCreatedDuringPlanExecution() throws Exception {
        // Craft a prompt that triggers planning (score >= 5):
        // "multiple_actions" (+3) + "testing" (+2) = 5
        String complexPrompt = "First investigate the API, and then write tests for it";

        // Enqueue a valid plan JSON response for sendMessage (LLMTaskPlanner.plan)
        String planJson = """
                {
                  "steps": [
                    {"name": "Read API", "description": "Read the API source", "requiredTools": ["read_file"]},
                    {"name": "Write tests", "description": "Write unit tests", "requiredTools": ["write_file"]}
                  ]
                }
                """;
        mockLlm.enqueueSendMessageResponse(MockLlmClient.sendMessageTextResponse(planJson));

        // Enqueue streaming responses for each plan step execution:
        // Step 1: read_file tool use + final text
        mockLlm.enqueueResponse(MockLlmClient.toolUseResponse(
                "Reading the API.", "toolu_cp_001", "read_file",
                "{\"file_path\":\"greeting.txt\"}"));
        mockLlm.enqueueResponse(MockLlmClient.textResponse("Found the API."));
        // Step 2: simple text (no tool use needed for this test)
        mockLlm.enqueueResponse(MockLlmClient.textResponse("Tests written successfully."));

        // Create a test file the tool can read
        Files.writeString(workDir.resolve("greeting.txt"), "API content here");

        try (var channel = connectToSocket()) {
            String sessionId = createSession(channel, 1);

            var promptParams = objectMapper.createObjectNode();
            promptParams.put("sessionId", sessionId);
            promptParams.put("prompt", complexPrompt);

            var result = collectEventsWithResumeHandling(channel, promptParams, 2,
                    true, false, false);

            // Verify plan was executed
            boolean hasPlanCreated = result.notifications().stream()
                    .anyMatch(n -> "stream.plan_created".equals(n.path("method").asText()));
            assertThat(hasPlanCreated).isTrue();

            boolean hasPlanCompleted = result.notifications().stream()
                    .anyMatch(n -> "stream.plan_completed".equals(n.path("method").asText()));
            assertThat(hasPlanCompleted).isTrue();

            // Verify checkpoint files were created on disk
            List<Path> checkpointFiles;
            try (var files = Files.list(checkpointDir)) {
                checkpointFiles = files
                        .filter(p -> p.toString().endsWith(".checkpoint.json"))
                        .toList();
            }
            assertThat(checkpointFiles).isNotEmpty();

            // Read checkpoint and verify content
            var cpJson = objectMapper.readTree(checkpointFiles.getFirst().toFile());
            assertThat(cpJson.has("originalGoal")).isTrue();
            assertThat(cpJson.get("status").asText()).isEqualTo("COMPLETED");

            assertThat(result.finalResponse().has("result")).isTrue();
            assertThat(result.finalResponse().path("result").path("planned").asBoolean()).isTrue();

            destroySession(channel, sessionId, 3);
        }
    }

    @Test
    @Order(21)
    void testResumeDetection_offeredAndDeclined() throws Exception {
        // Pre-seed a checkpoint file so the daemon finds it on next prompt
        Path projectDir = workDir.resolve("resume-test-project");
        Files.createDirectories(projectDir);

        try (var channel = connectToSocket()) {
            String sessionId = createSession(channel, 1, projectDir);

            // Build and save a checkpoint that matches this session
            var wsHash = ResumeRouter.hashWorkspace(projectDir);
            var plan = new TaskPlan("plan-resume-test", "Build feature X", List.of(
                    new PlannedStep("s1", "Research", "Find APIs", List.of("read_file"), null, StepStatus.COMPLETED),
                    new PlannedStep("s2", "Implement", "Write code", List.of("write_file"), null, StepStatus.PENDING),
                    new PlannedStep("s3", "Test", "Run tests", List.of("bash"), null, StepStatus.PENDING)
            ), new PlanStatus.Executing(1, 3), Instant.now());

            var checkpoint = new PlanCheckpoint(
                    "plan-resume-test", sessionId, wsHash, "Build feature X", plan,
                    List.of(new StepResult(true, "Found 3 APIs", null, 1000, 100, 50)),
                    0, List.of(),
                    PlanCheckpoint.CheckpointStatus.ACTIVE, "Need to implement",
                    List.of("api.java"), Instant.now(), Instant.now());

            var store = new FilePlanCheckpointStore(checkpointDir, objectMapper);
            store.save(checkpoint);

            // Enqueue a simple text response for when the normal flow continues
            // (after user declines resume)
            mockLlm.enqueueResponse(MockLlmClient.textResponse("Starting fresh."));

            var promptParams = objectMapper.createObjectNode();
            promptParams.put("sessionId", sessionId);
            promptParams.put("prompt", "Continue working");

            // Send request and handle resume.offer by DECLINING
            var result = collectEventsWithResumeHandling(channel, promptParams, 2,
                    false, false, false);

            // Verify resume.detected notification was sent
            boolean hasResumeDetected = result.notifications().stream()
                    .anyMatch(n -> "resume.detected".equals(n.path("method").asText()));
            assertThat(hasResumeDetected).isTrue();

            // Verify resume.offer notification was sent
            boolean hasResumeOffer = result.notifications().stream()
                    .anyMatch(n -> "resume.offer".equals(n.path("method").asText()));
            assertThat(hasResumeOffer).isTrue();

            // Verify resume.fallback was sent (user declined)
            boolean hasResumeFallback = result.notifications().stream()
                    .anyMatch(n -> "resume.fallback".equals(n.path("method").asText()));
            assertThat(hasResumeFallback).isTrue();

            // Verify the checkpoint is now marked as FAILED (user declined)
            var loaded = store.load("plan-resume-test");
            assertThat(loaded).isPresent();
            assertThat(loaded.get().status()).isEqualTo(PlanCheckpoint.CheckpointStatus.FAILED);

            // Response should be the normal flow (not resumed)
            assertThat(result.finalResponse().has("result")).isTrue();
            assertThat(result.finalResponse().path("result").has("resumed")).isFalse();

            destroySession(channel, sessionId, 3);
        }
    }

    @Test
    @Order(22)
    void testResumeDetection_offeredAndAccepted() throws Exception {
        Path projectDir = workDir.resolve("resume-accept-project");
        Files.createDirectories(projectDir);

        try (var channel = connectToSocket()) {
            String sessionId = createSession(channel, 1, projectDir);

            var wsHash = ResumeRouter.hashWorkspace(projectDir);
            var plan = new TaskPlan("plan-resume-accept", "Implement login", List.of(
                    new PlannedStep("s1", "Research", "Find auth libs", List.of("read_file"), null, StepStatus.COMPLETED),
                    new PlannedStep("s2", "Implement", "Write auth code", List.of("write_file"), null, StepStatus.PENDING)
            ), new PlanStatus.Executing(1, 2), Instant.now());

            var checkpoint = new PlanCheckpoint(
                    "plan-resume-accept", sessionId, wsHash, "Implement login", plan,
                    List.of(new StepResult(true, "Found libs", null, 500, 80, 40)),
                    0, List.of(),
                    PlanCheckpoint.CheckpointStatus.ACTIVE, "Ready to code",
                    List.of(), Instant.now(), Instant.now());

            var store = new FilePlanCheckpointStore(checkpointDir, objectMapper);
            store.save(checkpoint);

            // Enqueue streaming response for the resumed step execution
            // (Step 2: "Implement" - the remaining step)
            mockLlm.enqueueResponse(MockLlmClient.textResponse("Auth code implemented."));

            var promptParams = objectMapper.createObjectNode();
            promptParams.put("sessionId", sessionId);
            promptParams.put("prompt", "Continue working on login");

            // Send request and ACCEPT the resume offer
            var result = collectEventsWithResumeHandling(channel, promptParams, 2,
                    false, false, true);

            // Verify resume.detected was sent
            boolean hasResumeDetected = result.notifications().stream()
                    .anyMatch(n -> "resume.detected".equals(n.path("method").asText()));
            assertThat(hasResumeDetected).isTrue();

            // Verify resume.bound_task was sent (plan was bound to session)
            boolean hasBoundTask = result.notifications().stream()
                    .anyMatch(n -> "resume.bound_task".equals(n.path("method").asText()));
            assertThat(hasBoundTask).isTrue();

            // Verify resume.injected was sent
            boolean hasInjected = result.notifications().stream()
                    .anyMatch(n -> "resume.injected".equals(n.path("method").asText()));
            assertThat(hasInjected).isTrue();

            // Verify result indicates resumed plan
            assertThat(result.finalResponse().has("result")).isTrue();
            assertThat(result.finalResponse().path("result").path("resumed").asBoolean()).isTrue();
            assertThat(result.finalResponse().path("result").path("planned").asBoolean()).isTrue();

            // Old checkpoint should be RESUMED
            var oldCp = store.load("plan-resume-accept");
            assertThat(oldCp).isPresent();
            assertThat(oldCp.get().status()).isEqualTo(PlanCheckpoint.CheckpointStatus.RESUMED);

            destroySession(channel, sessionId, 3);
        }
    }

    @Test
    @Order(23)
    void testNoResumeAcrossWorkspaces() throws Exception {
        Path projectA = workDir.resolve("project-no-resume-A");
        Path projectB = workDir.resolve("project-no-resume-B");
        Files.createDirectories(projectA);
        Files.createDirectories(projectB);

        try (var channel = connectToSocket()) {
            String sessionId = createSession(channel, 1, projectA);

            // Seed a checkpoint for projectB (different workspace)
            var wsHashB = ResumeRouter.hashWorkspace(projectB);
            var plan = new TaskPlan("plan-wrong-ws", "Other project task", List.of(
                    new PlannedStep("s1", "Done", "Done", List.of(), null, StepStatus.COMPLETED),
                    new PlannedStep("s2", "Pending", "Pending", List.of(), null, StepStatus.PENDING)
            ), new PlanStatus.Executing(1, 2), Instant.now());

            var checkpoint = new PlanCheckpoint(
                    "plan-wrong-ws", "other-session", wsHashB, "Other project task", plan,
                    List.of(new StepResult(true, "done", null, 100, 50, 25)),
                    0, List.of(),
                    PlanCheckpoint.CheckpointStatus.ACTIVE, null,
                    List.of(), Instant.now(), Instant.now());

            var store = new FilePlanCheckpointStore(checkpointDir, objectMapper);
            store.save(checkpoint);

            // Simple text response (no planning, no resume)
            mockLlm.enqueueResponse(MockLlmClient.textResponse("Working in project A."));

            var promptParams = objectMapper.createObjectNode();
            promptParams.put("sessionId", sessionId);
            promptParams.put("prompt", "Hello");

            var result = sendPromptAndCollectEvents(channel, promptParams, 2);

            // Should NOT see any resume notifications (different workspace)
            boolean hasResumeDetected = result.notifications().stream()
                    .anyMatch(n -> "resume.detected".equals(n.path("method").asText()));
            assertThat(hasResumeDetected).isFalse();

            boolean hasResumeOffer = result.notifications().stream()
                    .anyMatch(n -> "resume.offer".equals(n.path("method").asText()));
            assertThat(hasResumeOffer).isFalse();

            destroySession(channel, sessionId, 3);
        }
    }

    @Test
    @Order(24)
    void testAdaptiveReplan_revisesAndCompletes() throws Exception {
        agentHandlerRef.setAdaptiveReplanEnabled(true);
        // Complex prompt that triggers planning (score >= 5):
        // "multiple_actions" (+3) + "testing" (+2) = 5
        String complexPrompt = "First analyze the config, and then write tests for it";

        // 1. Plan generation (sendMessage): 2-step plan
        String planJson = """
                {
                  "steps": [
                    {"name": "Analyze config", "description": "Read and analyze config", "requiredTools": ["read_file"]},
                    {"name": "Write tests", "description": "Write unit tests", "requiredTools": ["write_file"]}
                  ]
                }
                """;
        mockLlm.enqueueSendMessageResponse(MockLlmClient.sendMessageTextResponse(planJson));

        // 2. Step 1 succeeds
        mockLlm.enqueueResponse(MockLlmClient.textResponse("Config analyzed successfully."));

        // 3. Step 2 fails (triggers replan)
        mockLlm.enqueueResponse(MockLlmClient.errorResponse("Test framework not found"));

        // 4. Replanner sendMessage response: revise with 1 new step
        String replanJson = """
                {
                  "action": "revise",
                  "rationale": "Install test framework first, then write tests",
                  "steps": [
                    {"name": "Install and write tests", "description": "Install framework and write tests", "requiredTools": ["bash", "write_file"]}
                  ]
                }
                """;
        mockLlm.enqueueSendMessageResponse(MockLlmClient.sendMessageTextResponse(replanJson));

        // 5. Revised step succeeds
        mockLlm.enqueueResponse(MockLlmClient.textResponse("Tests written with new framework."));

        try (var channel = connectToSocket()) {
            String sessionId = createSession(channel, 1);

            var promptParams = objectMapper.createObjectNode();
            promptParams.put("sessionId", sessionId);
            promptParams.put("prompt", complexPrompt);

            var result = collectEventsWithResumeHandling(channel, promptParams, 2,
                    true, false, false);

            // Verify plan was created
            boolean hasPlanCreated = result.notifications().stream()
                    .anyMatch(n -> "stream.plan_created".equals(n.path("method").asText()));
            assertThat(hasPlanCreated).isTrue();

            // Verify replan notification was sent
            boolean hasPlanReplanned = result.notifications().stream()
                    .anyMatch(n -> "stream.plan_replanned".equals(n.path("method").asText()));
            assertThat(hasPlanReplanned).isTrue();

            // Verify plan completed
            boolean hasPlanCompleted = result.notifications().stream()
                    .anyMatch(n -> "stream.plan_completed".equals(n.path("method").asText()));
            assertThat(hasPlanCompleted).isTrue();

            // Verify the final result indicates success
            assertThat(result.finalResponse().has("result")).isTrue();
            assertThat(result.finalResponse().path("result").path("planned").asBoolean()).isTrue();
            assertThat(result.finalResponse().path("result").path("planSuccess").asBoolean()).isTrue();

            destroySession(channel, sessionId, 3);
        }
    }

    @Test
    @Order(25)
    void testAdaptiveReplan_escalatesOnPersistentFailure() throws Exception {
        agentHandlerRef.setAdaptiveReplanEnabled(true);
        // Complex prompt that triggers planning
        String complexPrompt = "First read the database schema, and then migrate the tables";

        // 1. Plan generation: 2-step plan
        String planJson = """
                {
                  "steps": [
                    {"name": "Read schema", "description": "Read database schema", "requiredTools": ["read_file"]},
                    {"name": "Migrate tables", "description": "Run migration", "requiredTools": ["bash"]}
                  ]
                }
                """;
        mockLlm.enqueueSendMessageResponse(MockLlmClient.sendMessageTextResponse(planJson));

        // 2. Step 1 fails
        mockLlm.enqueueResponse(MockLlmClient.errorResponse("Database connection refused"));

        // 3. Replanner escalates
        String escalateJson = """
                {
                  "action": "escalate",
                  "reason": "Database is unreachable, cannot proceed with migration"
                }
                """;
        mockLlm.enqueueSendMessageResponse(MockLlmClient.sendMessageTextResponse(escalateJson));

        try (var channel = connectToSocket()) {
            String sessionId = createSession(channel, 1);

            var promptParams = objectMapper.createObjectNode();
            promptParams.put("sessionId", sessionId);
            promptParams.put("prompt", complexPrompt);

            var result = collectEventsWithResumeHandling(channel, promptParams, 2,
                    true, false, false);

            // Verify plan was created
            boolean hasPlanCreated = result.notifications().stream()
                    .anyMatch(n -> "stream.plan_created".equals(n.path("method").asText()));
            assertThat(hasPlanCreated).isTrue();

            // Verify escalation notification was sent
            boolean hasPlanEscalated = result.notifications().stream()
                    .anyMatch(n -> "stream.plan_escalated".equals(n.path("method").asText()));
            assertThat(hasPlanEscalated).isTrue();

            // Verify the final result indicates failure
            assertThat(result.finalResponse().has("result")).isTrue();
            assertThat(result.finalResponse().path("result").path("planned").asBoolean()).isTrue();
            assertThat(result.finalResponse().path("result").path("planSuccess").asBoolean()).isFalse();

            destroySession(channel, sessionId, 3);
        }
    }

    @Test
    @Order(999)
    void testInspectContextWaitsForDelayedToolRegistration() throws Exception {
        var session = sessionManager.createSession(workDir);
        var toolRegistry = extractToolRegistry(agentHandlerRef);
        var delayedToolName = "screen_capture";

        try {
            var initialInspection = agentHandlerRef.inspectContext(session.id(), "Check the UI state.");
            assertThat(initialInspection.prompt()).doesNotContain("**screen_capture**");

            var mcpReady = new CompletableFuture<Void>();
            agentHandlerRef.setMcpInitFuture(mcpReady);

            Thread.ofVirtual().start(() -> {
                try {
                    Thread.sleep(100);
                    toolRegistry.register(new Tool() {
                        @Override
                        public String name() {
                            return delayedToolName;
                        }

                        @Override
                        public String description() {
                            return "Delayed test tool for inspectContext coverage.";
                        }

                        @Override
                        public JsonNode inputSchema() {
                            return JsonNodeFactory.instance.objectNode().put("type", "object");
                        }

                        @Override
                        public ToolResult execute(String inputJson) {
                            return new ToolResult("inspect ok", false);
                        }
                    });
                    mcpReady.complete(null);
                } catch (Exception e) {
                    mcpReady.completeExceptionally(e);
                }
            });

            var inspection = agentHandlerRef.inspectContext(session.id(), "Check the UI state.");
            assertThat(inspection.prompt()).contains("**screen_capture**");
            assertThat(inspection.prompt()).contains("LAST RESORT ONLY");
        } finally {
            agentHandlerRef.setMcpInitFuture(CompletableFuture.completedFuture(null));
            sessionManager.destroySession(session.id());
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
        return createSession(channel, requestId, workDir);
    }

    private String createSession(SocketChannel channel, int requestId, Path projectPath) throws Exception {
        var params = objectMapper.createObjectNode().put("project", projectPath.toString());
        var response = sendAndReceive(channel, "session.create", params, requestId);
        return response.get("result").get("sessionId").asText();
    }

    private ToolRegistry extractToolRegistry(StreamingAgentHandler handler) throws Exception {
        var field = StreamingAgentHandler.class.getDeclaredField("toolRegistry");
        field.setAccessible(true);
        return (ToolRegistry) field.get(handler);
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
        var timeoutDeadline = System.currentTimeMillis() + 5_000; // 5s timeout

        while (System.currentTimeMillis() < timeoutDeadline) {
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
     * Sends a JSON-RPC notification (no id field) to the daemon.
     */
    private void sendNotification(SocketChannel channel, String method,
                                   JsonNode params) throws Exception {
        var notification = objectMapper.createObjectNode();
        notification.put("method", method);
        if (params != null) {
            notification.set("params", params);
        }

        var json = objectMapper.writeValueAsString(notification) + "\n";
        channel.write(ByteBuffer.wrap(json.getBytes(StandardCharsets.UTF_8)));
    }

    /**
     * Reads a single newline-delimited JSON message from the channel.
     * Uses the shared {@link #channelLineBuffer} so that data from a previous
     * {@code channel.read()} that contained multiple messages is not lost.
     * Channel stays in blocking mode; the class-level @Timeout guards against hangs.
     */
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

    /**
     * Sends an agent.prompt request and collects events, handling both permission.request
     * and resume.offer notifications. This enables integration testing of the full
     * checkpoint/resume flow.
     *
     * @param acceptResume whether to accept resume.offer (true) or decline (false)
     */
    private PromptResult collectEventsWithResumeHandling(
            SocketChannel channel, JsonNode promptParams, int requestId,
            boolean approvePermissions, boolean rememberPermissions,
            boolean acceptResume) throws Exception {

        sendRequest(channel, "agent.prompt", promptParams, requestId);

        var notifications = new ArrayList<JsonNode>();
        var timeoutDeadline = System.currentTimeMillis() + 10_000; // 10s timeout for plan tests

        while (System.currentTimeMillis() < timeoutDeadline) {
            var msg = readMessage(channel);
            if (msg == null) {
                throw new IOException("Connection closed while waiting for response");
            }

            // Final response
            if (msg.has("id") && !msg.get("id").isNull()) {
                return new PromptResult(notifications, msg);
            }

            String method = msg.path("method").asText("");

            // Handle permission.request
            if ("permission.request".equals(method)) {
                var permRequestId = msg.path("params").path("requestId").asText();
                var responseParams = objectMapper.createObjectNode();
                responseParams.put("requestId", permRequestId);
                responseParams.put("approved", approvePermissions);
                responseParams.put("remember", rememberPermissions);
                sendNotification(channel, "permission.response", responseParams);
            }

            // Handle resume.offer
            if ("resume.offer".equals(method)) {
                var planId = msg.path("params").path("planId").asText();
                var responseParams = objectMapper.createObjectNode();
                responseParams.put("planId", planId);
                responseParams.put("accept", acceptResume);
                sendNotification(channel, "resume.response", responseParams);
            }

            notifications.add(msg);
        }

        throw new AssertionError("Timed out waiting for response");
    }

    /**
     * Result of an agent.prompt request, containing streaming notifications
     * and the final JSON-RPC response.
     */
    record PromptResult(List<JsonNode> notifications, JsonNode finalResponse) {}
}
