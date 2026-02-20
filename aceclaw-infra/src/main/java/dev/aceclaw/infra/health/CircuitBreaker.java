package dev.aceclaw.infra.health;

import dev.aceclaw.infra.event.EventBus;
import dev.aceclaw.infra.event.HealthEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * Thread-safe circuit breaker for protecting calls to external services.
 *
 * <p>State transitions:
 * <pre>
 *   CLOSED  —(failureThreshold reached)—→  OPEN
 *   OPEN    —(resetTimeout elapsed)—→       HALF_OPEN
 *   HALF_OPEN —(probe succeeds × halfOpenMaxProbes)—→  CLOSED
 *   HALF_OPEN —(probe fails)—→              OPEN
 * </pre>
 *
 * <p>Uses {@link AtomicReference} for lock-free thread safety. Publishes
 * {@link HealthEvent.StatusChanged} on state transitions when an {@link EventBus}
 * is provided.
 */
public final class CircuitBreaker {

    private static final Logger log = LoggerFactory.getLogger(CircuitBreaker.class);

    private final CircuitBreakerConfig config;
    private final EventBus eventBus;
    private final AtomicReference<CircuitBreakerState> state;

    /**
     * Functional interface for suppliers that throw checked exceptions.
     *
     * @param <T> the return type
     * @param <E> the exception type
     */
    @FunctionalInterface
    public interface CheckedSupplier<T, E extends Exception> {
        T get() throws E;
    }

    /**
     * Creates a circuit breaker with optional EventBus for health event publishing.
     *
     * @param config   circuit breaker configuration
     * @param eventBus event bus for publishing transitions (may be null)
     */
    public CircuitBreaker(CircuitBreakerConfig config, EventBus eventBus) {
        this.config = config;
        this.eventBus = eventBus;
        this.state = new AtomicReference<>(new CircuitBreakerState.Closed(0));
    }

    /**
     * Creates a circuit breaker without EventBus (standalone, for testing).
     */
    public CircuitBreaker(CircuitBreakerConfig config) {
        this(config, null);
    }

    /**
     * Executes the supplier through the circuit breaker.
     *
     * @param supplier the operation to protect
     * @param <T>      the return type
     * @return the supplier's result
     * @throws CircuitOpenException if the circuit is open
     */
    public <T> T execute(Supplier<T> supplier) {
        checkState();
        try {
            T result = supplier.get();
            recordSuccess();
            return result;
        } catch (Exception e) {
            recordFailure();
            throw e;
        }
    }

    /**
     * Executes a checked supplier through the circuit breaker.
     *
     * @param supplier the operation to protect
     * @param <T>      the return type
     * @param <E>      the checked exception type
     * @return the supplier's result
     * @throws E                    if the supplier throws
     * @throws CircuitOpenException if the circuit is open (unchecked)
     */
    public <T, E extends Exception> T executeChecked(CheckedSupplier<T, E> supplier) throws E {
        checkState();
        try {
            T result = supplier.get();
            recordSuccess();
            return result;
        } catch (CircuitOpenException e) {
            // Should not happen here (checkState throws before supplier), but be safe
            throw e;
        } catch (Exception e) {
            recordFailure();
            @SuppressWarnings("unchecked")
            E typed = (E) e;
            throw typed;
        }
    }

    /**
     * Returns the current circuit breaker state (snapshot).
     */
    public CircuitBreakerState currentState() {
        return maybeTransitionToHalfOpen(state.get());
    }

    /**
     * Returns the configuration.
     */
    public CircuitBreakerConfig config() {
        return config;
    }

    /**
     * Manually resets the circuit breaker to closed state.
     */
    public void reset() {
        var prev = state.getAndSet(new CircuitBreakerState.Closed(0));
        if (!(prev instanceof CircuitBreakerState.Closed c && c.consecutiveFailures() == 0)) {
            log.info("[{}] Circuit breaker manually reset", config.name());
            publishTransition(prev, new CircuitBreakerState.Closed(0));
        }
    }

    private void checkState() {
        while (true) {
            var current = state.get();
            switch (current) {
                case CircuitBreakerState.Closed _ -> {
                    return; // Allow through
                }
                case CircuitBreakerState.Open open -> {
                    if (resetTimeoutElapsed(open)) {
                        // Try to transition to half-open
                        var halfOpen = new CircuitBreakerState.HalfOpen(0);
                        if (state.compareAndSet(current, halfOpen)) {
                            log.info("[{}] Circuit breaker transitioning OPEN -> HALF_OPEN", config.name());
                            publishTransition(current, halfOpen);
                            return; // Allow this call as a probe
                        }
                        // CAS failed — another thread transitioned; retry
                        continue;
                    }
                    throw new CircuitOpenException(config.name(), config.resetTimeout());
                }
                case CircuitBreakerState.HalfOpen _ -> {
                    return; // Allow through as probe
                }
            }
        }
    }

