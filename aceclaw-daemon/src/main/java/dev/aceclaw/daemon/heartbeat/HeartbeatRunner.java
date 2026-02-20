package dev.aceclaw.daemon.heartbeat;

import dev.aceclaw.daemon.cron.CronJob;
import dev.aceclaw.daemon.cron.CronScheduler;
import dev.aceclaw.daemon.cron.JobStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Manages heartbeat tasks parsed from HEARTBEAT.md files.
 *
 * <p>HeartbeatRunner is a companion to {@link CronScheduler}. It parses HEARTBEAT.md
 * into {@link CronJob} objects (with {@code hb-} prefix) and syncs them into the
 * scheduler's {@link JobStore}. This reuses all existing cron infrastructure:
 * cron expressions, timeout, retry, circuit breaker, permissions, events.
 *
 * <p>Features:
 * <ul>
 *   <li>Initial sync on start: parse HEARTBEAT.md and create/update/remove heartbeat jobs</li>
 *   <li>Periodic re-sync: re-parses HEARTBEAT.md if file modified (lastModified check)</li>
 *   <li>Active hours: toggles heartbeat jobs enabled/disabled based on config</li>
 * </ul>
 */
public final class HeartbeatRunner {

    private static final Logger log = LoggerFactory.getLogger(HeartbeatRunner.class);

    /** Prefix for heartbeat job IDs to distinguish them from user-defined cron jobs. */
    public static final String JOB_ID_PREFIX = "hb-";

    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm");

    private final CronScheduler scheduler;
    private final Path homeDir;
    private final Path workingDir;
    private final String activeHours;
    private final int tickSeconds;

    private ScheduledExecutorService executor;
    private long lastProjectModified;
    private long lastGlobalModified;

    /**
     * Creates a HeartbeatRunner.
     *
     * @param scheduler    the cron scheduler to sync jobs into
     * @param homeDir      daemon home directory (~/.aceclaw)
     * @param workingDir   project working directory
     * @param activeHours  active hours in "HH:mm-HH:mm" format (null = always active)
     * @param tickSeconds  re-check interval in seconds
     */
    public HeartbeatRunner(CronScheduler scheduler, Path homeDir, Path workingDir,
                           String activeHours, int tickSeconds) {
        this.scheduler = scheduler;
        this.homeDir = homeDir;
        this.workingDir = workingDir;
        this.activeHours = activeHours;
        this.tickSeconds = tickSeconds > 0 ? tickSeconds : 60;
    }

    /**
     * Starts the heartbeat runner.
     *
     * <p>Performs initial sync from HEARTBEAT.md, then starts a background
     * thread that periodically re-syncs and manages active hours.
     */
    public void start() {
        // Initial sync
        syncFromFiles();

        executor = Executors.newSingleThreadScheduledExecutor(r -> {
            var t = new Thread(r, "heartbeat-runner");
            t.setDaemon(true);
            return t;
        });

        executor.scheduleAtFixedRate(this::tick, tickSeconds, tickSeconds, TimeUnit.SECONDS);
        log.info("HeartbeatRunner started: tick every {}s, activeHours={}",
                tickSeconds, activeHours != null ? activeHours : "always");
    }

