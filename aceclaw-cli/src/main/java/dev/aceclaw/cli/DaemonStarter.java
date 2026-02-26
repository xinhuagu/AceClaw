package dev.aceclaw.cli;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import dev.aceclaw.core.util.WaitSupport;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

/**
 * Auto-starts the AceClaw daemon if it is not already running.
 *
 * <p>Probes the daemon socket to detect a live instance. If none is found,
 * spawns the daemon in background using the current JVM and waits for
 * the socket to become available.
 */
public final class DaemonStarter {

    private static final Logger log = LoggerFactory.getLogger(DaemonStarter.class);

    private static final Path HOME_DIR = Path.of(System.getProperty("user.home"), ".aceclaw");
    private static final Path SOCKET_PATH = HOME_DIR.resolve("aceclaw.sock");
    private static final Path LOG_DIR = HOME_DIR.resolve("logs");
    private static final Path DAEMON_LOG = LOG_DIR.resolve("daemon.log");

    /** Maximum time to wait for the daemon socket to appear after starting. */
    private static final long START_TIMEOUT_MS = 5_000;
    /** Interval between connection probes. */
    private static final long PROBE_INTERVAL_MS = 200;

    private DaemonStarter() {}

    /**
     * Ensures a daemon is running and returns a connected {@link DaemonClient}.
     *
     * <p>If the daemon is already running (socket is reachable), a client is
     * connected and returned immediately. Otherwise the daemon is spawned
     * in the background, and this method blocks until the socket becomes
     * available or the timeout expires.
     *
     * @return a connected DaemonClient
     * @throws IOException if the daemon cannot be started or connected to
     */
    public static DaemonClient ensureRunning() throws IOException {
        // Try connecting to an existing daemon
        if (isDaemonRunning()) {
            log.debug("Daemon already running; connecting");
            var client = new DaemonClient(SOCKET_PATH);
            client.connect();
            return client;
        }

        // Daemon not running; start it
        log.info("Daemon not running; starting...");
        startDaemonProcess();

        // Wait for the socket to become available
        if (!waitForSocket()) {
            throw new IOException(
                    "Daemon did not start within " + START_TIMEOUT_MS + "ms. "
                    + "Check logs at " + DAEMON_LOG);
        }

        var client = new DaemonClient(SOCKET_PATH);
        client.connect();
        log.info("Connected to newly started daemon");
        return client;
    }

    /**
     * Checks whether the daemon is running by probing the socket.
     *
     * @return true if a connection to the daemon socket succeeds
     */
    public static boolean isDaemonRunning() {
        if (!Files.exists(SOCKET_PATH)) {
            return false;
        }
        try (var probe = new DaemonClient(SOCKET_PATH)) {
            probe.connect();
            return true;
        } catch (IOException e) {
            log.debug("Socket exists but connection failed: {}", e.getMessage());
            return false;
        }
    }

    // -- internal --------------------------------------------------------

    private static void startDaemonProcess() throws IOException {
        Files.createDirectories(LOG_DIR);

        // Resolve the java binary from the current JVM
        String javaHome = System.getProperty("java.home");
        Path javaBin = Path.of(javaHome, "bin", "java");

        // Build classpath from current process
        String classpath = System.getProperty("java.class.path");

        // Launch daemon in a new process group so Ctrl+C in the CLI terminal
        // does NOT send SIGINT to the daemon. Without this, pressing Ctrl+C
        // kills both the CLI and the daemon, preventing session-end extraction.
        // We use /bin/sh -c 'exec ...' with stdin redirected from /dev/null.
        // The 'setsid' command (if available) creates a new session/process group.
        String javaCmd = javaBin + " --enable-preview -cp '" + classpath
                + "' dev.aceclaw.daemon.AceClawDaemon";

        ProcessBuilder pb;
        if (Files.exists(Path.of("/usr/bin/setsid"))) {
            // Linux: setsid creates a new session (and thus a new process group)
            pb = new ProcessBuilder("/usr/bin/setsid", "/bin/sh", "-c", javaCmd);
        } else {
            // macOS: no setsid command; use nohup to ignore HUP and trap to ignore INT
            pb = new ProcessBuilder("/bin/sh", "-c",
                    "trap '' INT; exec " + javaCmd);
        }

        // Daemon's logback.xml FILE appender writes directly to daemon.log.
        // Discard stdout to avoid duplicate lines from the STDOUT appender.
        // Redirect stderr to daemon.log to capture pre-logback JVM errors.
        pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
        pb.redirectError(ProcessBuilder.Redirect.appendTo(DAEMON_LOG.toFile()));
        pb.redirectInput(ProcessBuilder.Redirect.from(Path.of("/dev/null").toFile()));

        // Inherit the CLI's working directory so tools resolve paths
        // relative to the user's project, not ~/.aceclaw/

        Process process = pb.start();
        log.info("Daemon process started (PID {}); logs at {}", process.pid(), DAEMON_LOG);
    }

    private static boolean waitForSocket() {
        try {
            return WaitSupport.awaitCondition(
                    DaemonStarter::isDaemonRunning,
                    Duration.ofMillis(START_TIMEOUT_MS),
                    Duration.ofMillis(PROBE_INTERVAL_MS)
            );
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