    private void recordSuccess() {
        while (true) {
            var current = state.get();
            switch (current) {
                case CircuitBreakerState.Closed _ -> {
                    // Reset failure count on success
                    if (current instanceof CircuitBreakerState.Closed c && c.consecutiveFailures() > 0) {
                        var reset = new CircuitBreakerState.Closed(0);
                        if (state.compareAndSet(current, reset)) return;
                        continue; // CAS failed, retry
                    }
                    return;
                }
                case CircuitBreakerState.HalfOpen halfOpen -> {
                    int probes = halfOpen.successfulProbes() + 1;
                    if (probes >= config.halfOpenMaxProbes()) {
                        // Enough probes — close the circuit
                        var closed = new CircuitBreakerState.Closed(0);
                        if (state.compareAndSet(current, closed)) {
                            log.info("[{}] Circuit breaker closing: {} probes succeeded", config.name(), probes);
                            publishTransition(current, closed);
                            return;
                        }
                        continue;
                    }
                    // More probes needed
                    var updated = new CircuitBreakerState.HalfOpen(probes);
                    if (state.compareAndSet(current, updated)) return;
                    continue;
                }
                case CircuitBreakerState.Open _ -> {
                    // Shouldn't happen (we wouldn't execute in open state), but ignore
                    return;
                }
            }
        }
    }

    private void recordFailure() {
        while (true) {
            var current = state.get();
            switch (current) {
                case CircuitBreakerState.Closed closed -> {
                    int failures = closed.consecutiveFailures() + 1;
                    if (failures >= config.failureThreshold()) {
                        var open = new CircuitBreakerState.Open(Instant.now());
                        if (state.compareAndSet(current, open)) {
                            log.warn("[{}] Circuit breaker OPENED after {} consecutive failures",
                                    config.name(), failures);
                            publishTransition(current, open);
                            return;
                        }
                        continue;
                    }
                    var updated = new CircuitBreakerState.Closed(failures);
                    if (state.compareAndSet(current, updated)) return;
                    continue;
                }
                case CircuitBreakerState.HalfOpen _ -> {
                    // Probe failed — reopen
                    var open = new CircuitBreakerState.Open(Instant.now());
                    if (state.compareAndSet(current, open)) {
                        log.warn("[{}] Circuit breaker reopened: half-open probe failed", config.name());
                        publishTransition(current, open);
                        return;
                    }
                    continue;
                }
                case CircuitBreakerState.Open _ -> {
                    return; // Already open
                }
            }
        }
    }

    private boolean resetTimeoutElapsed(CircuitBreakerState.Open open) {
        return Duration.between(open.openedAt(), Instant.now()).compareTo(config.resetTimeout()) >= 0;
    }

    private CircuitBreakerState maybeTransitionToHalfOpen(CircuitBreakerState current) {
        if (current instanceof CircuitBreakerState.Open open && resetTimeoutElapsed(open)) {
            var halfOpen = new CircuitBreakerState.HalfOpen(0);
            if (state.compareAndSet(current, halfOpen)) {
                publishTransition(current, halfOpen);
                return halfOpen;
            }
            return state.get();
        }
        return current;
    }

    private void publishTransition(CircuitBreakerState from, CircuitBreakerState to) {
        if (eventBus == null) return;

        var previous = stateToHealthStatus(from);
        var current = stateToHealthStatus(to);
        if (previous == current) return;

        eventBus.publish(new HealthEvent.StatusChanged(
                "circuit-breaker:" + config.name(),
                previous, current,
                "State: " + stateLabel(to),
                Instant.now()));
    }

    static HealthEvent.Status stateToHealthStatus(CircuitBreakerState state) {
        return switch (state) {
            case CircuitBreakerState.Closed _ -> HealthEvent.Status.HEALTHY;
            case CircuitBreakerState.HalfOpen _ -> HealthEvent.Status.DEGRADED;
            case CircuitBreakerState.Open _ -> HealthEvent.Status.UNHEALTHY;
        };
    }

    private static String stateLabel(CircuitBreakerState state) {
        return switch (state) {
            case CircuitBreakerState.Closed c -> "CLOSED(failures=" + c.consecutiveFailures() + ")";
            case CircuitBreakerState.Open o -> "OPEN(since=" + o.openedAt() + ")";
            case CircuitBreakerState.HalfOpen h -> "HALF_OPEN(probes=" + h.successfulProbes() + ")";
        };
    }

    /**
     * Thrown when a call is rejected because the circuit is open.
     */
    public static final class CircuitOpenException extends RuntimeException {

        private final String circuitName;
        private final Duration resetTimeout;

        public CircuitOpenException(String circuitName, Duration resetTimeout) {
            super("Circuit breaker '" + circuitName + "' is open; retry after " + resetTimeout);
            this.circuitName = circuitName;
            this.resetTimeout = resetTimeout;
        }

        public String circuitName() { return circuitName; }
        public Duration resetTimeout() { return resetTimeout; }
    }
}
