package dev.aceclaw.infra.health;

import dev.aceclaw.infra.event.HealthEvent;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class CircuitBreakerHealthCheckTest {

    private CircuitBreakerConfig config() {
        return new CircuitBreakerConfig("test-cb", 2, Duration.ofMillis(50), 1);
    }

    @Test
    void closedCircuitReportsHealthy() {
        var cb = new CircuitBreaker(config());
        var hc = new CircuitBreakerHealthCheck(cb);

        var result = hc.check();
        assertThat(result.status()).isEqualTo(HealthEvent.Status.HEALTHY);
        assertThat(result.detail()).contains("no failures");
    }

    @Test
    void closedWithFailuresStillReportsHealthy() {
        var cb = new CircuitBreaker(config());
        try {
            cb.execute(() -> { throw new RuntimeException("fail"); });
        } catch (RuntimeException ignored) {}

        var hc = new CircuitBreakerHealthCheck(cb);
        var result = hc.check();
        assertThat(result.status()).isEqualTo(HealthEvent.Status.HEALTHY);
        assertThat(result.detail()).contains("1 recent failure");
    }

    @Test
    void openCircuitReportsUnhealthy() {
        var cb = new CircuitBreaker(config());
        // Reach threshold (2)
        for (int i = 0; i < 2; i++) {
            try { cb.execute(() -> { throw new RuntimeException("fail"); }); }
            catch (RuntimeException ignored) {}
        }

        var hc = new CircuitBreakerHealthCheck(cb);
        var result = hc.check();
        assertThat(result.status()).isEqualTo(HealthEvent.Status.UNHEALTHY);
        assertThat(result.detail()).contains("Circuit open");
    }

    @Test
    void halfOpenCircuitReportsDegraded() {
        var cb = new CircuitBreaker(config());
        // Open it
        for (int i = 0; i < 2; i++) {
            try { cb.execute(() -> { throw new RuntimeException("fail"); }); }
            catch (RuntimeException ignored) {}
        }

        // Wait for timeout to transition to half-open
        sleep(100);

        var hc = new CircuitBreakerHealthCheck(cb);
        var result = hc.check();
        assertThat(result.status()).isEqualTo(HealthEvent.Status.DEGRADED);
        assertThat(result.detail()).contains("half-open");
    }

    @Test
    void nameIncludesCircuitBreakerName() {
        var cb = new CircuitBreaker(config());
        var hc = new CircuitBreakerHealthCheck(cb);
        assertThat(hc.name()).isEqualTo("circuit-breaker:test-cb");
    }

    private static void sleep(long millis) {
        try { Thread.sleep(millis); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
