package dev.aceclaw.infra.health;

import dev.aceclaw.infra.event.EventBus;
import dev.aceclaw.infra.event.HealthEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class HealthMonitorTest {

    private EventBus eventBus;

    @BeforeEach
    void setUp() {
        eventBus = new EventBus();
        eventBus.start();
    }

    @AfterEach
    void tearDown() {
        eventBus.stop();
    }

    @Test
    void emptyMonitorReportsHealthy() {
        var monitor = new HealthMonitor();
        var snapshot = monitor.checkNow();
        assertThat(snapshot.status()).isEqualTo(HealthEvent.Status.HEALTHY);
        assertThat(snapshot.components()).isEmpty();
    }

    @Test
    void singleHealthyCheckReportsHealthy() {
        var monitor = new HealthMonitor();
        monitor.register(staticCheck("comp-a", HealthCheckResult.healthy("all good")));

        var snapshot = monitor.checkNow();
        assertThat(snapshot.status()).isEqualTo(HealthEvent.Status.HEALTHY);
        assertThat(snapshot.components()).containsKey("comp-a");
        assertThat(snapshot.components().get("comp-a").status()).isEqualTo(HealthEvent.Status.HEALTHY);
    }

    @Test
    void worstStatusWinsAggregation() {
        var monitor = new HealthMonitor();
        monitor.register(staticCheck("healthy", HealthCheckResult.healthy()));
        monitor.register(staticCheck("degraded", HealthCheckResult.degraded("slow")));
        monitor.register(staticCheck("unhealthy", HealthCheckResult.unhealthy("down")));

        var snapshot = monitor.checkNow();
        assertThat(snapshot.status()).isEqualTo(HealthEvent.Status.UNHEALTHY);
        assertThat(snapshot.components()).hasSize(3);
    }

    @Test
    void degradedWinsOverHealthy() {
        var monitor = new HealthMonitor();
        monitor.register(staticCheck("a", HealthCheckResult.healthy()));
        monitor.register(staticCheck("b", HealthCheckResult.degraded("slow")));

        var snapshot = monitor.checkNow();
        assertThat(snapshot.status()).isEqualTo(HealthEvent.Status.DEGRADED);
    }

    @Test
    void publishesEventOnAggregateTransition() throws Exception {
        var latch = new CountDownLatch(1);
        var captured = new AtomicReference<HealthEvent.StatusChanged>();

        eventBus.subscribe(HealthEvent.class, event -> {
            if (event instanceof HealthEvent.StatusChanged sc && "system".equals(sc.component())) {
                captured.set(sc);
                latch.countDown();
            }
        });

        var mutableStatus = new AtomicReference<>(HealthCheckResult.healthy());
        var monitor = new HealthMonitor(eventBus);
        monitor.register(new HealthCheck() {
            @Override public String name() { return "mutable"; }
            @Override public HealthCheckResult check() { return mutableStatus.get(); }
        });

        // First check establishes baseline (HEALTHY)
        monitor.checkNow();

        // Change to unhealthy
        mutableStatus.set(HealthCheckResult.unhealthy("broken"));
        monitor.checkNow();

        assertThat(latch.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(captured.get().previous()).isEqualTo(HealthEvent.Status.HEALTHY);
        assertThat(captured.get().current()).isEqualTo(HealthEvent.Status.UNHEALTHY);
    }

    @Test
    void periodicCheckRunsAutomatically() throws Exception {
        var monitor = new HealthMonitor(null, Duration.ofMillis(100));
        var callCount = new java.util.concurrent.atomic.AtomicInteger(0);

        monitor.register(new HealthCheck() {
            @Override public String name() { return "counter"; }
            @Override public HealthCheckResult check() {
                callCount.incrementAndGet();
                return HealthCheckResult.healthy();
            }
        });

        monitor.start();
        try {
            // Wait for a few periodic checks (initial + periodic)
            Thread.sleep(350);
            // Should have been called at least 3 times (initial + ~3 periodic)
            assertThat(callCount.get()).isGreaterThanOrEqualTo(3);
        } finally {
            monitor.stop();
        }
    }

    @Test
    void snapshotIsImmutable() {
        var monitor = new HealthMonitor();
        monitor.register(staticCheck("a", HealthCheckResult.healthy()));
        var snapshot = monitor.checkNow();

        assertThat(snapshot.components()).hasSize(1);
        // Map.copyOf makes it unmodifiable
        org.junit.jupiter.api.Assertions.assertThrows(UnsupportedOperationException.class,
                () -> snapshot.components().put("b", HealthCheckResult.healthy()));
    }

    @Test
    void throwingCheckProducesUnhealthyResult() {
        var monitor = new HealthMonitor();
        monitor.register(new HealthCheck() {
            @Override public String name() { return "boom"; }
            @Override public HealthCheckResult check() { throw new RuntimeException("unexpected"); }
        });

        var snapshot = monitor.checkNow();
        assertThat(snapshot.status()).isEqualTo(HealthEvent.Status.UNHEALTHY);
        assertThat(snapshot.components().get("boom").detail()).contains("Check failed");
    }

    @Test
    void checkCountTracksRegistration() {
        var monitor = new HealthMonitor();
        assertThat(monitor.checkCount()).isZero();
        monitor.register(staticCheck("a", HealthCheckResult.healthy()));
        assertThat(monitor.checkCount()).isEqualTo(1);
        monitor.register(staticCheck("b", HealthCheckResult.healthy()));
        assertThat(monitor.checkCount()).isEqualTo(2);
    }

    private static HealthCheck staticCheck(String name, HealthCheckResult result) {
        return new HealthCheck() {
            @Override public String name() { return name; }
            @Override public HealthCheckResult check() { return result; }
        };
    }
}
