package dev.aceclaw.daemon;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

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
    /**
     * Stub StreamContext used as the originating-CLI handle stored on
     * {@code PendingPermission}. Records every {@code sendNotification}
     * call so the {@code permission.cancelled} fan-out tests can assert
     * the daemon poked the right context with the right payload.
     */
    private RecordingStreamContext recordingContext;

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
        // populating test entries. Canonical record ctor:
        //   (String sessionId, CompletableFuture future, StreamContext context)
        // The third arg was added when routePermissionResponse needed to
        // emit permission.cancelled back to the originating CLI (#437) —
        // the test scaffold mirrors the same shape so reflection stays
        // honest about the production record.
        Class<?> pendingPermissionClass = null;
        Class<?> streamContextClass = null;
        for (Class<?> inner : StreamingAgentHandler.class.getDeclaredClasses()) {
            if (inner.getSimpleName().equals("PendingPermission")) {
                pendingPermissionClass = inner;
            }
        }
        // StreamContext is a top-level interface in the same package; load
        // by name so we don't import an unstable internal type.
        streamContextClass = Class.forName("dev.aceclaw.daemon.StreamContext");
        assertThat(pendingPermissionClass).isNotNull();
        pendingPermissionCtor = pendingPermissionClass.getDeclaredConstructor(
                String.class, CompletableFuture.class, streamContextClass);
        pendingPermissionCtor.setAccessible(true);
        recordingContext = new RecordingStreamContext();
    }

    private Object pending(String sessionId, CompletableFuture<JsonNode> future) throws Exception {
        return pendingPermissionCtor.newInstance(sessionId, future, recordingContext);
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

    @Test
    void sendsPermissionCancelledToOriginatingContextOnApproved() throws Exception {
        // Successful WS-side resolution must fan out a permission.cancelled
        // notification on the originating CLI's StreamContext so its TUI
        // can dismiss the y/n modal. Without this the daemon-side tool
        // resumes silently and the operator's CLI keeps waiting on stdin
        // that no longer matters (#437).
        var future = new CompletableFuture<JsonNode>();
        registry.put("perm-route-1", pending(SESSION_ID, future));
        boolean routed = handler.routePermissionResponse(
                response("perm-route-1", SESSION_ID, true));
        assertThat(routed).isTrue();
        assertThat(recordingContext.notifications).hasSize(1);
        var sent = recordingContext.notifications.get(0);
        assertThat(sent.method).isEqualTo("permission.cancelled");
        var params = mapper.valueToTree(sent.params);
        assertThat(params.get("requestId").asText()).isEqualTo("perm-route-1");
        assertThat(params.get("approved").asBoolean()).isTrue();
        assertThat(params.get("via").asText()).isEqualTo("websocket");
    }

    @Test
    void sendsPermissionCancelledOnDeniedToo() throws Exception {
        // Denied is also a resolution: the CLI modal should dismiss with
        // an "approved=false" indicator, not stay on screen until the
        // 120s daemon-side deadline elapses.
        var future = new CompletableFuture<JsonNode>();
        registry.put("perm-route-deny", pending(SESSION_ID, future));
        handler.routePermissionResponse(
                response("perm-route-deny", SESSION_ID, false));
        assertThat(recordingContext.notifications).hasSize(1);
        var params = mapper.valueToTree(recordingContext.notifications.get(0).params);
        assertThat(params.get("approved").asBoolean()).isFalse();
    }

    @Test
    void doesNotSendCancellationWhenRouteFails() throws Exception {
        // If the routing itself fails — unknown id, sessionId mismatch,
        // already-completed future — there's no resolution to broadcast,
        // so the originating context must NOT see a stray
        // permission.cancelled. Pin against three failure modes.
        // a) Unknown requestId.
        handler.routePermissionResponse(response("perm-never", SESSION_ID, true));
        // b) sessionId mismatch.
        var futureA = new CompletableFuture<JsonNode>();
        registry.put("perm-x", pending("sess-A", futureA));
        handler.routePermissionResponse(response("perm-x", "sess-B-attacker", true));
        // c) Missing sessionId on the response.
        var futureB = new CompletableFuture<JsonNode>();
        registry.put("perm-y", pending(SESSION_ID, futureB));
        var noSid = mapper.createObjectNode();
        noSid.put("method", "permission.response");
        noSid.putObject("params").put("requestId", "perm-y").put("approved", true);
        handler.routePermissionResponse(noSid);

        assertThat(recordingContext.notifications)
                .as("no cancellation broadcast on failed routes")
                .isEmpty();
    }

    @Test
    void swallowsIOExceptionFromContextSend() throws Exception {
        // The cancellation send is best-effort — a CLI socket that's
        // mid-close shouldn't roll back the daemon-side resolution that
        // already took the WS path. Pin the IOException-swallow contract
        // so a future change doesn't accidentally let it escape and
        // crash the request thread.
        var throwingContext = new RecordingStreamContext();
        throwingContext.throwOnSend = new IOException("simulated socket-mid-close");
        // Replace the recorded context for THIS pending entry with the
        // throwing one — the constructor in setUp already used recordingContext.
        var future = new CompletableFuture<JsonNode>();
        registry.put("perm-throw",
                pendingPermissionCtor.newInstance(SESSION_ID, future, throwingContext));
        boolean routed = handler.routePermissionResponse(
                response("perm-throw", SESSION_ID, true));
        // Routing succeeded even though the cancellation send blew up.
        assertThat(routed).isTrue();
        assertThat(future.isDone()).isTrue();
    }

    /**
     * Recording stub — the cancel-fanout tests need a StreamContext that
     * captures every {@code sendNotification} payload (and optionally
     * throws on send). Implemented inline rather than using a mocking
     * library so the test stays self-contained.
     */
    private static final class RecordingStreamContext implements StreamContext {
        record Sent(String method, Object params) {}

        final List<Sent> notifications = new ArrayList<>();
        IOException throwOnSend;

        @Override
        public void sendNotification(String method, Object params) throws IOException {
            if (throwOnSend != null) throw throwOnSend;
            notifications.add(new Sent(method, params));
        }

        @Override
        public JsonNode readMessage() {
            throw new UnsupportedOperationException(
                    "read not used in routePermissionResponse tests");
        }
    }
}
