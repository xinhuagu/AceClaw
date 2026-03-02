package dev.aceclaw.daemon;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for permission response routing with per-request CompletableFuture.
 * Verifies that concurrent permission requests are correctly routed to the
 * requesting thread without cross-delivery.
 */
class PermissionRoutingTest {

    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
    }

    /**
     * Simulates the monitor dispatch logic from CancelAwareStreamContext.monitorLoop.
     * Mirrors the production method-check: only permission.response routes through
     * pendingPermissions; resume.response always goes to the fallback queue.
     */
    private void dispatchResponse(
            ConcurrentHashMap<String, CompletableFuture<JsonNode>> pendingPermissions,
            LinkedBlockingQueue<JsonNode> unmatchedResponses,
            JsonNode message) {
        Objects.requireNonNull(pendingPermissions, "pendingPermissions");
        Objects.requireNonNull(unmatchedResponses, "unmatchedResponses");
        Objects.requireNonNull(message, "message");
        String method = message.has("method") ? message.get("method").asText("") : "";

        if ("permission.response".equals(method)) {
            var params = message.get("params");
            var rid = params != null && params.has("requestId")
                    ? params.get("requestId").asText() : null;
            if (rid != null) {
                var future = pendingPermissions.remove(rid);
                if (future != null) {
                    future.complete(message);
                }
                // Unmatched permission.response is dropped (not queued)
            }
            // permission.response without requestId is dropped
        } else if ("resume.response".equals(method)) {
            unmatchedResponses.offer(message);
        }
        // other methods (e.g. agent.cancel) are not routed here
    }

    private ObjectNode makePermissionResponse(String requestId, boolean approved, boolean remember) {
        Objects.requireNonNull(requestId, "requestId");
        var root = objectMapper.createObjectNode();
        root.put("method", "permission.response");
        var params = root.putObject("params");
        params.put("requestId", requestId);
        params.put("approved", approved);
        params.put("remember", remember);
        return root;
    }

    @Test
    void concurrentRoutingDeliversToCorrectFuture() throws Exception {
        var pendingPermissions = new ConcurrentHashMap<String, CompletableFuture<JsonNode>>();
        var unmatchedResponses = new LinkedBlockingQueue<JsonNode>();

        // Register 3 concurrent permission requests
        var future1 = new CompletableFuture<JsonNode>();
        var future2 = new CompletableFuture<JsonNode>();
        var future3 = new CompletableFuture<JsonNode>();
        pendingPermissions.put("perm-aaa", future1);
        pendingPermissions.put("perm-bbb", future2);
        pendingPermissions.put("perm-ccc", future3);

        // Dispatch responses in reverse order
        dispatchResponse(pendingPermissions, unmatchedResponses,
                makePermissionResponse("perm-ccc", true, false));
        dispatchResponse(pendingPermissions, unmatchedResponses,
                makePermissionResponse("perm-aaa", true, true));
        dispatchResponse(pendingPermissions, unmatchedResponses,
                makePermissionResponse("perm-bbb", false, false));

        // Each future should have received its own response
        var result1 = future1.get(1, TimeUnit.SECONDS);
        assertEquals("perm-aaa", result1.get("params").get("requestId").asText());
        assertTrue(result1.get("params").get("approved").asBoolean());
        assertTrue(result1.get("params").get("remember").asBoolean());

        var result2 = future2.get(1, TimeUnit.SECONDS);
        assertEquals("perm-bbb", result2.get("params").get("requestId").asText());
        assertFalse(result2.get("params").get("approved").asBoolean());

        var result3 = future3.get(1, TimeUnit.SECONDS);
        assertEquals("perm-ccc", result3.get("params").get("requestId").asText());
        assertTrue(result3.get("params").get("approved").asBoolean());

        assertNull(unmatchedResponses.poll());
        assertTrue(pendingPermissions.isEmpty());
    }

    @Test
    void unmatchedPermissionResponseIsDropped() {
        var pendingPermissions = new ConcurrentHashMap<String, CompletableFuture<JsonNode>>();
        var unmatchedResponses = new LinkedBlockingQueue<JsonNode>();

        var future = new CompletableFuture<JsonNode>();
        pendingPermissions.put("perm-aaa", future);

        // Dispatch a permission.response with unknown requestId — should be dropped, not queued
        dispatchResponse(pendingPermissions, unmatchedResponses,
                makePermissionResponse("perm-unknown", true, false));

        assertFalse(future.isDone());
        // Stale permission.response must NOT pollute the fallback queue
        assertNull(unmatchedResponses.poll());
        // Original future still pending
        assertEquals(1, pendingPermissions.size());
    }

    @Test
    void resumeResponseAlwaysGoesToFallbackQueue() {
        var pendingPermissions = new ConcurrentHashMap<String, CompletableFuture<JsonNode>>();
        var unmatchedResponses = new LinkedBlockingQueue<JsonNode>();

        // Register a permission request
        var future = new CompletableFuture<JsonNode>();
        pendingPermissions.put("perm-aaa", future);

        // resume.response — even if it happens to carry a requestId, it should NOT
        // complete the pending permission future
        var root = objectMapper.createObjectNode();
        root.put("method", "resume.response");
        var params = root.putObject("params");
        params.put("requestId", "perm-aaa");
        params.put("accepted", true);

        dispatchResponse(pendingPermissions, unmatchedResponses, root);

        // Future must NOT have been completed
        assertFalse(future.isDone());
        // Message went to fallback queue
        var fallback = unmatchedResponses.poll();
        assertNotNull(fallback);
        assertEquals("resume.response", fallback.get("method").asText());
        // Pending permission still registered
        assertEquals(1, pendingPermissions.size());
    }

    @Test
    void nonPermissionMethodDoesNotCompleteFuture() {
        var pendingPermissions = new ConcurrentHashMap<String, CompletableFuture<JsonNode>>();
        var unmatchedResponses = new LinkedBlockingQueue<JsonNode>();

        var future = new CompletableFuture<JsonNode>();
        pendingPermissions.put("perm-aaa", future);

        // agent.cancel with a requestId that happens to match — must NOT route to future
        var root = objectMapper.createObjectNode();
        root.put("method", "agent.cancel");
        root.putObject("params").put("requestId", "perm-aaa");

        dispatchResponse(pendingPermissions, unmatchedResponses, root);

        assertFalse(future.isDone());
        // agent.cancel is not routed by dispatchResponse at all
        assertNull(unmatchedResponses.poll());
        assertEquals(1, pendingPermissions.size());
    }

    @Test
    void futureTimesOutWhenNotCompleted() {
        var future = new CompletableFuture<JsonNode>();
        assertThrows(TimeoutException.class, () -> future.get(50, TimeUnit.MILLISECONDS));
    }

    @Test
    void unregisterCancelsPendingFuture() {
        var pendingPermissions = new ConcurrentHashMap<String, CompletableFuture<JsonNode>>();

        var future = new CompletableFuture<JsonNode>();
        pendingPermissions.put("perm-cleanup", future);

        // Simulate unregisterPermissionRequest
        var removed = pendingPermissions.remove("perm-cleanup");
        if (removed != null && !removed.isDone()) {
            removed.cancel(false);
        }

        assertTrue(future.isCancelled());
        assertTrue(pendingPermissions.isEmpty());
    }

    @Test
    void doubleUnregisterIsSafe() {
        var pendingPermissions = new ConcurrentHashMap<String, CompletableFuture<JsonNode>>();

        var future = new CompletableFuture<JsonNode>();
        pendingPermissions.put("perm-double", future);

        // First: monitor dispatch removes and completes
        var dispatched = pendingPermissions.remove("perm-double");
        assertNotNull(dispatched);
        dispatched.complete(objectMapper.createObjectNode());

        // Second: finally block calls unregister — returns null, no-op
        var second = pendingPermissions.remove("perm-double");
        assertNull(second);

        // Future is completed, not cancelled
        assertTrue(future.isDone());
        assertFalse(future.isCancelled());
    }

    @Test
    void concurrentThreadsEachGetOwnResponse() throws Exception {
        var pendingPermissions = new ConcurrentHashMap<String, CompletableFuture<JsonNode>>();
        var unmatchedResponses = new LinkedBlockingQueue<JsonNode>();

        int threadCount = 5;
        var requestIds = new ArrayList<String>(threadCount);
        var results = Collections.synchronizedList(new ArrayList<String>());
        var allReady = new CountDownLatch(threadCount);
        var allDone = new CountDownLatch(threadCount);

        // Register futures and start waiting threads
        for (int i = 0; i < threadCount; i++) {
            String rid = "perm-thread-" + i;
            requestIds.add(rid);
            var future = new CompletableFuture<JsonNode>();
            pendingPermissions.put(rid, future);

            Thread.ofVirtual().start(() -> {
                allReady.countDown();
                try {
                    var msg = future.get(5, TimeUnit.SECONDS);
                    results.add(msg.get("params").get("requestId").asText());
                } catch (Exception e) {
                    results.add("ERROR: " + e.getMessage());
                }
                allDone.countDown();
            });
        }

        assertTrue(allReady.await(2, TimeUnit.SECONDS));

        // Dispatch responses in shuffled order
        var shuffledIds = new ArrayList<>(requestIds);
        Collections.shuffle(shuffledIds);
        for (var rid : shuffledIds) {
            dispatchResponse(pendingPermissions, unmatchedResponses,
                    makePermissionResponse(rid, true, false));
        }

        assertTrue(allDone.await(5, TimeUnit.SECONDS));

        assertEquals(threadCount, results.size());
        for (int i = 0; i < threadCount; i++) {
            assertTrue(results.contains("perm-thread-" + i),
                    "Missing result for perm-thread-" + i);
        }

        assertTrue(pendingPermissions.isEmpty());
        assertNull(unmatchedResponses.poll());
    }
}
