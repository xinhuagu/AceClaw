package dev.aceclaw.daemon;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link SessionEventBuffer}. The contract under test:
 *
 * <ul>
 *   <li>Append + snapshot returns insertion-order envelopes.</li>
 *   <li>Per-session cap evicts oldest, leaving the newest {@code capacity}.</li>
 *   <li>{@link SessionEventBuffer#clear} drops one session without affecting
 *       other sessions.</li>
 *   <li>Concurrent appenders + a snapshot reader don't corrupt state or
 *       throw {@link java.util.ConcurrentModificationException}.</li>
 * </ul>
 *
 * The hot path is "broadcast in Jetty thread, snapshot in another Jetty
 * thread" so the concurrency test exercises that exact shape.
 */
class SessionEventBufferTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void snapshotReturnsAppendedEnvelopesInOrder() {
        var buffer = new SessionEventBuffer();
        for (int i = 1; i <= 3; i++) {
            buffer.append("s1", envelopeWithEventId(i));
        }
        var snapshot = buffer.snapshot("s1");
        assertThat(snapshot).hasSize(3);
        assertThat(snapshot.get(0).get("eventId").asLong()).isEqualTo(1);
        assertThat(snapshot.get(2).get("eventId").asLong()).isEqualTo(3);
    }

    @Test
    void snapshotForUnknownSessionIsEmpty() {
        var buffer = new SessionEventBuffer();
        assertThat(buffer.snapshot("never-seen")).isEmpty();
    }

    @Test
    void capacityEvictsOldestEnvelopes() {
        // cap=3 makes the eviction fence easy to assert without flooding
        // the deque with junk.
        var buffer = new SessionEventBuffer(3);
        for (int i = 1; i <= 5; i++) {
            buffer.append("s1", envelopeWithEventId(i));
        }
        var snapshot = buffer.snapshot("s1");
        // Newest 3: eventId 3, 4, 5. Oldest two evicted.
        assertThat(snapshot).hasSize(3);
        assertThat(snapshot.get(0).get("eventId").asLong()).isEqualTo(3);
        assertThat(snapshot.get(2).get("eventId").asLong()).isEqualTo(5);
    }

    @Test
    void clearDropsOneSessionWithoutAffectingOthers() {
        var buffer = new SessionEventBuffer();
        buffer.append("s1", envelopeWithEventId(1));
        buffer.append("s2", envelopeWithEventId(1));
        buffer.append("s2", envelopeWithEventId(2));
        buffer.clear("s1");
        assertThat(buffer.snapshot("s1")).isEmpty();
        assertThat(buffer.snapshot("s2")).hasSize(2);
        assertThat(buffer.sessionCount()).isEqualTo(1);
    }

    @Test
    void concurrentAppendsAndSnapshotsAreSafe() throws InterruptedException {
        // 8 producers × 100 events = 800 envelopes total, well under the
        // default cap (no eviction during the race). A reader thread polls
        // snapshot() concurrently to provoke ArrayDeque iteration vs. mutation
        // races; without the deque's monitor this would throw or return
        // partial entries.
        var buffer = new SessionEventBuffer();
        var readyToStart = new CountDownLatch(1);
        var done = new CountDownLatch(9);
        var anyError = new AtomicInteger();

        var pool = Executors.newFixedThreadPool(9);
        try {
            for (int p = 0; p < 8; p++) {
                final int producerId = p;
                pool.submit(() -> {
                    try {
                        readyToStart.await();
                        for (int i = 0; i < 100; i++) {
                            buffer.append("s1", envelopeWithEventId(producerId * 100L + i));
                        }
                    } catch (Throwable t) {
                        anyError.incrementAndGet();
                    } finally {
                        done.countDown();
                    }
                });
            }
            pool.submit(() -> {
                try {
                    readyToStart.await();
                    while (done.getCount() > 1) {
                        // Just iterate the snapshot — corruption would surface
                        // here as a CME or NPE inside the deque iterator.
                        for (var env : buffer.snapshot("s1")) {
                            env.get("eventId");
                        }
                    }
                } catch (Throwable t) {
                    anyError.incrementAndGet();
                } finally {
                    done.countDown();
                }
            });
            readyToStart.countDown();
            assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdownNow();
        }
        assertThat(anyError.get()).isZero();
        assertThat(buffer.snapshot("s1")).hasSize(800);
    }

    @Test
    void rejectsNonPositiveCapacity() {
        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class, () -> new SessionEventBuffer(0));
        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class, () -> new SessionEventBuffer(-1));
    }

    private com.fasterxml.jackson.databind.node.ObjectNode envelopeWithEventId(long id) {
        var env = mapper.createObjectNode();
        env.put("eventId", id);
        env.put("sessionId", "s1");
        return env;
    }
}
