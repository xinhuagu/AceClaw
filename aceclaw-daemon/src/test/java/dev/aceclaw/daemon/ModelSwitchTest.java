package dev.aceclaw.daemon;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the model.list and model.switch JSON-RPC methods.
 */
class ModelSwitchTest {

    private RequestRouter router;
    private ObjectMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = new ObjectMapper();
        var sessionManager = new SessionManager();
        router = new RequestRouter(sessionManager, mapper);

        // Simulate wiring similar to AceClawDaemon
        var mockClient = new MockLlmClient();
        var toolRegistry = new dev.aceclaw.core.agent.ToolRegistry();
        var permissionManager = new dev.aceclaw.security.PermissionManager(
                new dev.aceclaw.security.DefaultPermissionPolicy("auto-accept"));

        var agentLoop = new dev.aceclaw.core.agent.StreamingAgentLoop(
                mockClient, toolRegistry, "test-model", "test prompt");

        var handler = new StreamingAgentHandler(
                sessionManager, agentLoop, toolRegistry, permissionManager, mapper);
        handler.setLlmConfig(mockClient, "test-model", "test prompt");

        // Register model.list
        router.register("model.list", params -> {
            var result = mapper.createObjectNode();
            result.put("currentModel", handler.getEffectiveModel());
            result.put("defaultModel", handler.getDefaultModel());
            result.put("provider", "test-provider");
            var models = mapper.createArrayNode();
            for (var m : mockClient.listModels()) {
                models.add(m);
            }
            result.set("models", models);
            return result;
        });

        router.register("model.switch", params -> {
            var newModel = params.get("model").asText();
            handler.setModelOverride(newModel);
            var result = mapper.createObjectNode();
            result.put("model", newModel);
            result.put("switched", true);
            return result;
        });
    }

    @Test
    void modelListReturnsCurrentModel() {
        var request = new JsonRpc.Request("2.0", "model.list", null, 1L);
        var response = router.route(request);

        assertInstanceOf(JsonRpc.Response.class, response);
        var result = (com.fasterxml.jackson.databind.JsonNode) ((JsonRpc.Response) response).result();
        assertEquals("test-model", result.path("currentModel").asText());
        assertEquals("test-model", result.path("defaultModel").asText());
        assertEquals("test-provider", result.path("provider").asText());
        assertTrue(result.path("models").isArray());
    }

    @Test
    void modelSwitchChangesEffectiveModel() {
        // Switch to a new model
        ObjectNode params = mapper.createObjectNode();
        params.put("model", "new-model");
        var switchRequest = new JsonRpc.Request("2.0", "model.switch", params, 2L);
        var switchResponse = router.route(switchRequest);

        assertInstanceOf(JsonRpc.Response.class, switchResponse);
        var switchResult = (com.fasterxml.jackson.databind.JsonNode) ((JsonRpc.Response) switchResponse).result();
        assertTrue(switchResult.path("switched").asBoolean());
        assertEquals("new-model", switchResult.path("model").asText());

        // Verify model.list now reflects the override
        var listRequest = new JsonRpc.Request("2.0", "model.list", null, 3L);
        var listResponse = router.route(listRequest);
        var listResult = (com.fasterxml.jackson.databind.JsonNode) ((JsonRpc.Response) listResponse).result();
        assertEquals("new-model", listResult.path("currentModel").asText());
        assertEquals("test-model", listResult.path("defaultModel").asText());
    }

    @Test
    void modelSwitchMissingParamThrows() {
        var request = new JsonRpc.Request("2.0", "model.switch", null, 4L);
        var response = router.route(request);

        assertInstanceOf(JsonRpc.ErrorResponse.class, response);
    }
}
