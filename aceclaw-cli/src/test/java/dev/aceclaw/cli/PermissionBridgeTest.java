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

    private static void releasePendingRequests(PermissionBridge bridge) {
        for (var req : bridge.pendingSnapshot()) {
            bridge.submitAnswer(req.requestId(), new PermissionBridge.PermissionAnswer(false, false));
        }
    }
}
