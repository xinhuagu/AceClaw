package dev.aceclaw.infra.health;

import dev.aceclaw.infra.event.EventBus;
import dev.aceclaw.infra.event.HealthEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Aggregates {@link HealthCheck}s and provides an overall system health snapshot.
 *
 * <p>Runs a periodic virtual thread that invokes all registered checks,
 * computes the aggregate status (worst-status-wins), and publishes
 * {@link HealthEvent.StatusChanged} on transitions.
 */
public final class HealthMonitor {

    private static final Logger log = LoggerFactory.getLogger(HealthMonitor.class);

    private static final Duration DEFAULT_CHECK_INTERVAL = Duration.ofSeconds(15);

    private final List<HealthCheck> checks = new CopyOnWriteArrayList<>();
    private final EventBus eventBus;
    private final Duration checkInterval;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicReference<HealthEvent.Status> lastAggregateStatus =
            new AtomicReference<>(HealthEvent.Status.HEALTHY);
    private final Object monitorSignal = new Object();
    private final Map<String, HealthCheckResult> lastResults = new ConcurrentHashMap<>();
    private volatile Thread monitorThread;

    /**
     * Creates a HealthMonitor with optional EventBus and custom check interval.
     *
     * @param eventBus      event bus for publishing transitions (may be null)
     * @param checkInterval how often to run health checks
     */
    public HealthMonitor(EventBus eventBus, Duration checkInterval) {
        this.eventBus = eventBus;
        this.checkInterval = checkInterval != null ? checkInterval : DEFAULT_CHECK_INTERVAL;
    }

    /**
     * Creates a HealthMonitor with optional EventBus and default interval.
     */
    public HealthMonitor(EventBus eventBus) {
        this(eventBus, DEFAULT_CHECK_INTERVAL);
    }

    /**
     * Creates a standalone HealthMonitor (no EventBus, default interval).
     */
    public HealthMonitor() {
        this(null, DEFAULT_CHECK_INTERVAL);
    }

    /**
     * Registers a health check.
     */
    public void register(HealthCheck check) {
        checks.add(check);
        log.debug("Registered health check: {}", check.name());
    }

    /**
     * Starts the periodic monitoring thread.
     */
    public void start() {
        if (running.compareAndSet(false, true)) {
            // Run an initial check immediately
            runChecks();
            monitorThread = Thread.ofVirtual()
                    .name("health-monitor")
                    .start(this::monitorLoop);
            log.info("Health monitor started (interval: {}s, checks: {})",
                    checkInterval.toSeconds(), checks.size());
        }
    }

    /**
     * Stops the monitoring thread.
     */
    public void stop() {
        if (running.compareAndSet(true, false)) {
            synchronized (monitorSignal) {
                monitorSignal.notifyAll();
            }
            if (monitorThread != null) {
                monitorThread.interrupt();
            }
            log.info("Health monitor stopped");
        }
    }

    /**
     * Returns whether the monitor is running.
     */
    public boolean isRunning() {
        return running.get();
    }

    /**
     * Takes an immediate health snapshot (does NOT trigger a new check round;
     * returns the latest cached results).
     */
    public HealthSnapshot snapshot() {
        var results = Map.copyOf(lastResults);
        var aggregate = computeAggregateStatus(results);
        return new HealthSnapshot(aggregate, results, Instant.now());
    }

    /**
     * Forces an immediate check round and returns the snapshot.
     */
    public HealthSnapshot checkNow() {
        runChecks();
        return snapshot();
    }

    /**
     * Returns the number of registered health checks.
     */
    public int checkCount() {
        return checks.size();
    }

    private void monitorLoop() {
        while (running.get()) {
            try {
                synchronized (monitorSignal) {
                    monitorSignal.wait(checkInterval.toMillis());
                }
                if (running.get()) {
                    runChecks();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    private void runChecks() {
        for (var check : checks) {
            try {
                var result = check.check();
                lastResults.put(check.name(), result);
            } catch (Exception e) {
                log.error("Health check '{}' threw unexpectedly: {}", check.name(), e.getMessage(), e);
                lastResults.put(check.name(), HealthCheckResult.unhealthy("Check failed: " + e.getMessage()));
            }
        }

        var newAggregate = computeAggregateStatus(lastResults);
        var previous = lastAggregateStatus.getAndSet(newAggregate);
        if (previous != newAggregate) {
            log.info("Health status changed: {} -> {}", previous, newAggregate);
            if (eventBus != null) {
                eventBus.publish(new HealthEvent.StatusChanged(
                        "system", previous, newAggregate,
                        "Aggregate health changed",
                        Instant.now()));
            }
        }
    }

    private static HealthEvent.Status computeAggregateStatus(Map<String, HealthCheckResult> results) {
        if (results.isEmpty()) return HealthEvent.Status.HEALTHY;

        var worst = HealthEvent.Status.HEALTHY;
        for (var result : results.values()) {
            if (result.status().compareTo(worst) > 0) {
                worst = result.status();
            }
        }
        return worst;
    }

    /**
     * An immutable snapshot of the system's health at a point in time.
     *
     * @param status     aggregate status (worst-status-wins)
     * @param components per-component results
     * @param timestamp  when the snapshot was taken
     */
    public record HealthSnapshot(
            HealthEvent.Status status,
            Map<String, HealthCheckResult> components,
            Instant timestamp
    ) {
        public HealthSnapshot {
            components = Map.copyOf(components);
        }
    }
}
