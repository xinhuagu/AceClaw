package dev.chelava.daemon;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;

/**
 * PID-file-based daemon lock ensuring single-instance execution.
 *
 * <p>Uses OS-level {@link FileLock} on {@code ~/.chelava/chelava.pid} to prevent
 * concurrent daemon instances. Stale locks (where the owning process no longer
 * exists) are automatically recovered.
 */
public final class DaemonLock {

    private static final Logger log = LoggerFactory.getLogger(DaemonLock.class);

    private static final String PID_FILE_NAME = "chelava.pid";
    private static final Path DEFAULT_DIR = Path.of(System.getProperty("user.home"), ".chelava");

    /**
     * Result of attempting to acquire the daemon lock.
     */
    public sealed interface LockResult {

        /** Lock was successfully acquired. */
        record Acquired(long pid) implements LockResult {}

        /** Another live daemon instance already holds the lock. */
        record AlreadyRunning(long pid) implements LockResult {}

        /** A stale lock was found and automatically recovered. */
        record StaleLock(long stalePid) implements LockResult {}
    }

    private final Path pidFile;
    private volatile FileChannel channel;
    private volatile FileLock fileLock;
    private volatile Thread shutdownHook;

    /**
     * Creates a daemon lock using the default PID file location.
     */
    public DaemonLock() {
        this(DEFAULT_DIR.resolve(PID_FILE_NAME));
    }

    /**
     * Creates a daemon lock at the specified path (useful for testing).
     *
     * @param pidFile path where the PID file will be written
     */
    public DaemonLock(Path pidFile) {
        this.pidFile = pidFile;
    }

    /**
     * Attempts to acquire the daemon lock.
     *
     * <p>If a stale lock is detected (PID file exists but the process is dead),
     * the stale file is removed and the lock is re-acquired. The result will be
     * {@link LockResult.StaleLock} in that case.
     *
     * @return the result of the lock attempt
     * @throws IOException if file operations fail
     */
    public LockResult tryAcquire() throws IOException {
        Files.createDirectories(pidFile.getParent());

        if (Files.exists(pidFile)) {
            long existingPid = readPidFromFile();
            if (existingPid > 0 && ProcessHandle.of(existingPid).isPresent()) {
                log.warn("Daemon already running with PID {}", existingPid);
                return new LockResult.AlreadyRunning(existingPid);
            }
            // Stale lock detected
            log.info("Stale lock detected for PID {}; recovering", existingPid);
            Files.deleteIfExists(pidFile);
            acquireLock();
            return new LockResult.StaleLock(existingPid);
        }

        acquireLock();
        return new LockResult.Acquired(ProcessHandle.current().pid());
    }

    /**
     * Releases the lock and deletes the PID file.
     */
    public void release() {
        removeShutdownHook();
        doRelease();
    }

    /**
     * Returns the path of the PID file managed by this lock.
     */
    public Path pidFile() {
        return pidFile;
    }

    // -- internal --------------------------------------------------------

    private void acquireLock() throws IOException {
        long pid = ProcessHandle.current().pid();

        channel = FileChannel.open(
                pidFile,
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING
        );

        fileLock = channel.tryLock();
        if (fileLock == null) {
            channel.close();
            throw new IOException("Failed to obtain OS-level file lock on " + pidFile);
        }

        channel.write(ByteBuffer.wrap(Long.toString(pid).getBytes(StandardCharsets.UTF_8)));
        channel.force(true);

        // Set file permissions to owner-only (600)
        try {
            Files.setPosixFilePermissions(pidFile, PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException e) {
            log.debug("POSIX file permissions not supported on this OS; skipping");
        }

        registerShutdownHook();
        log.info("Daemon lock acquired; PID file written at {}", pidFile);
    }

    private long readPidFromFile() {
        try {
            String content = Files.readString(pidFile, StandardCharsets.UTF_8).trim();
            return Long.parseLong(content);
        } catch (IOException | NumberFormatException e) {
            log.warn("Unable to read PID from {}; treating as stale", pidFile, e);
            return -1;
        }
    }

    private void doRelease() {
        try {
            if (fileLock != null && fileLock.isValid()) {
                fileLock.release();
                fileLock = null;
            }
        } catch (IOException e) {
            log.warn("Error releasing file lock", e);
        }
        try {
            if (channel != null && channel.isOpen()) {
                channel.close();
                channel = null;
            }
        } catch (IOException e) {
            log.warn("Error closing file channel", e);
        }
        try {
            Files.deleteIfExists(pidFile);
            log.info("PID file deleted: {}", pidFile);
        } catch (IOException e) {
            log.warn("Failed to delete PID file {}", pidFile, e);
        }
    }

    private void registerShutdownHook() {
        shutdownHook = new Thread(this::doRelease, "chelava-lock-cleanup");
        Runtime.getRuntime().addShutdownHook(shutdownHook);
    }

    private void removeShutdownHook() {
        if (shutdownHook != null) {
            try {
                Runtime.getRuntime().removeShutdownHook(shutdownHook);
            } catch (IllegalStateException e) {
                // JVM already shutting down — hook will run on its own
            }
            shutdownHook = null;
        }
    }
}
