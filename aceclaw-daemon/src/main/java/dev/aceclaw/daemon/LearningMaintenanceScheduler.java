package dev.aceclaw.daemon;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntSupplier;
import java.util.function.LongSupplier;

/**
 * Schedules periodic learning maintenance on top of the session-close pipeline.
 *
 * <p>The session-close callback already performs per-session extraction and indexing.
 * This scheduler adds heavier maintenance passes such as consolidation, cross-session
 * mining, and trend detection using time-based, session-count, size, and idle triggers.
 */
public final class LearningMaintenanceScheduler {

    private static final Logger log = LoggerFactory.getLogger(LearningMaintenanceScheduler.class);

    private final Config config;
    private final Clock clock;
    private final IntSupplier activeSessionCount;
    private final WorkspaceMemoryProbe largestMemoryFileBytes;
    private final MaintenancePipeline pipeline;
    private final LearningMaintenanceRecoveryStore recoveryStore;
    private final Object lifecycleLock = new Object();

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean maintenanceRunning = new AtomicBoolean(false);
    private final AtomicInteger sessionsSinceLastRun = new AtomicInteger();
    private final Map<String, WorkspaceScope> knownScopes = new ConcurrentHashMap<>();
    private final Map<String, WorkspaceScope> pendingScopes = new ConcurrentHashMap<>();
    private final Map<String, WorkspaceScope> recoveryScopes = new ConcurrentHashMap<>();

    private ScheduledExecutorService scheduler;
    private volatile Instant lastRunAt;
    private volatile Instant lastActiveAt;
    private volatile boolean idleTriggerArmed = true;
    private volatile boolean sizeTriggerArmed = true;

    public LearningMaintenanceScheduler(Config config,
                                        Clock clock,
                                        IntSupplier activeSessionCount,
                                        WorkspaceMemoryProbe largestMemoryFileBytes,
                                        MaintenancePipeline pipeline,
                                        LearningMaintenanceRecoveryStore recoveryStore) {
        this.config = Objects.requireNonNull(config, "config");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.activeSessionCount = Objects.requireNonNull(activeSessionCount, "activeSessionCount");
        this.largestMemoryFileBytes = Objects.requireNonNull(largestMemoryFileBytes, "largestMemoryFileBytes");
        this.pipeline = Objects.requireNonNull(pipeline, "pipeline");
        this.recoveryStore = recoveryStore;
        this.lastRunAt = clock.instant();
        this.lastActiveAt = clock.instant();
    }