    /**
     * Stops the heartbeat runner.
     */
    public void stop() {
        if (executor != null) {
            executor.shutdown();
            try {
                if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                    executor.shutdownNow();
                }
            } catch (InterruptedException e) {
                executor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
        log.info("HeartbeatRunner stopped");
    }

    /**
     * Periodic tick: re-sync if files changed, manage active hours.
     */
    void tick() {
        try {
            // Re-sync if any HEARTBEAT.md file was modified
            if (filesModified()) {
                syncFromFiles();
            }

            // Toggle heartbeat jobs based on active hours
            toggleActiveHours();
        } catch (Exception e) {
            log.error("HeartbeatRunner tick failed: {}", e.getMessage(), e);
        }
    }

    /**
     * Parses HEARTBEAT.md files and syncs tasks into the job store.
     *
     * <p>Sync logic:
     * <ul>
     *   <li>Add new heartbeat jobs not yet in the store</li>
     *   <li>Update existing heartbeat jobs if changed (prompt/schedule/timeout/tools)</li>
     *   <li>Remove stale {@code hb-*} jobs not in current HEARTBEAT.md</li>
     *   <li>Never touches non-heartbeat jobs (user cron jobs)</li>
     * </ul>
     */
    void syncFromFiles() {
        var tasks = HeartbeatLoader.load(homeDir, workingDir);
        var jobStore = scheduler.jobStore();

        // Build set of expected heartbeat job IDs
        var expectedIds = new HashSet<String>();
        for (HeartbeatTask task : tasks) {
            String jobId = toJobId(task.name());
            expectedIds.add(jobId);

            var existing = jobStore.get(jobId);
            if (existing.isPresent()) {
                // Update if changed
                CronJob existingJob = existing.get();
                if (hasChanged(existingJob, task)) {
                    var updated = new CronJob(
                            jobId, task.name(), task.schedule(), task.prompt(),
                            task.allowedTools(), task.timeoutSeconds(),
                            CronJob.DEFAULT_MAX_ITERATIONS, existingJob.enabled(),
                            CronJob.DEFAULT_RETRY_BACKOFF,
                            existingJob.lastRunAt(), existingJob.lastError(),
                            existingJob.consecutiveFailures());
                    jobStore.put(updated);
                    log.info("Heartbeat job updated: {}", jobId);
                }
            } else {
                // Create new
                var job = new CronJob(
                        jobId, task.name(), task.schedule(), task.prompt(),
                        task.allowedTools(), task.timeoutSeconds(),
                        CronJob.DEFAULT_MAX_ITERATIONS, true,
                        CronJob.DEFAULT_RETRY_BACKOFF, null, null, 0);
                jobStore.put(job);
                log.info("Heartbeat job created: {}", jobId);
            }
        }

        // Remove stale heartbeat jobs
        boolean removed = false;
        for (CronJob job : jobStore.all()) {
            if (job.id().startsWith(JOB_ID_PREFIX) && !expectedIds.contains(job.id())) {
                jobStore.remove(job.id());
                log.info("Heartbeat job removed (stale): {}", job.id());
                removed = true;
            }
        }

        // Persist changes
        if (!tasks.isEmpty() || removed) {
            try {
                jobStore.save();
            } catch (IOException e) {
                log.error("Failed to save job store after heartbeat sync: {}", e.getMessage());
            }
        }

        // Update file modification timestamps
        updateModifiedTimestamps();

        log.debug("Heartbeat sync: {} tasks, {} expected job IDs", tasks.size(), expectedIds.size());
    }

    /**
     * Toggles heartbeat jobs enabled/disabled based on active hours.
     */
    void toggleActiveHours() {
        if (activeHours == null || activeHours.isBlank()) {
            return; // No active hours configured = always enabled
        }

        boolean inWindow = isInActiveWindow(LocalTime.now());
        var jobStore = scheduler.jobStore();

        for (CronJob job : jobStore.all()) {
            if (job.id().startsWith(JOB_ID_PREFIX) && job.enabled() != inWindow) {
                jobStore.put(job.withEnabled(inWindow));
                log.debug("Heartbeat job {} {} (active hours: {})",
                        job.id(), inWindow ? "enabled" : "disabled", activeHours);
            }
        }
    }

    /**
     * Checks if the given time falls within the configured active hours window.
     *
     * <p>Handles wrapping (e.g., "22:00-06:00" means overnight).
     *
     * @param now the time to check
     * @return true if within active window, or if no active hours configured
     */
    boolean isInActiveWindow(LocalTime now) {
        if (activeHours == null || activeHours.isBlank()) {
            return true;
        }

        String[] parts = activeHours.split("-");
        if (parts.length != 2) {
            log.warn("Invalid activeHours format '{}', expected 'HH:mm-HH:mm'. Treating as always active.", activeHours);
            return true;
        }

        try {
            LocalTime start = LocalTime.parse(parts[0].trim(), TIME_FORMAT);
            LocalTime end = LocalTime.parse(parts[1].trim(), TIME_FORMAT);

            if (start.isBefore(end) || start.equals(end)) {
                // Normal range: e.g., 08:00-22:00
                return !now.isBefore(start) && now.isBefore(end);
            } else {
                // Wrapping range: e.g., 22:00-06:00 (overnight)
                return !now.isBefore(start) || now.isBefore(end);
            }
        } catch (DateTimeParseException e) {
            log.warn("Failed to parse activeHours '{}': {}. Treating as always active.",
                    activeHours, e.getMessage());
            return true;
        }
    }

    /**
     * Converts a task name to a job ID with the heartbeat prefix.
     * Slugifies the name: lowercase, spaces/special chars replaced with hyphens.
     */
    static String toJobId(String name) {
        String slug = name.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-|-$", "");
        return JOB_ID_PREFIX + slug;
    }

    /**
     * Checks if a HEARTBEAT.md file has been modified since last sync.
     */
    private boolean filesModified() {
        var files = HeartbeatLoader.discoverFiles(homeDir, workingDir);
        for (Path file : files) {
            try {
                long modified = Files.getLastModifiedTime(file).toMillis();
                String fileName = file.getFileName().toString();
                Path parent = file.getParent();

                // Check if this is the project file or global file
                if (parent != null && parent.getFileName() != null
                        && parent.getFileName().toString().equals(".aceclaw")) {
                    if (modified != lastProjectModified) return true;
                } else {
                    if (modified != lastGlobalModified) return true;
                }
            } catch (IOException e) {
                // File may have been deleted
                return true;
            }
        }
        return false;
    }

    /**
     * Updates stored modification timestamps for discovered files.
     */
    private void updateModifiedTimestamps() {
        lastProjectModified = 0;
        lastGlobalModified = 0;

        var files = HeartbeatLoader.discoverFiles(homeDir, workingDir);
        for (Path file : files) {
            try {
                long modified = Files.getLastModifiedTime(file).toMillis();
                Path parent = file.getParent();

                if (parent != null && parent.getFileName() != null
                        && parent.getFileName().toString().equals(".aceclaw")) {
                    lastProjectModified = modified;
                } else {
                    lastGlobalModified = modified;
                }
            } catch (IOException e) {
                // Ignore; will re-sync on next tick
            }
        }
    }

    /**
     * Checks if an existing job's properties differ from the parsed task.
     */
    private static boolean hasChanged(CronJob existing, HeartbeatTask task) {
        return !existing.expression().equals(task.schedule())
                || !existing.prompt().equals(task.prompt())
                || existing.timeoutSeconds() != task.timeoutSeconds()
                || !existing.allowedTools().equals(task.allowedTools());
    }
}
