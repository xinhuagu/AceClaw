package dev.aceclaw.daemon;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
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
 * reflection to populate test entries without spinning up a real
 * permission flow. The contract under test is the routing logic
 * itself: lookup, complete, idempotency, defensive parsing, and the
 * cross-session guard added to prevent one client from resolving
 * another session's pending approval (codex P1 review on PR #454).
 */
class PermissionResponseRoutingTest {

    private static final String SESSION_ID = "sess-1";

    private StreamingAgentHandler handler;
    private ObjectMapper mapper;
    private ConcurrentHashMap<String, Object> registry;
    private Constructor<?> pendingPermissionCtor;

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

        // The registry + PendingPermission record are private — for
        // unit testing we reach in to populate entries the way
        // CancelAwareStreamContext.registerPermissionRequest would in
        // production. The raw cast loses type safety but the test only
        // needs to put/inspect entries.
        Field f = StreamingAgentHandler.class.getDeclaredField("permissionRegistry");
        f.setAccessible(true);
        registry = (ConcurrentHashMap<String, Object>) f.get(handler);

        // Locate the private PendingPermission record's constructor for
        // populating test entries (it's a record, so the canonical
        // constructor takes (String sessionId, CompletableFuture future)).
        Class<?> pendingPermissionClass = null;
        for (Class<?> inner : StreamingAgentHandler.class.getDeclaredClasses()) {
            if (inner.getSimpleName().equals("PendingPermission")) {
                pendingPermissionClass = inner;
                break;
            }
        }
        assertThat(pendingPermissionClass).isNotNull();
        pendingPermissionCtor = pendingPermissionClass.getDeclaredConstructor(
                String.class, CompletableFuture.class);
        pendingPermissionCtor.setAccessible(true);
    }

    private Object pending(String sessionId, CompletableFuture<JsonNode> future) throws Exception {
        return pendingPermissionCtor.newInstance(sessionId, future);
    }

    /** Browser-shaped response carrying sessionId. */
    private JsonNode response(String requestId, String sessionId, boolean approved) {
        var root = mapper.createObjectNode();
        root.put("method", "permission.response");
        var params = root.putObject("params");
        if (requestId != null) params.put("requestId", requestId);
        if (sessionId != null) params.put("sessionId", sessionId);
        params.put("approved", approved);
        return root;
    }

    @Test
    void completesPendingFutureForKnownRequestId() throws Exception {
        var future = new CompletableFuture<JsonNode>();
        registry.put("perm-abc", pending(SESSION_ID, future));
        boolean routed = handler.routePermissionResponse(response("perm-abc", SESSION_ID, true));
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
        // No entry registered for this id — typical when the CLI got
        // there first and removed it, or when the request expired.
        assertThat(handler.routePermissionResponse(response("perm-never", SESSION_ID, true)))
                .isFalse();
    }

    @Test
    void returnsFalseOnSecondRouteEvenWithSameId() throws Exception {
        // Idempotency: late duplicates from the OTHER channel (CLI sent
        // first, then browser sends; or browser sends twice from a
        // double-clicked button) safely no-op.
        var future = new CompletableFuture<JsonNode>();
        registry.put("perm-once", pending(SESSION_ID, future));
        assertThat(handler.routePermissionResponse(response("perm-once", SESSION_ID, true)))
                .isTrue();
        assertThat(handler.routePermissionResponse(response("perm-once", SESSION_ID, false)))
                .isFalse();
        // First route's value wins (approving) — second one (denying) dropped.
        assertThat(future.get(1, TimeUnit.SECONDS)
                .get("params").get("approved").asBoolean()).isTrue();
    }

    @Test
    void rejectsCrossSessionResponse() throws Exception {
        // Cross-session guard: a browser tab viewing session B observes
        // session A's broadcast permission.request and tries to approve
        // by replaying its requestId. Daemon must reject because the
        // sessionId in the response does not match the registered
        // session. Without this guard the future would complete and
        // a tool the responder doesn't own would execute.
        var future = new CompletableFuture<JsonNode>();
        registry.put("perm-cross", pending("sess-A", future));
        boolean routed = handler.routePermissionResponse(
                response("perm-cross", "sess-B-attacker", true));
        assertThat(routed).isFalse();
        // Future stays pending — only the legitimate sessionId can
        // complete it (or it'll time out at 120s).
        assertThat(future.isDone()).isFalse();
        // Entry stays in the registry too — a follow-up legitimate
        // response should still be able to complete it.
        assertThat(registry.containsKey("perm-cross")).isTrue();
    }

    @Test
    void rejectsResponseMissingSessionId() throws Exception {
        // Older client / malformed payload that omits sessionId.
        // Cannot validate the cross-session guard without it, so we
        // reject conservatively rather than guessing.
        var future = new CompletableFuture<JsonNode>();
        registry.put("perm-no-sid", pending(SESSION_ID, future));
        var bad = mapper.createObjectNode();
        bad.put("method", "permission.response");
        bad.putObject("params").put("requestId", "perm-no-sid").put("approved", true);
        assertThat(handler.routePermissionResponse(bad)).isFalse();
        assertThat(future.isDone()).isFalse();
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
        bad.putObject("params").put("approved", true).put("sessionId", SESSION_ID);
        assertThat(handler.routePermissionResponse(bad)).isFalse();
    }

    @Test
    void returnsFalseWhenRequestIdIsEmptyString() {
        // Daemon's UUID-derived ids never produce empty strings, but a
        // fuzzed/manual client could — guard against it.
        assertThat(handler.routePermissionResponse(response("", SESSION_ID, true))).isFalse();
    }

    @Test
    void returnsFalseForNullMessage() {
        assertThat(handler.routePermissionResponse(null)).isFalse();
    }
}
