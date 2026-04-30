package dev.aceclaw.daemon;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link StreamingAgentHandler#routePermissionResponse}
 * (issue #433).
 *
 * <p>The method is the entry point the {@code AceClawDaemon} inbound
 * dispatcher calls when the WebSocket bridge receives a browser-sent
 * {@code permission.response}. It looks up the daemon-wide registry
 * keyed by {@code requestId} and completes the matching
 * {@link CompletableFuture}; the CLI socket monitor mirrors writes
 * into the same registry so first-response-wins works regardless of
 * which channel the response arrived on.
 *
 * <p>Tests reach into the private {@code permissionRegistry} field via
 * reflection to populate test futures without spinning up a real
 * permission flow. The contract under test is the routing logic
 * itself: lookup, complete, idempotency, defensive parsing.
 */
class PermissionResponseRoutingTest {

    private StreamingAgentHandler handler;
    private ObjectMapper mapper;
    private ConcurrentHashMap<String, CompletableFuture<JsonNode>> registry;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws Exception {
        mapper = new ObjectMapper();
        var sessionManager = new SessionManager();
        var mockClient = new MockLlmClient();
        var toolRegistry = new dev.aceclaw.core.agent.ToolRegistry();
        var permissionManager = new dev.aceclaw.security.PermissionManager(
                new dev.aceclaw.security.DefaultPermissionPolicy("auto-accept"));
        var agentLoop = new dev.aceclaw.core.agent.StreamingAgentLoop(
                mockClient, toolRegistry, "test-model", "test prompt");
        handler = new StreamingAgentHandler(
                sessionManager, agentLoop, toolRegistry, permissionManager, mapper);

        // The registry is private — for unit testing we reach in to
        // populate test futures the way CancelAwareStreamContext.
        // registerPermissionRequest would in production.
        Field f = StreamingAgentHandler.class.getDeclaredField("permissionRegistry");
        f.setAccessible(true);
        registry = (ConcurrentHashMap<String, CompletableFuture<JsonNode>>) f.get(handler);
    }

    private JsonNode response(String requestId, boolean approved) {
        var root = mapper.createObjectNode();
        root.put("method", "permission.response");
        var params = root.putObject("params");
        if (requestId != null) params.put("requestId", requestId);
        params.put("approved", approved);
        return root;
    }

    @Test
    void completesPendingFutureForKnownRequestId() throws Exception {
        var future = new CompletableFuture<JsonNode>();
        registry.put("perm-abc", future);
        boolean routed = handler.routePermissionResponse(response("perm-abc", true));
        assertThat(routed).isTrue();
        var msg = future.get(1, TimeUnit.SECONDS);
        assertThat(msg.get("params").get("requestId").asText()).isEqualTo("perm-abc");
        assertThat(msg.get("params").get("approved").asBoolean()).isTrue();
        // The matched entry has been removed — second route returns false
        // (the contract that lets us drop late duplicates safely).
        assertThat(registry.containsKey("perm-abc")).isFalse();
    }

    @Test
    void returnsFalseForUnknownRequestId() {
        // No future registered for this id — typical when the CLI got
        // there first and removed it, or when the request expired.
        assertThat(handler.routePermissionResponse(response("perm-never", true))).isFalse();
    }

    @Test
    void returnsFalseOnSecondRouteEvenWithSameId() throws Exception {
        // Idempotency: late duplicates from the OTHER channel (CLI sent
        // first, then browser sends; or browser sends twice from a
        // double-clicked button) safely no-op.
        var future = new CompletableFuture<JsonNode>();
        registry.put("perm-once", future);
        assertThat(handler.routePermissionResponse(response("perm-once", true))).isTrue();
        assertThat(handler.routePermissionResponse(response("perm-once", false))).isFalse();
        // First route's value wins (browser approving) — second one
        // (denying) is dropped.
        assertThat(future.get(1, TimeUnit.SECONDS)
                .get("params").get("approved").asBoolean()).isTrue();
    }

    @Test
    void returnsFalseWhenMessageIsMissingParams() {
        var bad = mapper.createObjectNode();
        bad.put("method", "permission.response");
        // no params field
        assertThat(handler.routePermissionResponse(bad)).isFalse();
    }

    @Test
    void returnsFalseWhenRequestIdIsMissing() {
        // params present but no requestId — defensive parse, not a
        // throw, since malformed messages shouldn't crash the handler.
        var bad = mapper.createObjectNode();
        bad.put("method", "permission.response");
        bad.putObject("params").put("approved", true);
        assertThat(handler.routePermissionResponse(bad)).isFalse();
    }

    @Test
    void returnsFalseWhenRequestIdIsEmptyString() {
        // Daemon's UUID-derived ids never produce empty strings, but a
        // fuzzed/manual client could — guard against it.
        assertThat(handler.routePermissionResponse(response("", true))).isFalse();
    }

    @Test
    void returnsFalseForNullMessage() {
        assertThat(handler.routePermissionResponse(null)).isFalse();
    }
}
