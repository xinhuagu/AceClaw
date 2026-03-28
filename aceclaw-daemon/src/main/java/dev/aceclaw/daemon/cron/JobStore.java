package dev.aceclaw.daemon.cron;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Persistent store for cron job definitions.
 *
 * <p>Jobs are stored in {@code ~/.aceclaw/cron/jobs.json} as a JSON array.
 * All mutations are atomic (write to temp file, then rename) and thread-safe.
 */
public final class JobStore {

    private static final Logger log = LoggerFactory.getLogger(JobStore.class);
    private static final String JOBS_FILE = "jobs.json";

    private final Path cronDir;
    private final Path jobsFile;
    private final ObjectMapper mapper;
    private final ReadWriteLock lock = new ReentrantReadWriteLock();

    /** Structured key for workspace-scoped job lookup. */
    record JobKey(String workspace, String id) {
        JobKey {
            // Normalize null workspace to empty string for consistent hashing
            workspace = workspace != null ? workspace : "";
        }

        static JobKey of(CronJob job) {
            return new JobKey(job.workspace(), job.id());
        }

        static JobKey of(String workspace, String id) {
            return new JobKey(workspace, id);
        }
    }

    /** In-memory cache of jobs, keyed by (workspace, id). */
    private final Map<JobKey, CronJob> jobs = new LinkedHashMap<>();

    public JobStore(Path homeDir) {
        this.cronDir = homeDir.resolve("cron");
        this.jobsFile = cronDir.resolve(JOBS_FILE);
        this.mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        mapper.enable(SerializationFeature.INDENT_OUTPUT);
    }

    /**
     * Loads jobs from disk into the in-memory cache.
     * If the file does not exist, starts with an empty set.
     */
    public void load() throws IOException {
        lock.writeLock().lock();
        try {
            jobs.clear();
            if (Files.isRegularFile(jobsFile)) {
                List<CronJob> loaded = mapper.readValue(
                        jobsFile.toFile(), new TypeReference<List<CronJob>>() {});
                for (CronJob job : loaded) {
                    // Validate expression on load
                    try {
                        CronExpression.parse(job.expression());
                        jobs.put(JobKey.of(job), job);
                    } catch (IllegalArgumentException e) {
                        log.warn("Skipping job '{}' with invalid cron expression '{}': {}",
                                job.id(), job.expression(), e.getMessage());
                    }
                }
                log.info("Loaded {} cron job(s) from {}", jobs.size(), jobsFile);
            } else {
                log.debug("No jobs.json found at {}, starting with empty job list", jobsFile);
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Persists the current in-memory jobs to disk atomically.
     */
    public void save() throws IOException {
        lock.readLock().lock();
        List<CronJob> snapshot;
        try {
            snapshot = new ArrayList<>(jobs.values());
        } finally {
            lock.readLock().unlock();
        }

        Files.createDirectories(cronDir);
        Path tempFile = cronDir.resolve(JOBS_FILE + ".tmp");
        try {
            mapper.writeValue(tempFile.toFile(), snapshot);
            try {
                Files.move(tempFile, jobsFile, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                // Fallback to non-atomic move on filesystems that don't support it
                Files.move(tempFile, jobsFile, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            // Clean up temp file if write or move failed
            try {
                Files.deleteIfExists(tempFile);
            } catch (IOException ignored) {
                // Best-effort cleanup
            }
        }
        log.debug("Saved {} cron job(s) to {}", snapshot.size(), jobsFile);
    }

    /**
     * Returns all jobs across all workspaces (snapshot).
     */
    public List<CronJob> all() {
        lock.readLock().lock();
        try {
            return List.copyOf(jobs.values());
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Returns jobs visible from the specified workspace (snapshot).
     *
     * <p>Includes both workspace-scoped jobs matching the given workspace AND
     * global jobs (null workspace, e.g. heartbeat jobs). This ensures heartbeat
     * and other daemon-global jobs remain visible in workspace-scoped views.
     *
     * @param workspace canonical workspace path (null returns only global jobs)
     */
    public List<CronJob> forWorkspace(String workspace) {
        lock.readLock().lock();
        try {
            return jobs.values().stream()
                    .filter(j -> j.workspace() == null
                            || (workspace != null && workspace.equals(j.workspace())))
                    .toList();
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Returns all enabled jobs (snapshot).
     */
    public List<CronJob> enabled() {
        lock.readLock().lock();
        try {
            return jobs.values().stream()
                    .filter(CronJob::enabled)
                    .toList();
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Retrieves a job by workspace and id.
     */
    public Optional<CronJob> get(String workspace, String id) {
        lock.readLock().lock();
        try {
            return Optional.ofNullable(jobs.get(JobKey.of(workspace, id)));
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Retrieves a job by id only (searches all workspaces). Use {@link #get(String, String)} for
     * workspace-scoped lookup. This method is for backward compatibility and scheduler use.
     */
    public Optional<CronJob> get(String id) {
        lock.readLock().lock();
        try {
            return jobs.values().stream()
                    .filter(j -> j.id().equals(id))
                    .findFirst();
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Adds or updates a job. Uses composite key (workspace + id). Does NOT auto-save to disk.
     */
    public void put(CronJob job) {
        lock.writeLock().lock();
        try {
            jobs.put(JobKey.of(job), job);
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Removes a job by workspace and id. Does NOT auto-save to disk.
     *
     * @return true if the job existed and was removed
     */
    public boolean remove(String workspace, String id) {
        lock.writeLock().lock();
        try {
            return jobs.remove(JobKey.of(workspace, id)) != null;
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Removes a job by id only (searches all workspaces). Backward-compatible.
     *
     * @return true if a job with that id existed and was removed
     */
    public boolean remove(String id) {
        lock.writeLock().lock();
        try {
            var key = jobs.entrySet().stream()
                    .filter(e -> e.getValue().id().equals(id))
                    .map(Map.Entry::getKey)
                    .findFirst();
            return key.map(k -> jobs.remove(k) != null).orElse(false);
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Returns the number of stored jobs.
     */
    public int size() {
        lock.readLock().lock();
        try {
            return jobs.size();
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Returns the path to the jobs file.
     */
    public Path jobsFile() {
        return jobsFile;
    }

}
