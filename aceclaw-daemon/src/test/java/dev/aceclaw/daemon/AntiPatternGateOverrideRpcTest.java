package dev.aceclaw.daemon;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.aceclaw.core.agent.StreamingAgentLoop;
import dev.aceclaw.core.agent.ToolRegistry;
import dev.aceclaw.security.DefaultPermissionPolicy;
import dev.aceclaw.security.PermissionManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AntiPatternGateOverrideRpcTest {

    private RequestRouter router;
    private ObjectMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = new ObjectMapper();
        var sessionManager = new SessionManager();
        router = new RequestRouter(sessionManager, mapper);

        var mockClient = new MockLlmClient();
        var toolRegistry = new ToolRegistry();
        var permissionManager = new PermissionManager(new DefaultPermissionPolicy("auto-accept"));
        var agentLoop = new StreamingAgentLoop(mockClient, toolRegistry, "test-model", "test prompt");
        var handler = new StreamingAgentHandler(sessionManager, agentLoop, toolRegistry, permissionManager, mapper);
        handler.setLlmConfig(mockClient, "test-model", "test prompt");

        router.register("antiPatternGate.override.set", params -> {
            if (params == null || !params.has("sessionId")) {
                throw new IllegalArgumentException("Missing required parameter: sessionId");
            }
            if (params == null || !params.has("tool")) {
                throw new IllegalArgumentException("Missing required parameter: tool");
            }
            String sessionId = params.get("sessionId").asText();
            String tool = params.get("tool").asText();
            long ttlSeconds = params.has("ttlSeconds") ? Math.max(1L, params.get("ttlSeconds").asLong()) : 300L;
            String reason = params.has("reason") ? params.get("reason").asText() : "manual override";
            handler.setAntiPatternGateOverride(sessionId, tool, ttlSeconds, reason);
            var status = handler.getAntiPatternGateOverride(sessionId, tool);
            var result = mapper.createObjectNode();
            result.put("active", status.active());
            result.put("ttlSecondsRemaining", status.ttlSecondsRemaining());
            return result;
        });
        router.register("antiPatternGate.override.get", params -> {
            String sessionId = params.get("sessionId").asText();
            String tool = params.get("tool").asText();
            var status = handler.getAntiPatternGateOverride(sessionId, tool);
            var result = mapper.createObjectNode();
            result.put("active", status.active());
            return result;
        });
        router.register("antiPatternGate.override.clear", params -> {
            String sessionId = params.get("sessionId").asText();
            String tool = params.get("tool").asText();
            var result = mapper.createObjectNode();
            result.put("cleared", handler.clearAntiPatternGateOverride(sessionId, tool));
            return result;
        });
    }

    @Test
    void rpcSetGetClearOverride() {
        ObjectNode setParams = mapper.createObjectNode();
        setParams.put("sessionId", "s1");
        setParams.put("tool", "bash");
        setParams.put("ttlSeconds", 120);
        setParams.put("reason", "manual");

        var setResponse = router.route(new JsonRpc.Request("2.0", "antiPatternGate.override.set", setParams, 1L));
        assertInstanceOf(JsonRpc.Response.class, setResponse);
        var setResult = (com.fasterxml.jackson.databind.JsonNode) ((JsonRpc.Response) setResponse).result();
        assertTrue(setResult.path("active").asBoolean());
        assertTrue(setResult.path("ttlSecondsRemaining").asLong() > 0);

        ObjectNode getParams = mapper.createObjectNode();
        getParams.put("sessionId", "s1");
        getParams.put("tool", "bash");
        var getResponse = router.route(new JsonRpc.Request("2.0", "antiPatternGate.override.get", getParams, 2L));
        assertInstanceOf(JsonRpc.Response.class, getResponse);
        var getResult = (com.fasterxml.jackson.databind.JsonNode) ((JsonRpc.Response) getResponse).result();
        assertTrue(getResult.path("active").asBoolean());

        ObjectNode clearParams = mapper.createObjectNode();
        clearParams.put("sessionId", "s1");
        clearParams.put("tool", "bash");
        var clearResponse = router.route(new JsonRpc.Request("2.0", "antiPatternGate.override.clear", clearParams, 3L));
        assertInstanceOf(JsonRpc.Response.class, clearResponse);
        var clearResult = (com.fasterxml.jackson.databind.JsonNode) ((JsonRpc.Response) clearResponse).result();
        assertTrue(clearResult.path("cleared").asBoolean());
    }
}
