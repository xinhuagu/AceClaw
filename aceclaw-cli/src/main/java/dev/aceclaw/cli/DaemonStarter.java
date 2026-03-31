package dev.aceclaw.cli;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import dev.aceclaw.core.util.WaitSupport;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;

/**
 * Auto-starts the AceClaw daemon if it is not already running.
 *
 * <p>Probes the daemon socket to detect a live instance. If none is found,
 * spawns the daemon in background using a platform-specific launch strategy
 * and waits for the socket to become available.
 *
 * <p>Platform support:
 * <ul>
 *   <li>Linux: uses {@code setsid} to create a new process group</li>
 *   <li>macOS: uses {@code trap '' INT} to ignore SIGINT in the daemon</li>
 *   <li>Windows: uses {@code ProcessBuilder} with no console inheritance</li>
 * </ul>
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
    public static DaemonClient ensureRunning() throws IOException, InterruptedException {
        // Try connecting to an existing daemon
        if (isDaemonRunning()) {
            log.debug("Daemon already running; connecting");
            var client = new DaemonClient(SOCKET_PATH);
            client.connect();
            return client;
        }

        // Daemon not running; start it
        log.info("Daemon not running; starting...");
        startDaemonProcess(null);

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
     * Ensures the daemon is running in the background.
     *
     * @param providerOverride optional provider override for the newly spawned daemon
     * @return true if a new daemon process was started, false if one was already running
     * @throws IOException if the daemon cannot be started
     * @throws InterruptedException if waiting for the socket is interrupted
     */
    public static boolean ensureStarted(String providerOverride) throws IOException, InterruptedException {
        if (isDaemonRunning()) {
            log.debug("Daemon already running; background start skipped");
            return false;
        }

        log.info("Daemon not running; starting...");
        startDaemonProcess(providerOverride);

        if (!waitForSocket()) {
            throw new IOException(
                    "Daemon did not start within " + START_TIMEOUT_MS + "ms. "
                            + "Check logs at " + DAEMON_LOG);
        }
        return true;
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

    // -- Platform detection ------------------------------------------------

    enum Platform {
        LINUX, MACOS, WINDOWS;

        static Platform detect() {
            String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
            if (os.contains("win")) return WINDOWS;
            if (os.contains("mac") || os.contains("darwin")) return MACOS;
            return LINUX;
        }
    }

    // -- Daemon process launch ---------------------------------------------

    private static void startDaemonProcess(String providerOverride) throws IOException {
        Files.createDirectories(LOG_DIR);

        Platform platform = Platform.detect();
        ProcessBuilder pb = switch (platform) {
            case LINUX -> buildLinuxLauncher();
            case MACOS -> buildMacOSLauncher();
            case WINDOWS -> buildWindowsLauncher();
        };
        applyProviderOverride(pb, providerOverride);

        // Inherit the CLI's working directory so tools resolve paths
        // relative to the user's project, not ~/.aceclaw/

        Process process = pb.start();
        log.info("Daemon process started (PID {}, platform={}); logs at {}",
                process.pid(), platform, DAEMON_LOG);
    }

    private static void applyProviderOverride(ProcessBuilder pb, String providerOverride) {
        if (providerOverride == null || providerOverride.isBlank()) {
            return;
        }
        pb.environment().put("ACECLAW_PROVIDER", providerOverride.trim().toLowerCase(Locale.ROOT));
    }

    /**
     * Resolves the java binary from the current JVM.
     * Returns {@code java.exe} on Windows, {@code java} on Unix.
     */
    static Path resolveJavaBin() {
        String javaHome = System.getProperty("java.home");
        String binary = Platform.detect() == Platform.WINDOWS ? "java.exe" : "java";
        return Path.of(javaHome, "bin", binary);
    }

    /**
     * Returns the JVM command to launch the daemon with preview features.
     */
    private static String buildJavaCommand() {
        String classpath = System.getProperty("java.class.path");
        return resolveJavaBin() + " --enable-preview -cp "
                + quoteForShell(classpath)
                + " dev.aceclaw.daemon.AceClawDaemon";
    }

    /**
     * Quotes a string for the current platform's shell.
     */
    private static String quoteForShell(String value) {
        if (Platform.detect() == Platform.WINDOWS) {
            // Windows cmd.exe uses double quotes
            return "\"" + value.replace("\"", "\\\"") + "\"";
        }
        // Unix shells use single quotes (no escaping needed except for single quotes)
        return "'" + value.replace("'", "'\\''") + "'";
    }

    // -- Platform-specific launchers ----------------------------------------

    /**
     * Linux: uses {@code setsid} to create a new session/process group,
     * preventing Ctrl+C in the CLI from killing the daemon.
     */
    private static ProcessBuilder buildLinuxLauncher() {
        String javaCmd = buildJavaCommand();
        ProcessBuilder pb;
        if (Files.exists(Path.of("/usr/bin/setsid"))) {
            pb = new ProcessBuilder("/usr/bin/setsid", "/bin/sh", "-c", javaCmd);
        } else {
            // Fallback: nohup + background
            pb = new ProcessBuilder("/bin/sh", "-c", "nohup " + javaCmd + " &");
        }
        pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
        pb.redirectError(ProcessBuilder.Redirect.appendTo(DAEMON_LOG.toFile()));
        pb.redirectInput(ProcessBuilder.Redirect.from(Path.of("/dev/null").toFile()));
        return pb;
    }

    /**
     * macOS: no setsid command; uses {@code trap '' INT} to ignore SIGINT
     * so Ctrl+C in the CLI does not kill the daemon.
     */
    private static ProcessBuilder buildMacOSLauncher() {
        String javaCmd = buildJavaCommand();
        var pb = new ProcessBuilder("/bin/sh", "-c", "trap '' INT; exec " + javaCmd);
        pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
        pb.redirectError(ProcessBuilder.Redirect.appendTo(DAEMON_LOG.toFile()));
        pb.redirectInput(ProcessBuilder.Redirect.from(Path.of("/dev/null").toFile()));
        return pb;
    }

    /**
     * Windows: launches daemon as a detached process. {@code ProcessBuilder} on Windows
     * does not share the console by default when stdin/stdout/stderr are redirected,
     * so Ctrl+C in the CLI terminal does not propagate to the daemon.
     */
    private static ProcessBuilder buildWindowsLauncher() {
        Path javaBin = resolveJavaBin();
        String classpath = System.getProperty("java.class.path");

        var pb = new ProcessBuilder(
                javaBin.toString(),
                "--enable-preview",
                "-cp", classpath,
                "dev.aceclaw.daemon.AceClawDaemon");

        // On Windows, redirecting all three streams detaches the process from the
        // parent console. No setsid/trap equivalent needed.
        pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
        pb.redirectError(ProcessBuilder.Redirect.appendTo(DAEMON_LOG.toFile()));

        // Windows has no /dev/null; use Redirect.PIPE and don't write to it
        // (the daemon doesn't read stdin, so this is safe)
        pb.redirectInput(ProcessBuilder.Redirect.PIPE);

        return pb;
    }

    private static boolean waitForSocket() throws InterruptedException {
        return WaitSupport.awaitCondition(
                DaemonStarter::isDaemonRunning,
                Duration.ofMillis(START_TIMEOUT_MS),
                Duration.ofMillis(PROBE_INTERVAL_MS)
        );
    }
}
