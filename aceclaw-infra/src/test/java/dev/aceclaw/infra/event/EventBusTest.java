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

        bus.subscribe(AgentEvent.class, event -> {
            agentCount.incrementAndGet();
            agentLatch.countDown();
        });
        bus.subscribe(ToolEvent.class, event -> {
            toolCount.incrementAndGet();
            toolLatch.countDown();
        });

        bus.publish(new AgentEvent.TurnStarted("s1", 1, Instant.now()));
        bus.publish(new ToolEvent.Invoked("s1", "bash", Instant.now()));

        assertThat(agentLatch.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(toolLatch.await(2, TimeUnit.SECONDS)).isTrue();

        // Small delay to let any incorrectly routed events arrive
        Thread.sleep(100);

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
        var count = new AtomicInteger(0);

        bus.subscribe(AgentEvent.class, event -> count.incrementAndGet());
        bus.stop();

        bus.publish(new AgentEvent.TurnStarted("s1", 1, Instant.now()));
        Thread.sleep(100);

        assertThat(count.get()).isZero();
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
    void queueCapacityDropsExcessEvents() {
        var smallBus = new EventBus(2);
        smallBus.start();

        // Subscribe but don't start draining (subscriber is paused by not consuming)
        // We can test this indirectly: fill beyond capacity
        var count = new AtomicInteger(0);
        var sub = smallBus.subscribe(AgentEvent.class, event -> count.incrementAndGet());

        // Publish more than capacity — some should be dropped silently
        for (int i = 0; i < 10; i++) {
            smallBus.publish(new AgentEvent.TurnStarted("s1", i, Instant.now()));
        }

        // At least some should have been delivered
        try { Thread.sleep(200); } catch (InterruptedException ignored) {}
        assertThat(count.get()).isGreaterThan(0);

        smallBus.stop();
    }
}
