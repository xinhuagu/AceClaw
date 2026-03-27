package dev.aceclaw.daemon;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks which interactive TUI client currently owns each workspace.
 *
 * <p>Enforces "one live TUI per workspace" without coupling to session lifecycle.
 * Sessions may outlive TUI attachments (e.g., for resume/persistence), and TUI
 * attachments may be released without destroying the underlying session.
 *
 * <p>Thread-safe: all operations are safe to call from any virtual thread.
 */
public final class WorkspaceAttachmentRegistry {

    private static final Logger log = LoggerFactory.getLogger(WorkspaceAttachmentRegistry.class);

    /** Attachments with no heartbeat for this duration are considered stale. */
    private static final Duration STALE_THRESHOLD = Duration.ofMinutes(2);

    /**
     * Metadata for an active interactive TUI attachment.
     *
     * @param sessionId    the session ID bound to this attachment
     * @param clientInstanceId identifier for the CLI process (e.g., "cli-default")
     * @param attachedAt   when the attachment was acquired
     * @param lastSeenAt   last heartbeat timestamp
     */
    public record Attachment(
            String sessionId,
            String clientInstanceId,
            Instant attachedAt,
            Instant lastSeenAt
    ) {
        public Attachment {
            Objects.requireNonNull(sessionId, "sessionId");
            Objects.requireNonNull(clientInstanceId, "clientInstanceId");
            attachedAt = attachedAt != null ? attachedAt : Instant.now();
            lastSeenAt = lastSeenAt != null ? lastSeenAt : attachedAt;
        }

        Attachment withHeartbeat() {
            return new Attachment(sessionId, clientInstanceId, attachedAt, Instant.now());
        }

        boolean isStale(Instant now) {
            return Duration.between(lastSeenAt, now).compareTo(STALE_THRESHOLD) > 0;
        }
    }

    /**
     * Result of an acquire attempt.
     */
    public sealed interface AcquireResult {
        record Acquired(Attachment attachment) implements AcquireResult {}
        record Conflict(Path workspace, Attachment existing) implements AcquireResult {}
    }

    private final ConcurrentHashMap<Path, Attachment> attachments = new ConcurrentHashMap<>();

    /**
     * Attempts to acquire an interactive TUI attachment for a workspace.
     *
     * <p>If the workspace already has a live (non-stale) attachment, returns
     * {@link AcquireResult.Conflict}. If the existing attachment is stale,
     * it is evicted and the new attachment proceeds.
     *
     * @param workspace canonical workspace path
     * @param sessionId the session ID for this TUI
     * @param clientInstanceId the CLI process identifier
     * @return {@link AcquireResult.Acquired} on success, {@link AcquireResult.Conflict} if occupied
     */
    public AcquireResult acquire(Path workspace, String sessionId, String clientInstanceId) {
        Objects.requireNonNull(workspace, "workspace");
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(clientInstanceId, "clientInstanceId");

        var now = Instant.now();
        var newAttachment = new Attachment(sessionId, clientInstanceId, now, now);

        // Atomically check-and-set using compute
        var result = new AcquireResult[1];
        attachments.compute(workspace, (key, existing) -> {
            if (existing == null || existing.isStale(now)) {
                if (existing != null) {
                    log.info("Evicting stale attachment for workspace={}: session={}, lastSeen={}",
                            key, existing.sessionId(), existing.lastSeenAt());
                }
                result[0] = new AcquireResult.Acquired(newAttachment);
                return newAttachment;
            }
            // Live attachment exists — conflict
            result[0] = new AcquireResult.Conflict(key, existing);
            return existing;
        });

        if (result[0] instanceof AcquireResult.Acquired) {
            log.info("Workspace attachment acquired: workspace={}, session={}, client={}",
                    workspace, sessionId, clientInstanceId);
        } else if (result[0] instanceof AcquireResult.Conflict c) {
            log.info("Workspace attachment conflict: workspace={}, existingSession={}, requestedSession={}",
                    workspace, c.existing().sessionId(), sessionId);
        }
        return result[0];
    }

    /**
     * Releases the interactive TUI attachment for a workspace.
     *
     * <p>Only releases if the current attachment matches the given session ID,
     * preventing one client from releasing another's attachment.
     *
     * @param workspace canonical workspace path
     * @param sessionId the session ID that holds the attachment
     * @return true if the attachment was released
     */
    public boolean release(Path workspace, String sessionId) {
        Objects.requireNonNull(workspace, "workspace");
        Objects.requireNonNull(sessionId, "sessionId");

        boolean[] released = {false};
        attachments.computeIfPresent(workspace, (key, existing) -> {
            if (existing.sessionId().equals(sessionId)) {
                released[0] = true;
                log.info("Workspace attachment released: workspace={}, session={}", key, sessionId);
                return null; // remove
            }
            return existing; // keep — different session owns it
        });
        return released[0];
    }

    /**
     * Updates the heartbeat timestamp for an active attachment.
     *
     * @param workspace canonical workspace path
     * @param sessionId the session ID to heartbeat
     * @return true if the heartbeat was accepted (attachment exists and matches)
     */
    public boolean heartbeat(Path workspace, String sessionId) {
        Objects.requireNonNull(workspace, "workspace");
        Objects.requireNonNull(sessionId, "sessionId");

        boolean[] accepted = {false};
        attachments.computeIfPresent(workspace, (key, existing) -> {
            if (existing.sessionId().equals(sessionId)) {
                accepted[0] = true;
                return existing.withHeartbeat();
            }
            return existing;
        });
        return accepted[0];
    }

    /**
     * Returns the current attachment for a workspace, or null if none.
     */
    public Attachment getAttachment(Path workspace) {
        return attachments.get(workspace);
    }

    /**
     * Returns the number of active (non-stale) attachments.
     */
    public int activeCount() {
        var now = Instant.now();
        return (int) attachments.values().stream()
                .filter(a -> !a.isStale(now))
                .count();
    }

    /**
     * Evicts all stale attachments. Called periodically or on demand.
     *
     * @return the number of evicted attachments
     */
    public int evictStale() {
        var now = Instant.now();
        int[] evicted = {0};
        attachments.forEach((workspace, attachment) -> {
            if (attachment.isStale(now)) {
                attachments.computeIfPresent(workspace, (key, current) -> {
                    if (current.isStale(now)) {
                        evicted[0]++;
                        log.info("Evicted stale attachment: workspace={}, session={}", key, current.sessionId());
                        return null;
                    }
                    return current;
                });
            }
        });
        return evicted[0];
    }

    /**
     * Releases all attachments. Called during daemon shutdown.
     */
    public void releaseAll() {
        int count = attachments.size();
        attachments.clear();
        log.info("All workspace attachments released: count={}", count);
    }
}
