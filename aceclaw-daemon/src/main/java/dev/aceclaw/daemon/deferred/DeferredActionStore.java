package dev.aceclaw.daemon.deferred;

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
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Persistent store for deferred action definitions.
 *
 * <p>Actions are stored in {@code ~/.aceclaw/deferred/actions.json} as a JSON array.
 * All mutations are atomic (write to temp file, then rename) and thread-safe.
 */
public final class DeferredActionStore {

    private static final Logger log = LoggerFactory.getLogger(DeferredActionStore.class);
    private static final String ACTIONS_FILE = "actions.json";

    private final Path deferredDir;
    private final Path actionsFile;
    private final ObjectMapper mapper;
    private final ReadWriteLock lock = new ReentrantReadWriteLock();
    /** Serializes file I/O in save() to prevent last-write-wins data loss. */
    private final ReentrantLock saveLock = new ReentrantLock();

    /** In-memory cache of actions, keyed by action id. */
    private final Map<String, DeferredAction> actions = new LinkedHashMap<>();

    public DeferredActionStore(Path homeDir) {
        this.deferredDir = homeDir.resolve("deferred");
        this.actionsFile = deferredDir.resolve(ACTIONS_FILE);
        this.mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        mapper.enable(SerializationFeature.INDENT_OUTPUT);
    }

    /**
     * Loads actions from disk into the in-memory cache.
     * If the file does not exist, starts with an empty set.
     */
    public void load() throws IOException {
        lock.writeLock().lock();
        try {
            actions.clear();
            if (Files.isRegularFile(actionsFile)) {
                List<DeferredAction> loaded = mapper.readValue(
                        actionsFile.toFile(), new TypeReference<List<DeferredAction>>() {});
                for (DeferredAction action : loaded) {
                    actions.put(action.actionId(), action);
                }
                log.info("Loaded {} deferred action(s) from {}", actions.size(), actionsFile);
            } else {
                log.debug("No actions.json found at {}, starting with empty action list", actionsFile);
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Persists the current in-memory actions to disk atomically.
     */
    public void save() throws IOException {
        // Serialize the entire snapshot-read + file-write sequence to prevent
        // concurrent saves from causing last-write-wins data loss
        saveLock.lock();
        try {
            lock.readLock().lock();
            List<DeferredAction> snapshot;
            try {
                snapshot = new ArrayList<>(actions.values());
            } finally {
                lock.readLock().unlock();
            }

            Files.createDirectories(deferredDir);
            Path tempFile = Files.createTempFile(deferredDir, "actions-", ".tmp");
            try {
                mapper.writeValue(tempFile.toFile(), snapshot);
                try {
                    Files.move(tempFile, actionsFile, StandardCopyOption.REPLACE_EXISTING,
                            StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException e) {
                    Files.move(tempFile, actionsFile, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                try {
                    Files.deleteIfExists(tempFile);
                } catch (IOException ignored) {
                    // Best-effort cleanup
                }
            }
            log.debug("Saved {} deferred action(s) to {}", snapshot.size(), actionsFile);
        } finally {
            saveLock.unlock();
        }
    }

    /**
     * Returns all actions (snapshot).
     */
    public List<DeferredAction> all() {
        lock.readLock().lock();
        try {
            return List.copyOf(actions.values());
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Returns all actions in PENDING state (snapshot).
     */
    public List<DeferredAction> allPending() {
        lock.readLock().lock();
        try {
            return actions.values().stream()
                    .filter(a -> a.state() == DeferredActionState.PENDING)
                    .toList();
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Returns all actions for a given session (snapshot).
     */
    public List<DeferredAction> bySession(String sessionId) {
        java.util.Objects.requireNonNull(sessionId, "sessionId");
        lock.readLock().lock();
        try {
            return actions.values().stream()
                    .filter(a -> a.sessionId().equals(sessionId))
                    .toList();
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Retrieves an action by id.
     */
    public Optional<DeferredAction> get(String actionId) {
        lock.readLock().lock();
        try {
            return Optional.ofNullable(actions.get(actionId));
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Finds an action by idempotency key.
     */
    public Optional<DeferredAction> findByIdempotencyKey(String idempotencyKey) {
        java.util.Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        lock.readLock().lock();
        try {
            return actions.values().stream()
                    .filter(a -> a.idempotencyKey().equals(idempotencyKey)
                            && a.state() == DeferredActionState.PENDING)
                    .findFirst();
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Adds or updates an action. Does NOT auto-save to disk.
     */
    public void put(DeferredAction action) {
        lock.writeLock().lock();
        try {
            actions.put(action.actionId(), action);
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Removes an action by id. Does NOT auto-save to disk.
     *
     * @return true if the action existed and was removed
     */
    public boolean remove(String actionId) {
        lock.writeLock().lock();
        try {
            return actions.remove(actionId) != null;
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Returns the number of stored actions.
     */
    public int size() {
        lock.readLock().lock();
        try {
            return actions.size();
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Returns the number of PENDING actions for a given session.
     */
    public int pendingCountForSession(String sessionId) {
        java.util.Objects.requireNonNull(sessionId, "sessionId");
        lock.readLock().lock();
        try {
            return (int) actions.values().stream()
                    .filter(a -> a.sessionId().equals(sessionId)
                            && a.state() == DeferredActionState.PENDING)
                    .count();
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Returns the total number of PENDING actions across all sessions.
     */
    public int totalPendingCount() {
        lock.readLock().lock();
        try {
            return (int) actions.values().stream()
                    .filter(a -> a.state() == DeferredActionState.PENDING)
                    .count();
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Returns the path to the actions file.
     */
    public Path actionsFile() {
        return actionsFile;
    }
}
