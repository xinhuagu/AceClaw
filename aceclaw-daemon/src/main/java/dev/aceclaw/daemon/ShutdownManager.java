package dev.aceclaw.daemon;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Manages ordered, graceful shutdown of daemon components.
 *
 * <p>Participants register via {@link #register(ShutdownParticipant)} and are
 * invoked in descending priority order when the JVM receives a shutdown signal
 * (SIGTERM, SIGINT, or normal exit). Each participant is given a configurable
 * timeout; if it does not complete in time, shutdown proceeds to the next one.
 */
public final class ShutdownManager {

    private static final Logger log = LoggerFactory.getLogger(ShutdownManager.class);

    /** Default per-participant timeout. */
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(5);

    // -- Shutdown phase reporting -----------------------------------------

    /**
     * Represents the current phase of the shutdown sequence.
     */
    public sealed interface ShutdownPhase {
        /** Shutdown has been initiated but no participant has been called yet. */
        record Initiated() implements ShutdownPhase {}

        /** A specific participant is currently shutting down. */
        record InProgress(String participantName) implements ShutdownPhase {}

        /** All participants completed successfully within their timeouts. */
        record Completed() implements ShutdownPhase {}

        /** A participant exceeded its timeout. */
        record TimedOut(String participantName) implements ShutdownPhase {}
    }

    // -- Participant contract ---------------------------------------------

    /**
     * A component that participates in the daemon shutdown sequence.
     */
    public interface ShutdownParticipant {

        /**
         * Human-readable name used for logging.
         */
        String name();

        /**
         * Shutdown priority. Participants with <em>higher</em> priority values
         * are shut down first.
         */
        int priority();

        /**
         * Called during shutdown. Implementations should release resources and
         * persist any necessary state. This method is invoked on a dedicated
         * thread and must return within the configured timeout.
         */
        void onShutdown();
    }

    // -- State ------------------------------------------------------------

    private final CopyOnWriteArrayList<ShutdownParticipant> participants = new CopyOnWriteArrayList<>();
    private final AtomicBoolean shutdownInitiated = new AtomicBoolean(false);
    private final Duration perParticipantTimeout;
    private volatile ShutdownPhase currentPhase;
    private volatile Thread shutdownHook;

    /**
     * Creates a ShutdownManager with the default 5-second per-participant timeout.
     */
    public ShutdownManager() {
        this(DEFAULT_TIMEOUT);
    }

    /**
     * Creates a ShutdownManager with a custom per-participant timeout.
     *
     * @param perParticipantTimeout maximum time each participant is given to shut down
     */
    public ShutdownManager(Duration perParticipantTimeout) {
        this.perParticipantTimeout = perParticipantTimeout;
        this.currentPhase = null;
    }

    /**
     * Installs this manager's shutdown hook into the JVM runtime.
     * Must be called once during daemon startup.
     */
    public void installShutdownHook() {
        shutdownHook = new Thread(this::executeShutdown, "aceclaw-shutdown");
        Runtime.getRuntime().addShutdownHook(shutdownHook);
        log.info("Shutdown hook installed");
    }

    /**
     * Removes the shutdown hook (e.g. during tests or explicit daemon stop).
     */
    public void removeShutdownHook() {
        if (shutdownHook != null) {
            try {
                Runtime.getRuntime().removeShutdownHook(shutdownHook);
            } catch (IllegalStateException e) {
                // JVM already shutting down
            }
            shutdownHook = null;
        }
    }

    /**
     * Registers a participant for the shutdown sequence.
     * Thread-safe; may be called from any thread at any time before shutdown.
     *
     * @param participant the participant to register
     */
    public void register(ShutdownParticipant participant) {
        participants.add(participant);
        log.debug("Registered shutdown participant '{}' with priority {}", participant.name(), participant.priority());
    }

    /**
     * Programmatically triggers the shutdown sequence.
     * Safe to call from any thread; only the first invocation takes effect.
     */
    public void executeShutdown() {
        if (!shutdownInitiated.compareAndSet(false, true)) {
            log.info("Shutdown already in progress; ignoring duplicate request");
            return;
        }

        currentPhase = new ShutdownPhase.Initiated();
        log.info("Shutdown initiated; {} participant(s) registered", participants.size());

        // Sort by descending priority
        List<ShutdownParticipant> ordered = participants.stream()
                .sorted(Comparator.comparingInt(ShutdownParticipant::priority).reversed())
                .toList();

        for (ShutdownParticipant participant : ordered) {
            String name = participant.name();
            currentPhase = new ShutdownPhase.InProgress(name);
            log.info("Shutting down participant '{}'", name);

            CountDownLatch done = new CountDownLatch(1);
            Thread worker = Thread.ofVirtual().name("shutdown-" + name).start(() -> {
                try {
                    participant.onShutdown();
                } catch (Exception e) {
                    log.error("Error shutting down participant '{}'", name, e);
                } finally {
                    done.countDown();
                }
            });

            try {
                boolean finished = done.await(perParticipantTimeout.toMillis(), TimeUnit.MILLISECONDS);
                if (!finished) {
                    currentPhase = new ShutdownPhase.TimedOut(name);
                    log.warn("Participant '{}' timed out after {}; proceeding", name, perParticipantTimeout);
                    worker.interrupt();
                } else {
                    log.info("Participant '{}' shut down successfully", name);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("Shutdown sequence interrupted while waiting for '{}'", name);
                break;
            }
        }

        currentPhase = new ShutdownPhase.Completed();
        log.info("Shutdown sequence completed");
    }

    /**
     * Returns the current shutdown phase, or {@code null} if shutdown has not started.
     */
    public ShutdownPhase currentPhase() {
        return currentPhase;
    }

    /**
     * Returns {@code true} if shutdown has been initiated.
     */
    public boolean isShutdownInitiated() {
        return shutdownInitiated.get();
    }
}
