package dev.aceclaw.infra.health;

import dev.aceclaw.infra.event.EventBus;
import dev.aceclaw.infra.event.HealthEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CircuitBreakerTest {

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

    private CircuitBreakerConfig config(int threshold, Duration timeout, int probes) {
        return new CircuitBreakerConfig("test", threshold, timeout, probes);
    }

    @Test
    void startsInClosedState() {
        var cb = new CircuitBreaker(config(3, Duration.ofSeconds(10), 1));
        assertThat(cb.currentState()).isInstanceOf(CircuitBreakerState.Closed.class);
    }

    @Test
    void successKeepsCircuitClosed() {
        var cb = new CircuitBreaker(config(3, Duration.ofSeconds(10), 1));
        var result = cb.execute(() -> "ok");
        assertThat(result).isEqualTo("ok");
        assertThat(cb.currentState()).isInstanceOf(CircuitBreakerState.Closed.class);
    }

    @Test
    void failuresBelowThresholdKeepCircuitClosed() {
        var cb = new CircuitBreaker(config(3, Duration.ofSeconds(10), 1));

        for (int i = 0; i < 2; i++) {
            try {
                cb.execute(() -> { throw new RuntimeException("fail"); });
            } catch (RuntimeException ignored) {}
        }

        var state = cb.currentState();
        assertThat(state).isInstanceOf(CircuitBreakerState.Closed.class);
        assertThat(((CircuitBreakerState.Closed) state).consecutiveFailures()).isEqualTo(2);
    }

    @Test
    void failuresAtThresholdOpenCircuit() {
        var cb = new CircuitBreaker(config(3, Duration.ofSeconds(10), 1));

        for (int i = 0; i < 3; i++) {
            try {
                cb.execute(() -> { throw new RuntimeException("fail"); });
            } catch (RuntimeException ignored) {}
        }

        assertThat(cb.currentState()).isInstanceOf(CircuitBreakerState.Open.class);
    }

    @Test
    void openCircuitRejectsCallsWithCircuitOpenException() {
        var cb = new CircuitBreaker(config(1, Duration.ofSeconds(60), 1));

        try {
            cb.execute(() -> { throw new RuntimeException("fail"); });
        } catch (RuntimeException ignored) {}

        assertThatThrownBy(() -> cb.execute(() -> "should not run"))
                .isInstanceOf(CircuitBreaker.CircuitOpenException.class)
                .hasMessageContaining("test");
    }

    @Test
    void openTransitionsToHalfOpenAfterTimeout() {
        var cb = new CircuitBreaker(config(1, Duration.ofMillis(50), 1));

        try {
            cb.execute(() -> { throw new RuntimeException("fail"); });
        } catch (RuntimeException ignored) {}

        assertThat(cb.currentState()).isInstanceOf(CircuitBreakerState.Open.class);

        // Wait for reset timeout
        sleep(100);

        // currentState() should trigger the transition
        assertThat(cb.currentState()).isInstanceOf(CircuitBreakerState.HalfOpen.class);
    }

    @Test
    void halfOpenClosesAfterSuccessfulProbes() {
        var cb = new CircuitBreaker(config(1, Duration.ofMillis(50), 2));

        // Open the circuit
        try {
            cb.execute(() -> { throw new RuntimeException("fail"); });
        } catch (RuntimeException ignored) {}

        sleep(100);

        // First probe
        cb.execute(() -> "probe1");
        assertThat(cb.currentState()).isInstanceOf(CircuitBreakerState.HalfOpen.class);

        // Second probe — should close
        cb.execute(() -> "probe2");
        assertThat(cb.currentState()).isInstanceOf(CircuitBreakerState.Closed.class);
    }

    @Test
    void halfOpenReopensOnProbeFailure() {
        var cb = new CircuitBreaker(config(1, Duration.ofMillis(50), 2));

        // Open the circuit
        try {
            cb.execute(() -> { throw new RuntimeException("fail"); });
        } catch (RuntimeException ignored) {}

        sleep(100);

        // Probe that fails
        try {
            cb.execute(() -> { throw new RuntimeException("probe fail"); });
        } catch (RuntimeException ignored) {}

        assertThat(cb.currentState()).isInstanceOf(CircuitBreakerState.Open.class);
    }

    @Test
    void successResetsFailureCount() {
        var cb = new CircuitBreaker(config(3, Duration.ofSeconds(10), 1));

        // 2 failures
        for (int i = 0; i < 2; i++) {
            try {
                cb.execute(() -> { throw new RuntimeException("fail"); });
            } catch (RuntimeException ignored) {}
        }

        // 1 success resets
        cb.execute(() -> "ok");

        var state = (CircuitBreakerState.Closed) cb.currentState();
        assertThat(state.consecutiveFailures()).isZero();
    }

    @Test
    void manualResetClosesCircuit() {
        var cb = new CircuitBreaker(config(1, Duration.ofSeconds(60), 1));

        try {
            cb.execute(() -> { throw new RuntimeException("fail"); });
        } catch (RuntimeException ignored) {}

        assertThat(cb.currentState()).isInstanceOf(CircuitBreakerState.Open.class);

        cb.reset();

        assertThat(cb.currentState()).isInstanceOf(CircuitBreakerState.Closed.class);
        // Should accept calls again
        assertThat(cb.execute(() -> "ok")).isEqualTo("ok");
    }

    @Test
    void executeCheckedPropagatesCheckedException() {
        var cb = new CircuitBreaker(config(3, Duration.ofSeconds(10), 1));

        assertThatThrownBy(() ->
                cb.executeChecked(() -> { throw new java.io.IOException("disk full"); }))
                .isInstanceOf(java.io.IOException.class)
                .hasMessage("disk full");
    }

    @Test
    void publishesEventOnStateTransition() throws Exception {
        var latch = new CountDownLatch(1);
        var captured = new AtomicReference<HealthEvent.StatusChanged>();

        eventBus.subscribe(HealthEvent.class, event -> {
            if (event instanceof HealthEvent.StatusChanged sc) {
                captured.set(sc);
                latch.countDown();
            }
        });

        var cb = new CircuitBreaker(config(1, Duration.ofSeconds(60), 1), eventBus);

        try {
            cb.execute(() -> { throw new RuntimeException("fail"); });
        } catch (RuntimeException ignored) {}

        assertThat(latch.await(2, TimeUnit.SECONDS)).isTrue();

        var event = captured.get();
        assertThat(event.component()).isEqualTo("circuit-breaker:test");
        assertThat(event.previous()).isEqualTo(HealthEvent.Status.HEALTHY);
        assertThat(event.current()).isEqualTo(HealthEvent.Status.UNHEALTHY);
    }

    @Test
    void concurrentAccessIsSafe() throws Exception {
        var cb = new CircuitBreaker(config(100, Duration.ofMillis(50), 5));
        int threadCount = 20;
        var startLatch = new CountDownLatch(1);
        var doneLatch = new CountDownLatch(threadCount);
        var errors = new AtomicInteger(0);

        for (int i = 0; i < threadCount; i++) {
            final int idx = i;
            Thread.ofVirtual().start(() -> {
                try {
                    startLatch.await();
                    for (int j = 0; j < 50; j++) {
                        try {
                            if (idx % 2 == 0) {
                                cb.execute(() -> "ok");
                            } else {
                                cb.execute(() -> { throw new RuntimeException("fail"); });
                            }
                        } catch (RuntimeException ignored) {}
                    }
                } catch (Exception e) {
                    errors.incrementAndGet();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        assertThat(doneLatch.await(10, TimeUnit.SECONDS)).isTrue();
        assertThat(errors.get()).isZero();

        // State should be valid (one of the three states)
        var state = cb.currentState();
        assertThat(state).isInstanceOfAny(
                CircuitBreakerState.Closed.class,
                CircuitBreakerState.Open.class,
                CircuitBreakerState.HalfOpen.class);
    }

    @Test
    void configValidation() {
        assertThatThrownBy(() -> new CircuitBreakerConfig("", 1, Duration.ofSeconds(1), 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CircuitBreakerConfig("x", 0, Duration.ofSeconds(1), 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CircuitBreakerConfig("x", 1, Duration.ZERO, 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CircuitBreakerConfig("x", 1, Duration.ofSeconds(1), 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void defaultForLlmCreatesValidConfig() {
        var config = CircuitBreakerConfig.defaultForLlm();
        assertThat(config.name()).isEqualTo("llm");
        assertThat(config.failureThreshold()).isEqualTo(5);
        assertThat(config.resetTimeout()).isEqualTo(Duration.ofSeconds(30));
        assertThat(config.halfOpenMaxProbes()).isEqualTo(2);
    }

    private static void sleep(long millis) {
        LockSupport.parkNanos(millis * 1_000_000L);
    }
}