    public void start() {
        synchronized (lifecycleLock) {
            if (running.get()) {
                log.debug("LearningMaintenanceScheduler already running");
                return;
            }
            var nextScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
                var thread = new Thread(r, "learning-maintenance-scheduler");
                thread.setDaemon(true);
                return thread;
            });
            long tickMillis = Math.max(1, config.tickInterval().toMillis());
            nextScheduler.scheduleAtFixedRate(this::tick, tickMillis, tickMillis, TimeUnit.MILLISECONDS);
            scheduler = nextScheduler;
            running.set(true);
        }
        log.info("LearningMaintenanceScheduler started: tick={}s timeTrigger={}h sessionTrigger={} sizeTrigger={}KB idleTrigger={}m",
                config.tickInterval().toSeconds(),
                config.timeTriggerInterval().toHours(),
                config.sessionCountTrigger(),
                config.sizeTriggerBytes() / 1024,
                config.idleTriggerInterval().toMinutes());
    }

    public void stop() {
        ScheduledExecutorService currentScheduler;
        synchronized (lifecycleLock) {
            if (!running.get()) {
                return;
            }
            running.set(false);
            currentScheduler = scheduler;
            scheduler = null;
        }
        if (currentScheduler != null) {
            currentScheduler.shutdown();
            try {
                if (!currentScheduler.awaitTermination(10, TimeUnit.SECONDS)) {
                    currentScheduler.shutdownNow();
                }
            } catch (InterruptedException e) {
                currentScheduler.shutdownNow();
                Thread.currentThread().interrupt();
            } finally {
                maintenanceRunning.set(false);
                sessionsSinceLastRun.set(0);
            }
        }
        log.info("LearningMaintenanceScheduler stopped");
    }

    public boolean isRunning() {
        return running.get();
    }

    boolean isMaintenanceRunningForTest() {
        return maintenanceRunning.get();
    }

    public void onSessionClosed(String workspaceHash, Path workingDir) {
        if (!running.get()) {
            return;
        }
        var scope = registerScope(workspaceHash, workingDir);
        sessionsSinceLastRun.incrementAndGet();
        if (recoveryScopes.containsKey(scope.workspaceHash())) {
            tryTrigger(Trigger.RECOVERY);
            return;
        }
        tryTrigger(Trigger.SESSION_COUNT);
    }

    public void registerWorkspace(String workspaceHash, Path workingDir) {
        if (!running.get()) {
            return;
        }
        registerScope(workspaceHash, workingDir);
        tryTrigger(Trigger.RECOVERY);
    }

    void tick() {
        if (!running.get()) {
            return;
        }
        try {
            int active = Math.max(0, activeSessionCount.getAsInt());
            Instant now = clock.instant();
            if (active > 0) {
                lastActiveAt = now;
                idleTriggerArmed = true;
            }
            tryTrigger(Trigger.RECOVERY);
            tryTrigger(Trigger.SIZE_THRESHOLD);
            tryTrigger(Trigger.TIME_INTERVAL);
            tryTrigger(Trigger.IDLE_INTERVAL);
        } catch (Exception e) {
            log.warn("Learning maintenance tick failed: {}", e.getMessage());
        }
    }

    private void tryTrigger(Trigger trigger) {
        if (!running.get()) {
            return;
        }
        if (!shouldTrigger(trigger)) {
            return;
        }
        var scopesToRun = scopesFor(trigger);
        if (scopesToRun.isEmpty()) {
            return;
        }
        if (!maintenanceRunning.compareAndSet(false, true)) {
            return;
        }
        int sessionsAtStart = trigger == Trigger.RECOVERY ? 0 : sessionsSinceLastRun.get();

        Thread.ofVirtual().name("learning-maintenance-" + trigger.id()).start(() -> {
            var completedScopes = new HashSet<WorkspaceScope>();
            WorkspaceScope activeScope = null;
            try {
                for (var scope : scopesToRun) {
                    activeScope = scope;
                    if (recoveryStore != null) {
                        recoveryStore.markStarted(scope.workingDir(), scope.workspaceHash(), trigger.id());
                    }
                    pipeline.run(trigger.id(), scope);
                    if (recoveryStore != null) {
                        recoveryStore.clear(scope.workingDir());
                    }
                    pendingScopes.remove(scope.workspaceHash(), scope);
                    recoveryScopes.remove(scope.workspaceHash(), scope);
                    completedScopes.add(scope);
                }
                lastRunAt = clock.instant();
                sessionsSinceLastRun.updateAndGet(current -> Math.max(0, current - sessionsAtStart));
                if (trigger == Trigger.SIZE_THRESHOLD) {
                    sizeTriggerArmed = false;
                }
                if (trigger == Trigger.IDLE_INTERVAL) {
                    idleTriggerArmed = false;
                }
                log.info("Learning maintenance completed via trigger={}", trigger.id());
            } catch (Exception e) {
                for (var scope : scopesToRun) {
                    if (completedScopes.contains(scope)) {
                        continue;
                    }
                    if (scope.equals(activeScope) && recoveryStore != null) {
                        try {
                            recoveryStore.markFailed(scope.workingDir(), scope.workspaceHash(), trigger.id(), e);
                        } catch (Exception ignored) {
                            // best-effort recovery metadata only
                        }
                    }
                    recoveryScopes.put(scope.workspaceHash(), scope);
                }
                log.warn("Learning maintenance failed via trigger={}: {}", trigger.id(), e.getMessage());
            } finally {
                maintenanceRunning.set(false);
            }
        });
    }

    private boolean shouldTrigger(Trigger trigger) {
        return switch (trigger) {
            case RECOVERY -> !recoveryScopes.isEmpty();
            case SESSION_COUNT ->
                    config.sessionCountTrigger() > 0
                            && sessionsSinceLastRun.get() >= config.sessionCountTrigger()
                            && !pendingScopes.isEmpty();
            case TIME_INTERVAL -> Duration.between(lastRunAt, clock.instant())
                    .compareTo(config.timeTriggerInterval()) >= 0
                    && !knownScopes.isEmpty();
            case SIZE_THRESHOLD -> {
                long bytes = Math.max(0L, largestMemoryFileBytes.largestMemoryFileBytes(List.copyOf(knownScopes.values())));
                if (bytes <= config.sizeTriggerBytes()) {
                    sizeTriggerArmed = true;
                    yield false;
                }
                yield sizeTriggerArmed;
            }
            case IDLE_INTERVAL -> activeSessionCount.getAsInt() == 0
                    && idleTriggerArmed
                    && !knownScopes.isEmpty()
                    && Duration.between(lastActiveAt, clock.instant()).compareTo(config.idleTriggerInterval()) >= 0;
        };
    }

    private List<WorkspaceScope> scopesFor(Trigger trigger) {
        var source = switch (trigger) {
            case RECOVERY -> recoveryScopes;
            case SESSION_COUNT -> pendingScopes;
            default -> knownScopes;
        };
        return List.copyOf(new ArrayList<>(new LinkedHashMap<>(source).values()));
    }

    private WorkspaceScope registerScope(String workspaceHash, Path workingDir) {
        var scope = new WorkspaceScope(workspaceHash, workingDir);
        knownScopes.put(scope.workspaceHash(), scope);
        pendingScopes.put(scope.workspaceHash(), scope);
        if (recoveryStore != null && recoveryStore.needsRecovery(scope.workingDir(), scope.workspaceHash())) {
            recoveryScopes.put(scope.workspaceHash(), scope);
        }
        return scope;
    }

    @FunctionalInterface
    public interface MaintenancePipeline {
        void run(String trigger, WorkspaceScope scope) throws Exception;
    }

    @FunctionalInterface
    public interface WorkspaceMemoryProbe {
        long largestMemoryFileBytes(List<WorkspaceScope> scopes);
    }

    public record WorkspaceScope(String workspaceHash, Path workingDir) {
        public WorkspaceScope {
            Objects.requireNonNull(workspaceHash, "workspaceHash");
            Objects.requireNonNull(workingDir, "workingDir");
            if (workspaceHash.isBlank()) {
                throw new IllegalArgumentException("workspaceHash must not be blank");
            }
            workingDir = workingDir.toAbsolutePath().normalize();
        }
    }

    public record Config(Duration timeTriggerInterval,
                         int sessionCountTrigger,
                         long sizeTriggerBytes,
                         Duration idleTriggerInterval,
                         Duration tickInterval) {
        public Config {
            Objects.requireNonNull(timeTriggerInterval, "timeTriggerInterval");
            Objects.requireNonNull(idleTriggerInterval, "idleTriggerInterval");
            Objects.requireNonNull(tickInterval, "tickInterval");
            if (timeTriggerInterval.isNegative() || timeTriggerInterval.isZero()) {
                throw new IllegalArgumentException("timeTriggerInterval must be positive");
            }
            if (sessionCountTrigger <= 0) {
                throw new IllegalArgumentException("sessionCountTrigger must be positive");
            }
            if (sizeTriggerBytes <= 0) {
                throw new IllegalArgumentException("sizeTriggerBytes must be positive");
            }
            if (idleTriggerInterval.isNegative() || idleTriggerInterval.isZero()) {
                throw new IllegalArgumentException("idleTriggerInterval must be positive");
            }
            if (tickInterval.isNegative() || tickInterval.isZero()) {
                throw new IllegalArgumentException("tickInterval must be positive");
            }
        }

        public static Config defaults(Duration tickInterval) {
            return new Config(
                    Duration.ofHours(6),
                    10,
                    50L * 1024L,
                    Duration.ofMinutes(5),
                    tickInterval);
        }
    }

    enum Trigger {
        RECOVERY("recovery"),
        TIME_INTERVAL("scheduled"),
        SESSION_COUNT("session-count"),
        SIZE_THRESHOLD("size-threshold"),
        IDLE_INTERVAL("idle");

        private final String id;

        Trigger(String id) {
            this.id = id;
        }

        String id() {
            return id;
        }
    }
}
