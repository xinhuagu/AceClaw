package dev.aceclaw.daemon;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import dev.aceclaw.core.agent.HookExecutor;
import dev.aceclaw.core.agent.StreamingAgentLoop;
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
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Full-stack E2E integration tests for the hook system.
 *
 * <p>Uses real shell scripts as hooks and the MockLlmClient to drive the agent loop.
 * Tests verify the entire flow: hook config → CommandHookExecutor → PermissionAwareTool
 * → tool execution with PreToolUse blocking, PostToolUse side effects.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@Timeout(value = 60, unit = TimeUnit.SECONDS)
@DisabledOnOs(OS.WINDOWS)
class HookIntegrationTest {

    @TempDir
    static Path tempDir;

    private static Path socketPath;
    private static Path workDir;
    private static Path scriptDir;
    private static MockLlmClient mockLlm;
    private static SessionManager sessionManager;
    private static PermissionManager permissionManager;
    private static UdsListener udsListener;
    private static ObjectMapper objectMapper;

    private final StringBuilder channelLineBuffer = new StringBuilder();

    @BeforeAll
    static void startDaemon() throws Exception {
        socketPath = tempDir.resolve("test-hooks.sock");
        workDir = tempDir.resolve("workspace");
        scriptDir = tempDir.resolve("scripts");
        Files.createDirectories(workDir);
        Files.createDirectories(scriptDir);

        // Create hook scripts
        createScript("block_bash.sh", """
                #!/bin/bash
                # Read stdin (hook protocol) and block bash commands
                cat > /dev/null
                echo "Bash commands are blocked by security policy" >&2
                exit 2
                """);

        createScript("audit_post.sh", """
                #!/bin/bash
                # Read stdin, write a marker file to prove we ran
                INPUT=$(cat)
                echo "$INPUT" > """ + tempDir.resolve("audit-marker.json").toAbsolutePath() + """

                echo '{"decision":"allow"}'
                """);

        createScript("allow_all.sh", """
                #!/bin/bash
                cat > /dev/null
                echo '{"decision":"allow"}'
                """);

        // Infrastructure
        objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

        sessionManager = new SessionManager();
        var router = new RequestRouter(sessionManager, objectMapper);
        var connectionBridge = new ConnectionBridge(router, objectMapper);

        // Mock LLM
        mockLlm = new MockLlmClient();

        // Tool registry
        var toolRegistry = new ToolRegistry();
        toolRegistry.register(new ReadFileTool(workDir));
        toolRegistry.register(new WriteFileTool(workDir));
        toolRegistry.register(new EditFileTool(workDir));
        toolRegistry.register(new BashExecTool(workDir));
        toolRegistry.register(new GlobSearchTool(workDir));
        toolRegistry.register(new GrepSearchTool(workDir));

        // Permission manager (auto-accept for simplicity — hooks are the focus)
        permissionManager = new PermissionManager(new DefaultPermissionPolicy("auto-accept"));

        // Hook registry: PreToolUse blocks "bash", PostToolUse audits all
        var hookConfigs = new HashMap<String, List<AceClawConfig.HookMatcherFormat>>();

        // PreToolUse: block bash
        var preHookFormat = new AceClawConfig.HookConfigFormat(
                "command", scriptDir.resolve("block_bash.sh").toAbsolutePath().toString(), 10);
        var preMatcherFormat = new AceClawConfig.HookMatcherFormat("bash", List.of(preHookFormat));
        hookConfigs.put("PreToolUse", List.of(preMatcherFormat));

        // PostToolUse: audit all tools
        var postHookFormat = new AceClawConfig.HookConfigFormat(
                "command", scriptDir.resolve("audit_post.sh").toAbsolutePath().toString(), 10);
        var postMatcherFormat = new AceClawConfig.HookMatcherFormat(null, List.of(postHookFormat));
        hookConfigs.put("PostToolUse", List.of(postMatcherFormat));

        var hookRegistry = HookRegistry.load(hookConfigs);
        HookExecutor hookExecutor = new CommandHookExecutor(hookRegistry, objectMapper, workDir);

        // Streaming agent loop + handler
        var agentLoop = new StreamingAgentLoop(
                mockLlm, toolRegistry, "mock-model", "You are a test agent.");
        var agentHandler = new StreamingAgentHandler(
                sessionManager, agentLoop, toolRegistry, permissionManager, objectMapper);
        agentHandler.setLlmConfig(mockLlm, "mock-model", "You are a test agent.");
        agentHandler.setMemoryStore(null, workDir);
        agentHandler.setHookExecutor(hookExecutor);
        agentHandler.register(router);

        // Start UDS listener
        udsListener = new UdsListener(socketPath, connectionBridge);
        udsListener.start();

        // Wait for socket readiness
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

    @BeforeEach
    void resetState() {
        mockLlm.reset();
        permissionManager.clearSessionApprovals();
        channelLineBuffer.setLength(0);
        // Clean up audit marker
        try { Files.deleteIfExists(tempDir.resolve("audit-marker.json")); } catch (Exception ignored) {}
    }

    @Test
    @Order(1)
    void preToolUseBlocksBashCommand() throws Exception {
        try (var channel = connectToSocket()) {
            String sessionId = createSession(channel, 1);

            // LLM wants to run bash — PreToolUse hook should block it
            mockLlm.enqueueResponse(MockLlmClient.toolUseResponse(
                    "Let me run that command.",
                    "toolu_001", "bash",
                    "{\"command\":\"echo hello\"}"
            ));
            // LLM responds after seeing the block
            mockLlm.enqueueResponse(MockLlmClient.textResponse(
                    "The bash command was blocked by a hook."));

            var params = objectMapper.createObjectNode();
            params.put("sessionId", sessionId);
            params.put("prompt", "Run echo hello");

            var result = sendPromptAndCollectEvents(channel, params, 2);

            // Verify the final response mentions the hook block
            var response = result.finalResponse();
            assertThat(response.has("result")).isTrue();

            // The LLM should have received a tool error result mentioning the hook
            var requests = mockLlm.capturedRequests();
            assertThat(requests).hasSize(2);

            // Second request should contain a tool result with the block message
            var secondRequest = requests.get(1);
            var toolResultBlocks = secondRequest.messages().stream()
                    .filter(m -> m instanceof dev.aceclaw.core.llm.Message.UserMessage)
                    .flatMap(m -> ((dev.aceclaw.core.llm.Message.UserMessage) m).content().stream())
                    .filter(b -> b instanceof dev.aceclaw.core.llm.ContentBlock.ToolResult)
                    .map(b -> (dev.aceclaw.core.llm.ContentBlock.ToolResult) b)
                    .toList();
            assertThat(toolResultBlocks).isNotEmpty();
            // The tool result should be an error indicating hook block
            var blockResult = toolResultBlocks.getFirst();
            assertThat(blockResult.isError()).isTrue();
            assertThat(blockResult.content()).contains("Blocked by hook");

            destroySession(channel, sessionId, 3);
        }
    }

    @Test
    @Order(2)
    void preToolUseDoesNotFireForUnmatchedTool() throws Exception {
        // Create a test file that read_file can access
        var testFile = workDir.resolve("hook-test.txt");
        Files.writeString(testFile, "Hook test content");

        try (var channel = connectToSocket()) {
            String sessionId = createSession(channel, 1);

            // LLM uses read_file — PreToolUse hook only matches "bash", so should pass
            mockLlm.enqueueResponse(MockLlmClient.toolUseResponse(
                    "Reading the file.",
                    "toolu_002", "read_file",
                    "{\"file_path\":\"hook-test.txt\"}"
            ));
            mockLlm.enqueueResponse(MockLlmClient.textResponse(
                    "The file contains: Hook test content"));

            var params = objectMapper.createObjectNode();
            params.put("sessionId", sessionId);
            params.put("prompt", "Read hook-test.txt");

            var result = sendPromptAndCollectEvents(channel, params, 2);

            // Should succeed — read_file is not matched by the PreToolUse "bash" hook
            var response = result.finalResponse();
            assertThat(response.has("result")).isTrue();
            assertThat(response.get("result").get("response").asText())
                    .contains("Hook test content");

            destroySession(channel, sessionId, 3);
        }
    }

    @Test
    @Order(3)
    void postToolUseFiresAfterSuccess() throws Exception {
        // Create a test file for read_file
        var testFile = workDir.resolve("audit-test.txt");
        Files.writeString(testFile, "Audit me");

        try (var channel = connectToSocket()) {
            String sessionId = createSession(channel, 1);

            // LLM reads a file — PostToolUse should fire and write audit marker
            mockLlm.enqueueResponse(MockLlmClient.toolUseResponse(
                    "Reading the file.",
                    "toolu_003", "read_file",
                    "{\"file_path\":\"audit-test.txt\"}"
            ));
            mockLlm.enqueueResponse(MockLlmClient.textResponse("File read successfully."));

            var params = objectMapper.createObjectNode();
            params.put("sessionId", sessionId);
            params.put("prompt", "Read audit-test.txt");

            var result = sendPromptAndCollectEvents(channel, params, 2);

            // Poll for the async PostToolUse hook to complete (fire-and-forget on virtual thread)
            var auditMarker = tempDir.resolve("audit-marker.json");
            assertThat(waitForFile(auditMarker, 5000))
                    .as("PostToolUse hook should have created audit marker file")
                    .isTrue();

            // Verify the audit marker contains the expected JSON structure
            var markerContent = Files.readString(auditMarker);
            var markerJson = objectMapper.readTree(markerContent);
            assertThat(markerJson.get("hook_event_name").asText()).isEqualTo("PostToolUse");
            assertThat(markerJson.get("tool_name").asText()).isEqualTo("read_file");
            assertThat(markerJson.has("tool_output")).isTrue();

            destroySession(channel, sessionId, 3);
        }
    }

    @Test
    @Order(4)
    void hookInvocationOrderVerified() throws Exception {
        // This test verifies that PreToolUse runs before tool execution
        // and PostToolUse runs after. We use bash (blocked by PreToolUse)
        // and read_file (passes PreToolUse, triggers PostToolUse).

        // Clean audit marker
        Files.deleteIfExists(tempDir.resolve("audit-marker.json"));

        var testFile = workDir.resolve("order-test.txt");
        Files.writeString(testFile, "Order test");

        try (var channel = connectToSocket()) {
            String sessionId = createSession(channel, 1);

            // 1. First try bash — should be blocked by PreToolUse
            mockLlm.enqueueResponse(MockLlmClient.toolUseResponse(
                    "Running bash.",
                    "toolu_004", "bash",
                    "{\"command\":\"echo test\"}"
            ));
            // 2. LLM falls back to read_file after bash is blocked
            mockLlm.enqueueResponse(MockLlmClient.toolUseResponse(
                    "Reading instead.",
                    "toolu_005", "read_file",
                    "{\"file_path\":\"order-test.txt\"}"
            ));
            // 3. Final response
            mockLlm.enqueueResponse(MockLlmClient.textResponse("Done with order test."));

            var params = objectMapper.createObjectNode();
            params.put("sessionId", sessionId);
            params.put("prompt", "First try bash, then read order-test.txt");

            var result = sendPromptAndCollectEvents(channel, params, 2);

            // Poll for async PostToolUse hooks
            var auditMarker = tempDir.resolve("audit-marker.json");
            waitForFile(auditMarker, 5000);

            // Bash was blocked → no PostToolUse for bash
            // read_file succeeded → PostToolUse audit marker should exist for read_file
            assertThat(Files.exists(auditMarker)).isTrue();
            var markerJson = objectMapper.readTree(Files.readString(auditMarker));
            assertThat(markerJson.get("tool_name").asText()).isEqualTo("read_file");

            // Verify 3 LLM calls were made (bash→blocked, read_file, final)
            assertThat(mockLlm.capturedRequests()).hasSize(3);

            destroySession(channel, sessionId, 3);
        }
    }

    // -- Helper methods (mirrors DaemonIntegrationTest) --

    /** Polls for a file to appear, returning true if found within the timeout. */
    private static boolean waitForFile(Path file, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (Files.exists(file)) return true;
            Thread.sleep(50);
        }
        return Files.exists(file);
    }


    private static void createScript(String name, String content) throws IOException {
        var path = scriptDir.resolve(name);
        Files.writeString(path, content);
        Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rwxr-xr-x"));
    }

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
        var timeoutDeadline = System.currentTimeMillis() + 10_000;

        while (System.currentTimeMillis() < timeoutDeadline) {
            var msg = readMessage(channel);
            if (msg == null) {
                throw new IOException("Connection closed while waiting for response");
            }

            if (msg.has("id") && !msg.get("id").isNull()) {
                return new PromptResult(notifications, msg);
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
