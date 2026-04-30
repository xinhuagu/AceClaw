package dev.aceclaw.cli;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PermissionBridgeTest {

    @Test
    void requestPermission_blocksUntilAnswered() throws Exception {
        var bridge = new PermissionBridge();
        var req = new PermissionBridge.PermissionRequest("1", "bash", "run script", "req-1");

        var pool = Executors.newSingleThreadExecutor();
        try {
            Future<PermissionBridge.PermissionAnswer> future = pool.submit(() -> bridge.requestPermission(req));

            var pending = bridge.pollPending(1, TimeUnit.SECONDS);
            assertThat(pending).isNotNull();
            assertThat(pending.requestId()).isEqualTo("req-1");
            assertThat(bridge.pendingCount()).isEqualTo(0);

            bridge.submitAnswer("req-1", new PermissionBridge.PermissionAnswer(true, false));
            var answer = future.get(2, TimeUnit.SECONDS);
            assertThat(answer.approved()).isTrue();
            assertThat(answer.remember()).isFalse();
        } finally {
            releasePendingRequests(bridge);
            pool.shutdownNow();
        }
    }

    @Test
    void pendingSnapshot_containsQueuedRequests() throws Exception {
        var bridge = new PermissionBridge();
        var req1 = new PermissionBridge.PermissionRequest("1", "bash", "cmd1", "req-1");
        var req2 = new PermissionBridge.PermissionRequest("2", "bash", "cmd2", "req-2");

        var pool = Executors.newFixedThreadPool(2);
        try {
            Future<PermissionBridge.PermissionAnswer> f1 = pool.submit(() -> bridge.requestPermission(req1));
            Future<PermissionBridge.PermissionAnswer> f2 = pool.submit(() -> bridge.requestPermission(req2));

            // Wait until both requests are queued.
            for (int i = 0; i < 20 && bridge.pendingCount() < 2; i++) {
                Thread.sleep(20);
            }

            var snapshot = bridge.pendingSnapshot();
            assertThat(snapshot).hasSize(2);
            assertThat(snapshot).extracting(PermissionBridge.PermissionRequest::requestId)
                    .containsExactlyInAnyOrder("req-1", "req-2");

            bridge.submitAnswer("req-1", new PermissionBridge.PermissionAnswer(true, false));
            bridge.submitAnswer("req-2", new PermissionBridge.PermissionAnswer(false, false));

            assertThat(f1.get(2, TimeUnit.SECONDS).approved()).isTrue();
            assertThat(f2.get(2, TimeUnit.SECONDS).approved()).isFalse();
        } finally {
            releasePendingRequests(bridge);
            pool.shutdownNow();
        }
    }

    @Test
    void concurrentRequests_areResolvedByRequestId() throws Exception {
        var bridge = new PermissionBridge();
        int n = 6;
        var pool = Executors.newFixedThreadPool(n);
        try {
            List<Future<PermissionBridge.PermissionAnswer>> futures = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                int idx = i;
                futures.add(pool.submit(() -> bridge.requestPermission(new PermissionBridge.PermissionRequest(
                        String.valueOf(idx), "bash", "cmd-" + idx, "req-" + idx))));
            }

            var pending = new ArrayList<PermissionBridge.PermissionRequest>();
            for (int i = 0; i < n; i++) {
                var req = bridge.pollPending(2, TimeUnit.SECONDS);
                assertThat(req).isNotNull();
                pending.add(req);
            }

            for (var req : pending) {
                boolean approve = Integer.parseInt(req.taskId()) % 2 == 0;
                bridge.submitAnswer(req.requestId(), new PermissionBridge.PermissionAnswer(approve, false));
            }

            int approved = 0;
            int rejected = 0;
            for (var f : futures) {
                var ans = f.get(2, TimeUnit.SECONDS);
                if (ans.approved()) approved++;
                else rejected++;
            }
            assertThat(approved).isEqualTo(3);
            assertThat(rejected).isEqualTo(3);
        } finally {
            releasePendingRequests(bridge);
            pool.shutdownNow();
        }
    }

    @Test
    void timedRequestPermission_cleansUpPendingRequestOnTimeout() {
        var bridge = new PermissionBridge();
        var req = new PermissionBridge.PermissionRequest("1", "bash", "run script", "req-timeout");

        assertThatThrownBy(() -> bridge.requestPermission(req, 20, TimeUnit.MILLISECONDS))
                .isInstanceOf(TimeoutException.class);

        assertThat(bridge.pendingCount()).isZero();
        assertThat(bridge.pendingSnapshot()).isEmpty();
        assertThat(bridge.consumeResolvedAnswer("req-timeout")).isNull();
    }

    @Test
    void lateAnswerAfterTimeout_isDropped() {
        var bridge = new PermissionBridge();
        var req = new PermissionBridge.PermissionRequest("1", "bash", "run script", "req-late");

        assertThatThrownBy(() -> bridge.requestPermission(req, 20, TimeUnit.MILLISECONDS))
                .isInstanceOf(TimeoutException.class);

        bridge.submitAnswer("req-late", new PermissionBridge.PermissionAnswer(true, false));

        assertThat(bridge.consumeResolvedAnswer("req-late")).isNull();
        assertThat(bridge.pendingCount()).isZero();
    }

    @Test
    void interruptedBeforeEnqueue_cleansUpFuture() throws Exception {
        var bridge = new PermissionBridge();
        var req = new PermissionBridge.PermissionRequest("1", "bash", "run script", "req-interrupted");

        var pool = Executors.newSingleThreadExecutor();
        try {
            Future<?> future = pool.submit(() -> {
                Thread.currentThread().interrupt();
                assertThatThrownBy(() -> bridge.requestPermission(req))
                        .isInstanceOf(InterruptedException.class);
            });

            future.get(2, TimeUnit.SECONDS);

            assertThat(bridge.pendingCount()).isZero();
            assertThat(bridge.pendingSnapshot()).isEmpty();

            bridge.submitAnswer("req-interrupted", new PermissionBridge.PermissionAnswer(true, false));
            assertThat(bridge.consumeResolvedAnswer("req-interrupted")).isNull();
        } finally {
            releasePendingRequests(bridge);
            pool.shutdownNow();
        }
    }

    private static void releasePendingRequests(PermissionBridge bridge) {
        for (var req : bridge.pendingSnapshot()) {
            bridge.submitAnswer(req.requestId(), new PermissionBridge.PermissionAnswer(false, false));
        }
    }

    // ---- cancelExternal / consumeExternalCancellation (issue #437) -------

    @Test
    void cancelExternal_unblocksWaitingRequestPermission() throws Exception {
        // Daemon side resolved the permission via WS first; the CLI's
        // task thread is blocked on requestPermission and needs to wake
        // with the external answer so its bookkeeping (clearWaitingPermission
        // etc.) runs without showing the modal as "still active".
        var bridge = new PermissionBridge();
        var req = new PermissionBridge.PermissionRequest("1", "bash", "run script", "req-ext");

        var pool = Executors.newSingleThreadExecutor();
        try {
            Future<PermissionBridge.PermissionAnswer> future = pool.submit(() -> bridge.requestPermission(req));
            // Drain the pending queue so the modal-equivalent has "seen" it.
            var pending = bridge.pollPending(1, TimeUnit.SECONDS);
            assertThat(pending).isNotNull();

            bridge.cancelExternal("req-ext",
                    new PermissionBridge.PermissionAnswer(true, false, true));

            var answer = future.get(2, TimeUnit.SECONDS);
            assertThat(answer.approved()).isTrue();
            assertThat(answer.external())
                    .as("external flag flows through so TaskStreamReader skips the stale UDS send")
                    .isTrue();
        } finally {
            releasePendingRequests(bridge);
            pool.shutdownNow();
        }
    }

    @Test
    void consumeExternalCancellation_isOneShot() {
        // Modal's polling loop calls consumeExternalCancellation per
        // tick — the entry must clear on first read so the next tick
        // doesn't see a stale cancellation. Subsequent reads return null.
        var bridge = new PermissionBridge();
        bridge.cancelExternal("req-once",
                new PermissionBridge.PermissionAnswer(false, false, true));

        var first = bridge.consumeExternalCancellation("req-once");
        assertThat(first).isNotNull();
        assertThat(first.approved()).isFalse();
        assertThat(first.external()).isTrue();

        assertThat(bridge.consumeExternalCancellation("req-once")).isNull();
    }

    @Test
    void consumeResolvedAnswer_alsoClearsExternalCancellation() {
        // When the modal completes the local way (user typed y) but a
        // cancellation arrives a millisecond later from the daemon, the
        // cleanup hook in consumeResolvedAnswer must drop the stale
        // external entry so the externalCancellations map doesn't leak
        // for the lifetime of the CLI process.
        var bridge = new PermissionBridge();
        bridge.cancelExternal("req-leak",
                new PermissionBridge.PermissionAnswer(true, false, true));
        // Simulate the user-answer path that records a resolved answer
        // (in production this happens via submitAnswer + then the modal
        // calls consumeResolvedAnswer in its cleanup).
        bridge.submitAnswer("req-leak",
                new PermissionBridge.PermissionAnswer(false, false));
        // First consumeResolvedAnswer: sees the user's answer AND wipes
        // any pending external entry as a side-effect.
        bridge.consumeResolvedAnswer("req-leak");

        // External entry is gone too — no leak.
        assertThat(bridge.consumeExternalCancellation("req-leak")).isNull();
    }

    @Test
    void cancelExternal_arrivingBeforeRequestPermission_unblocksImmediately() throws Exception {
        // Race the codex P1 review caught: TaskStreamReader handles
        // permission.request on a virtual thread (#437), so the read
        // loop's permission.cancelled handler can race ahead of the
        // virtual thread's requestPermission call. cancelExternal
        // stores the answer in externalCancellations either way; the
        // requestPermission early-check then drains it instead of
        // blocking until the 115 s timeout.
        var bridge = new PermissionBridge();
        bridge.cancelExternal("req-early",
                new PermissionBridge.PermissionAnswer(true, false, true));

        var req = new PermissionBridge.PermissionRequest("1", "bash", "run script", "req-early");
        var pool = Executors.newSingleThreadExecutor();
        try {
            Future<PermissionBridge.PermissionAnswer> future = pool.submit(
                    () -> bridge.requestPermission(req, 1, TimeUnit.SECONDS));
            var answer = future.get(2, TimeUnit.SECONDS);
            assertThat(answer.approved()).isTrue();
            assertThat(answer.external()).isTrue();
            // External entry consumed → not lingering.
            assertThat(bridge.consumeExternalCancellation("req-early")).isNull();
        } finally {
            releasePendingRequests(bridge);
            pool.shutdownNow();
        }
    }

    @Test
    void cancelExternal_isIdempotent() {
        // Daemon could conceivably send two permission.cancelled frames
        // for the same requestId (rare, but possible across reconnects).
        // The second call must not flip the answer or throw.
        var bridge = new PermissionBridge();
        bridge.cancelExternal("req-dup",
                new PermissionBridge.PermissionAnswer(true, false, true));
        bridge.cancelExternal("req-dup",
                new PermissionBridge.PermissionAnswer(false, false, true));

        var consumed = bridge.consumeExternalCancellation("req-dup");
        assertThat(consumed).isNotNull();
        // First write wins (putIfAbsent semantics).
        assertThat(consumed.approved()).isTrue();
    }
}
