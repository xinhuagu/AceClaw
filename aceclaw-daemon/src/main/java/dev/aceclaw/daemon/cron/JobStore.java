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

    /** In-memory cache of jobs, keyed by job id. */
    private final Map<String, CronJob> jobs = new LinkedHashMap<>();

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
                        jobs.put(job.id(), job);
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
        mapper.writeValue(tempFile.toFile(), snapshot);

        try {
            Files.move(tempFile, jobsFile, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            // Fallback to non-atomic move on filesystems that don't support it
            Files.move(tempFile, jobsFile, StandardCopyOption.REPLACE_EXISTING);
        }
        log.debug("Saved {} cron job(s) to {}", snapshot.size(), jobsFile);
    }

    /**
     * Returns all jobs (snapshot).
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
     * Retrieves a job by id.
     */
    public Optional<CronJob> get(String id) {
        lock.readLock().lock();
        try {
            return Optional.ofNullable(jobs.get(id));
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Adds or updates a job. Does NOT auto-save to disk.
     */
    public void put(CronJob job) {
        lock.writeLock().lock();
        try {
            jobs.put(job.id(), job);
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Removes a job by id. Does NOT auto-save to disk.
     *
     * @return true if the job existed and was removed
     */
    public boolean remove(String id) {
        lock.writeLock().lock();
        try {
            return jobs.remove(id) != null;
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
