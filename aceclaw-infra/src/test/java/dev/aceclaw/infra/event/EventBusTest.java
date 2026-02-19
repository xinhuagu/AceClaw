package dev.aceclaw.infra.event;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EventBusTest {

    private EventBus bus;

    @BeforeEach
    void setUp() {
        bus = new EventBus();
        bus.start();
    }

    @AfterEach
    void tearDown() {
        bus.stop();
    }

    @Test
    void publishDeliversToMatchingSubscriber() throws Exception {
        var latch = new CountDownLatch(1);
        var received = new AtomicReference<AgentEvent.TurnStarted>();

        bus.subscribe(AgentEvent.class, event -> {
            if (event instanceof AgentEvent.TurnStarted ts) {
                received.set(ts);
                latch.countDown();
            }
        });

        var event = new AgentEvent.TurnStarted("sess1", 1, Instant.now());
        bus.publish(event);

        assertThat(latch.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(received.get().sessionId()).isEqualTo("sess1");
        assertThat(received.get().turnNumber()).isEqualTo(1);
    }

    @Test
    void subscriberDoesNotReceiveUnrelatedEvents() throws Exception {
        var agentCount = new AtomicInteger(0);
        var toolCount = new AtomicInteger(0);

        var agentLatch = new CountDownLatch(1);
        var toolLatch = new CountDownLatch(1);
        var unexpected = new CountDownLatch(1);

        bus.subscribe(AgentEvent.class, event -> {
            if (agentCount.incrementAndGet() > 1) {
                unexpected.countDown();
            }
            agentLatch.countDown();
        });
        bus.subscribe(ToolEvent.class, event -> {
            if (toolCount.incrementAndGet() > 1) {
                unexpected.countDown();
            }
            toolLatch.countDown();
        });

        bus.publish(new AgentEvent.TurnStarted("s1", 1, Instant.now()));
        bus.publish(new ToolEvent.Invoked("s1", "bash", Instant.now()));

        assertThat(agentLatch.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(toolLatch.await(2, TimeUnit.SECONDS)).isTrue();

        // Verify no unexpected extra deliveries
        assertThat(unexpected.await(200, TimeUnit.MILLISECONDS)).isFalse();

        assertThat(agentCount.get()).isEqualTo(1);
        assertThat(toolCount.get()).isEqualTo(1);
    }

    @Test
    void multipleSubscribersReceiveSameEvent() throws Exception {
        var latch = new CountDownLatch(2);

        bus.subscribe(SystemEvent.class, event -> latch.countDown());
        bus.subscribe(SystemEvent.class, event -> latch.countDown());

        bus.publish(new SystemEvent.DaemonStarted(42, Instant.now()));

        assertThat(latch.await(2, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void stopPreventsDelivery() throws Exception {
        var delivered = new CountDownLatch(1);

        bus.subscribe(AgentEvent.class, event -> delivered.countDown());
        bus.stop();

        bus.publish(new AgentEvent.TurnStarted("s1", 1, Instant.now()));

        assertThat(delivered.await(200, TimeUnit.MILLISECONDS)).isFalse();
    }

    @Test
    void subscriberCountTracksSubscriptions() {
        assertThat(bus.subscriberCount()).isZero();

        bus.subscribe(AgentEvent.class, event -> {});
        assertThat(bus.subscriberCount()).isEqualTo(1);

        bus.subscribe(ToolEvent.class, event -> {});
        assertThat(bus.subscriberCount()).isEqualTo(2);
    }

    @Test
    void subscriberErrorDoesNotCrashBus() throws Exception {
        var latch = new CountDownLatch(1);

        // First subscriber throws
        bus.subscribe(AgentEvent.class, event -> {
            throw new RuntimeException("boom");
        });

        // Second subscriber should still receive
        bus.subscribe(AgentEvent.class, event -> latch.countDown());

        bus.publish(new AgentEvent.TurnStarted("s1", 1, Instant.now()));

        assertThat(latch.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(bus.isRunning()).isTrue();
    }

    @Test
    void sealedHierarchyEnablesPatternMatching() throws Exception {
        var latch = new CountDownLatch(1);
        var result = new AtomicReference<String>();

        bus.subscribe(AceClawEvent.class, event -> {
            var msg = switch (event) {
                case AgentEvent.TurnStarted ts -> "agent:" + ts.turnNumber();
                case ToolEvent.Invoked ti -> "tool:" + ti.toolName();
                case SystemEvent.DaemonStarted ds -> "boot:" + ds.bootMs();
                default -> "other";
            };
            result.set(msg);
            latch.countDown();
        });

        bus.publish(new SystemEvent.DaemonStarted(55, Instant.now()));

        assertThat(latch.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(result.get()).isEqualTo("boot:55");
    }

    @Test
    void unsubscribeStopsDeliveryAndRemoves() throws Exception {
        var count = new AtomicInteger(0);
        var latch = new CountDownLatch(1);

        var sub = bus.subscribe(AgentEvent.class, event -> {
            count.incrementAndGet();
            latch.countDown();
        });

        bus.publish(new AgentEvent.TurnStarted("s1", 1, Instant.now()));
        assertThat(latch.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(count.get()).isEqualTo(1);

        sub.unsubscribe();
        assertThat(bus.subscriberCount()).isZero();

        var noDelivery = new CountDownLatch(1);
        // Reuse count — if it increments, unsubscribe failed
        bus.publish(new AgentEvent.TurnStarted("s1", 2, Instant.now()));
        assertThat(noDelivery.await(200, TimeUnit.MILLISECONDS)).isFalse();
        assertThat(count.get()).isEqualTo(1);
    }

    @Test
    void queueCapacityDropsOldestEvents() throws Exception {
        var smallBus = new EventBus(2);
        smallBus.start();

        var blocker = new CountDownLatch(1);
        var delivered = new CountDownLatch(1);
        var count = new AtomicInteger(0);

        try {
            smallBus.subscribe(AgentEvent.class, event -> {
                try {
                    blocker.await();
                } catch (InterruptedException ignored) {}
                count.incrementAndGet();
                delivered.countDown();
            });

            // Publish more than capacity while drain is blocked
            for (int i = 0; i < 10; i++) {
                smallBus.publish(new AgentEvent.TurnStarted("s1", i, Instant.now()));
            }

            // Release the drain and wait for at least one delivery
            blocker.countDown();
            assertThat(delivered.await(2, TimeUnit.SECONDS)).isTrue();

            // Should have delivered at most capacity + 1 (one in-flight when blocked)
            assertThat(count.get()).isGreaterThan(0).isLessThanOrEqualTo(3);
        } finally {
            smallBus.stop();
        }
    }

    @Test
    void invalidCapacityThrows() {
        assertThatThrownBy(() -> new EventBus(0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("queueCapacity must be > 0");

        assertThatThrownBy(() -> new EventBus(-1))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
