package dev.aceclaw.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import dev.aceclaw.daemon.AceClawConfig;
import dev.aceclaw.daemon.AceClawDaemon;
import dev.aceclaw.llm.openai.CopilotDeviceAuth;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Main entry point for the AceClaw CLI.
 *
 * <p>Acts as a thin client that connects to the daemon process via Unix Domain Socket.
 * Auto-starts the daemon if it is not already running.
 *
 * <p>Usage:
 * <pre>
 *   aceclaw              - auto-start daemon, create session, enter REPL
 *   aceclaw daemon start - start daemon in foreground
 *   aceclaw daemon stop  - stop daemon via JSON-RPC
 *   aceclaw daemon status - show daemon health status
 * </pre>
 */
@Command(
    name = "aceclaw",
    mixinStandardHelpOptions = true,
    version = "aceclaw (version loaded at runtime)",
    description = "AI coding agent — Device as Agent",
    subcommands = {
            AceClawMain.DaemonCommand.class,
            AceClawMain.ModelsCommand.class,
            AceClawMain.DashboardCommand.class
    }
)
public final class AceClawMain implements Runnable {

    static final String VERSION = dev.aceclaw.core.BuildVersion.version();
    private static final Path CODEX_AUTH_FILE = Path.of(
            System.getProperty("user.home"), ".codex", "auth.json");

    @Override
    public void run() {
        // Pre-flight: if copilot provider and no cached OAuth token, run device-code flow
        ensureCopilotAuth(null);

        try (DaemonClient client = DaemonStarter.ensureRunning()) {

            // Fetch health status to get model info and context window size
            String model = "unknown";
            int contextWindowTokens = 0;
            String profile = null;
            String dashboardUrl = null;
            try {
                JsonNode health = client.sendRequest("health.status", null);
                model = health.path("model").asText("unknown");
                contextWindowTokens = health.path("contextWindowTokens").asInt(0);
                if (health.hasNonNull("profile")) {
                    String p = health.path("profile").asText("");
                    if (!p.isBlank()) {
                        profile = p;
                    }
                }
                JsonNode dash = health.path("dashboard");
                if (dash.path("enabled").asBoolean(false)
                        && dash.path("bundled").asBoolean(false)
                        && !dash.path("url").asText("").isEmpty()) {
                    dashboardUrl = dash.path("url").asText();
                }
            } catch (Exception e) {
                // Non-fatal; banner will show "unknown" model
            }

            // Zero-friction discovery hint (issue #446): print the dashboard
            // URL once when the REPL starts. Most users never read docs; one
            // line in the terminal is the highest-leverage place to surface
            // the new entry point. Suppressed when the dashboard isn't
            // available (disabled in config, or built with -Pno-dashboard).
            if (dashboardUrl != null) {
                System.out.println("dashboard: " + dashboardUrl);
            }

            // Create a session for the current working directory
            var params = client.objectMapper().createObjectNode();
            String requestedProject = canonicalizeProject(Paths.get(System.getProperty("user.dir")));
            params.put("project", requestedProject);
            params.put("interactive", true);
            String clientInstanceId = resolveClientInstanceId();
            params.put("clientInstanceId", clientInstanceId);

            JsonNode session = client.sendRequest("session.create", params);
            String sessionId = session.get("sessionId").asText();
            String effectiveProject = session.path("project").asText(requestedProject);
            if (!samePath(requestedProject, effectiveProject)) {
                throw new IOException("Session project mismatch: requested=" + requestedProject
                        + ", resolved=" + effectiveProject + ". Stop daemon and retry.");
            }

            // Detect git branch for status bar
            String gitBranch = detectGitBranch(effectiveProject);

            // Read bench mode from dev.sh (ACECLAW_BENCH_MODE env var)
            String benchMode = System.getenv("ACECLAW_BENCH_MODE");

            // Defense in depth: a JVM shutdown hook makes sure the daemon
            // hears about the session ending even when the REPL doesn't
            // reach its normal cleanup — TerminalRepl's double-Ctrl-C
            // force-exit, an OS-sent SIGTERM/SIGHUP, an uncaught error
            // bubbling out of repl.run(). The normal-exit path below
            // marks `cleanedUp` so the hook is a no-op when the REPL
            // finished the right way. Failures here are swallowed
            // (best-effort) — the JVM is going down regardless.
            final var cleanedUp = new java.util.concurrent.atomic.AtomicBoolean(false);
            final var clientRef = client;
            final var sessionIdFinal = sessionId;
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                if (cleanedUp.compareAndSet(false, true)) {
                    // sendRequest is synchronous (waits on socket
                    // readLine), so a hung-but-socket-alive daemon
                    // would block this hook indefinitely — defeating
                    // the very Ctrl-C×2 force-exit it backs up. Bound
                    // it to 1 s on a daemon thread so JVM exit can
                    // proceed regardless. Best-effort only.
                    var worker = new Thread(() -> {
                        try {
                            var p = clientRef.objectMapper().createObjectNode();
                            p.put("sessionId", sessionIdFinal);
                            clientRef.sendRequest("session.destroy", p);
                        } catch (Exception ignored) {
                            // Daemon down / socket closed / race — accept.
                        }
                    }, "aceclaw-cli-shutdown-cleanup-rpc");
                    worker.setDaemon(true);
                    worker.start();
                    try {
                        worker.join(1000L);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
            }, "aceclaw-cli-shutdown-cleanup"));

            // Enter REPL with session info
            var sessionInfo = new TerminalRepl.SessionInfo(
                    VERSION, model, effectiveProject, contextWindowTokens, gitBranch, benchMode, profile);
            var repl = new TerminalRepl(client, sessionId, sessionInfo);
            repl.run();

            // Normal-exit cleanup. Marking the flag first prevents the
            // shutdown hook from re-issuing session.destroy if the JVM
            // exits before this thread returns.
            if (cleanedUp.compareAndSet(false, true)) {
                try {
                    var destroyParams = client.objectMapper().createObjectNode();
                    destroyParams.put("sessionId", sessionId);
                    client.sendRequest("session.destroy", destroyParams);
                } catch (Exception e) {
                    // Best-effort cleanup; daemon may already be shutting down
                }
            }

        } catch (DaemonClient.DaemonClientException e) {
            if (e.code() == -32001) {
                // Workspace conflict: another TUI is already attached
                System.err.println();
                System.err.println("  Another TUI session is already active for this workspace.");
                System.err.println("  " + e.getMessage());
                System.err.println();
                System.err.println("  To open a TUI for a different workspace, cd there first.");
                System.err.println("  To force restart, use dev.sh (will interrupt the other session).");
            } else if (e.getMessage() != null && e.getMessage().contains("API key")) {
                System.err.println("Error: " + e.getMessage());
                System.err.println("Set ANTHROPIC_API_KEY or add apiKey to ~/.aceclaw/config.json");
            } else {
                System.err.println("Error: " + e.getMessage());
            }
            System.exit(1);
        } catch (IOException e) {
            System.err.println("Failed to connect to daemon: " + e.getMessage());
            System.err.println("Check if the daemon is running with: aceclaw daemon status");
            System.exit(1);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            System.err.println("Interrupted while starting or connecting to daemon");
            System.exit(1);
        }
    }

    /**
     * Detects the current git branch for the given project directory.
     * Returns null if not a git repo or git is not available.
     */
    private static String detectGitBranch(String projectPath) {
        try {
            var pb = new ProcessBuilder("git", "rev-parse", "--abbrev-ref", "HEAD");
            pb.directory(Path.of(projectPath).toFile());
            pb.redirectErrorStream(true);
            var process = pb.start();
            String output = new String(process.getInputStream().readAllBytes()).trim();
            int exitCode = process.waitFor();
            return exitCode == 0 && !output.isBlank() ? output : null;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * If the active provider is "copilot" and no cached OAuth token exists,
     * runs the interactive device-code flow before starting the daemon.
     */
    static void ensureCopilotAuth(String providerOverride) {
        try {
            String effectiveProvider = providerOverride;
            if (effectiveProvider == null || effectiveProvider.isBlank()) {
                AceClawConfig config = AceClawConfig.load(null);
                effectiveProvider = config.provider();
            }
            if (!"copilot".equalsIgnoreCase(effectiveProvider)) {
                return;
            }
            // Check if we already have a cached OAuth token
            if (CopilotDeviceAuth.loadCachedToken() != null) {
                return;
            }
            // No cached token — need interactive auth
            System.out.println("No Copilot OAuth token found. Starting GitHub authentication...");
            CopilotDeviceAuth.authenticate();
        } catch (RuntimeException e) {
            System.err.println("Copilot authentication failed: " + e.getMessage());
            System.err.println("You can retry or set GITHUB_TOKEN with a valid OAuth token.");
            System.exit(1);
        }
    }

    public static void main(String[] args) {
        int exitCode = new CommandLine(new AceClawMain()).execute(args);
        System.exit(exitCode);
    }

    private static String resolveClientInstanceId() {
        String fromEnv = System.getenv("ACECLAW_CLIENT_INSTANCE_ID");
        if (fromEnv != null && !fromEnv.isBlank()) {
            return fromEnv.trim();
        }
        return "cli-default";
    }

    private static String canonicalizeProject(Path path) {
        try {
            var candidate = path.toAbsolutePath().normalize();
            if (Files.exists(candidate)) {
                return candidate.toRealPath().toString();
            }
            return candidate.toString();
        } catch (IOException e) {
            return path.toAbsolutePath().normalize().toString();
        }
    }

    private static boolean samePath(String left, String right) {
        if (left == null || right == null) {
            return false;
        }
        try {
            Path l = Paths.get(left).toAbsolutePath().normalize();
            Path r = Paths.get(right).toAbsolutePath().normalize();
            return l.equals(r);
        } catch (Exception e) {
            return left.equals(right);
        }
    }

    private static boolean hasCodexAccessToken() {
        try {
            if (!Files.exists(CODEX_AUTH_FILE)) {
                return false;
            }
            var mapper = new ObjectMapper();
            JsonNode root = mapper.readTree(Files.readString(CODEX_AUTH_FILE));
            String accessToken = root.path("tokens").path("access_token").asText("");
            if (!accessToken.isBlank()) {
                return true;
            }
            String legacy = root.path("OPENAI_API_KEY").asText("");
            return !legacy.isBlank();
        } catch (Exception e) {
            return false;
        }
    }

    // -- Daemon subcommand group -----------------------------------------

    /**
     * Subcommand group for daemon lifecycle management.
     */
    @Command(
        name = "daemon",
        description = "Manage the AceClaw daemon process",
        subcommands = {
            DaemonStartCommand.class,
            DaemonStopCommand.class,
            DaemonStatusCommand.class
        }
    )
    static final class DaemonCommand implements Runnable {
        @Override
        public void run() {
            new CommandLine(this).usage(System.out);
        }
    }

    // -- Models subcommand group -----------------------------------------

    @Command(
            name = "models",
            description = "Manage model providers and authentication",
            subcommands = { ModelsAuthCommand.class }
    )
    static final class ModelsCommand implements Runnable {
        @Override
        public void run() {
            new CommandLine(this).usage(System.out);
        }
    }

    @Command(
            name = "auth",
            description = "Manage model provider authentication",
            subcommands = { ModelsAuthLoginCommand.class }
    )
    static final class ModelsAuthCommand implements Runnable {
        @Override
        public void run() {
            new CommandLine(this).usage(System.out);
        }
    }

    @Command(
            name = "login",
            description = "Authenticate with a model provider (default: openai-codex)"
    )
    static final class ModelsAuthLoginCommand implements Runnable {
        @Option(
                names = "--provider",
                description = "Provider to authenticate: ${COMPLETION-CANDIDATES}",
                defaultValue = "openai-codex")
        String provider;

        @Override
        public void run() {
            String resolvedProvider = provider == null ? "openai-codex" : provider.trim().toLowerCase();
            switch (resolvedProvider) {
                case "openai-codex" -> loginOpenAiCodex();
                case "copilot" -> loginCopilot();
                default -> {
                    System.err.println("Unsupported auth provider: " + resolvedProvider);
                    System.err.println("Supported: openai-codex, copilot");
                    System.exit(1);
                }
            }
        }

        private static void loginOpenAiCodex() {
            try {
                if (hasCodexAccessToken()) {
                    System.out.println("OpenAI Codex OAuth token already available at ~/.codex/auth.json.");
                    return;
                }

                System.out.println("Starting Codex OAuth login...");
                var process = new ProcessBuilder("codex", "auth", "login")
                        .inheritIO()
                        .start();
                int exitCode = process.waitFor();
                if (exitCode != 0) {
                    throw new RuntimeException("'codex auth login' exited with code " + exitCode);
                }

                if (!hasCodexAccessToken()) {
                    throw new RuntimeException("Codex login completed but no access token found in ~/.codex/auth.json");
                }
                System.out.println("OpenAI Codex OAuth login successful.");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                System.err.println("OpenAI Codex authentication interrupted.");
                System.exit(1);
            } catch (IOException e) {
                System.err.println("Failed to start 'codex auth login'. Ensure Codex CLI is installed and in PATH.");
                System.exit(1);
            } catch (RuntimeException e) {
                System.err.println("OpenAI Codex authentication failed: " + e.getMessage());
                System.exit(1);
            }
        }

        private static void loginCopilot() {
            try {
                if (CopilotDeviceAuth.loadCachedToken() != null) {
                    System.out.println("Copilot OAuth token already cached.");
                    return;
                }
                CopilotDeviceAuth.authenticate();
            } catch (RuntimeException e) {
                System.err.println("Copilot authentication failed: " + e.getMessage());
                System.exit(1);
            }
        }
    }

    /**
     * Starts the daemon, defaulting to background mode.
     */
    @Command(
        name = "start",
        description = "Start the daemon (background by default; use --foreground for debugging)"
    )
    static final class DaemonStartCommand implements Runnable {
        @Option(
                names = {"-p", "--provider"},
                description = "Provider override for this daemon start (e.g. anthropic, copilot, openai)"
        )
        String provider;

        @Option(
                names = {"-f", "--foreground"},
                description = "Run in foreground and keep this terminal attached"
        )
        boolean foreground;

        @Override
        public void run() {
            String providerOverride = provider == null || provider.isBlank()
                    ? null
                    : provider.trim().toLowerCase();
            ensureCopilotAuth(providerOverride);
            try {
                if (foreground) {
                    System.out.println("Starting AceClaw daemon in foreground...");
                    var daemon = providerOverride == null
                            ? AceClawDaemon.createDefault()
                            : AceClawDaemon.createDefault(providerOverride);
                    daemon.start();
                    return;
                }

                boolean started = DaemonStarter.ensureStarted(providerOverride);
                if (started) {
                    System.out.println("Daemon started in background.");
                } else if (providerOverride != null) {
                    System.out.println("Daemon is already running. Stop it first to switch provider.");
                } else {
                    System.out.println("Daemon is already running.");
                }
            } catch (AceClawDaemon.DaemonException e) {
                System.err.println("Daemon failed to start: " + e.getMessage());
                System.exit(1);
            } catch (IOException e) {
                System.err.println("Failed to start daemon: " + e.getMessage());
                System.exit(1);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                System.err.println("Interrupted while starting daemon");
                System.exit(1);
            }
        }
    }

    /**
     * Stops a running daemon by sending {@code admin.shutdown} via JSON-RPC.
     */
    @Command(
        name = "stop",
        description = "Stop the running daemon"
    )
    static final class DaemonStopCommand implements Runnable {
        @Override
        public void run() {
            if (!DaemonStarter.isDaemonRunning()) {
                System.out.println("Daemon is not running.");
                return;
            }

            try (var client = new DaemonClient()) {
                client.connect();
                JsonNode result = client.sendRequest("admin.shutdown", null);
                if (result != null && result.has("acknowledged")
                        && result.get("acknowledged").asBoolean()) {
                    System.out.println("Daemon shutdown acknowledged.");
                } else {
                    System.out.println("Shutdown request sent.");
                }
            } catch (DaemonClient.DaemonClientException e) {
                System.err.println("Error: " + e.getMessage());
                System.exit(1);
            } catch (IOException e) {
                System.err.println("Failed to connect to daemon: " + e.getMessage());
                System.exit(1);
            }
        }
    }

    /**
     * Queries daemon health status via {@code health.status} JSON-RPC.
     */
    @Command(
        name = "status",
        description = "Show daemon status"
    )
    static final class DaemonStatusCommand implements Runnable {
        @Override
        public void run() {
            if (!DaemonStarter.isDaemonRunning()) {
                System.out.println("Daemon is not running.");
                return;
            }

            try (var client = new DaemonClient()) {
                client.connect();
                JsonNode result = client.sendRequest("health.status", null);
                System.out.println("Daemon Status:");
                System.out.println("  Status:          " + result.path("status").asText("unknown"));
                System.out.println("  Version:         " + result.path("version").asText("unknown"));
                System.out.println("  Model:           " + result.path("model").asText("unknown"));
                System.out.println("  Active Sessions: " + result.path("activeSessions").asInt(0));
                JsonNode mcp = result.path("mcp");
                if (!mcp.isMissingNode()) {
                    System.out.println("  MCP Servers:     "
                            + mcp.path("connected").asInt(0)
                            + "/" + mcp.path("configured").asInt(0)
                            + " connected"
                            + (mcp.path("failed").asInt(0) > 0
                            ? " (" + mcp.path("failed").asInt(0) + " failed)"
                            : ""));
                    System.out.println("  MCP Tools:       " + mcp.path("tools").asInt(0));
                }
                System.out.println("  Timestamp:       " + result.path("timestamp").asText("unknown"));
            } catch (DaemonClient.DaemonClientException e) {
                System.err.println("Error: " + e.getMessage());
                System.exit(1);
            } catch (IOException e) {
                System.err.println("Failed to connect to daemon: " + e.getMessage());
                System.exit(1);
            }
        }
    }

    /**
     * Opens the bundled browser dashboard (issue #446). Boots the daemon if it
     * isn't already running, then queries {@code health.status} for the URL the
     * daemon is serving on (so a user-overridden port or an ephemeral fallback
     * is handled correctly) and opens the system browser.
     */
    @Command(
        name = "dashboard",
        description = "Open the AceClaw dashboard in a browser"
    )
    static final class DashboardCommand implements Runnable {
        @Option(
                names = "--no-open",
                description = "Print the URL but don't open a browser (for SSH/headless)"
        )
        boolean noOpen;

        @Override
        public void run() {
            try (DaemonClient client = DaemonStarter.ensureRunning()) {
                JsonNode health = client.sendRequest("health.status", null);
                JsonNode dashNode = health.path("dashboard");

                // Pre-#446 daemons (or unexpected response shape) won't carry
                // a "dashboard" object — surface that as a clear upgrade hint
                // rather than letting the user stare at an empty URL.
                if (dashNode.isMissingNode() || dashNode.isNull()) {
                    System.err.println("This daemon does not report a dashboard URL.");
                    System.err.println("Stop it (`aceclaw daemon stop`) and reinstall: `./gradlew :aceclaw-cli:installDist`.");
                    System.exit(1);
                    return;
                }

                boolean enabled = dashNode.path("enabled").asBoolean(false);
                boolean bundled = dashNode.path("bundled").asBoolean(false);
                String url = dashNode.path("url").asText("");

                // Bundled comes first: a missing dashboard build is the more
                // fundamental issue — even if the WS bridge were running there
                // would be no UI to serve. A user who runs -Pno-dashboard AND
                // hits a port conflict should be told about the build first;
                // fixing the port without rebuilding gets them nowhere.
                if (!bundled) {
                    System.err.println("The dashboard wasn't bundled in this daemon build.");
                    System.err.println("Rebuild without -Pno-dashboard, or run the dev server:");
                    System.err.println("  cd aceclaw-dashboard && npm run dev");
                    System.exit(1);
                    return;
                }
                if (!enabled) {
                    // Two paths land here: user explicitly disabled WS in
                    // config.json, OR Jetty failed to bind the port (something
                    // else on the user's machine is on 3141). Both deserve a
                    // pointer to the daemon log because the user can't
                    // distinguish them from CLI output alone.
                    System.err.println("Dashboard not available — daemon's WebSocket bridge is not running.");
                    System.err.println("Likely causes:");
                    System.err.println("  - webSocket.enabled = false in ~/.aceclaw/config.json");
                    System.err.println("  - the configured port (default 3141) is in use by another process");
                    System.err.println("Check ~/.aceclaw/logs/daemon.log for the bind error.");
                    System.exit(1);
                    return;
                }
                if (url.isEmpty()) {
                    System.err.println("Daemon reported the dashboard as enabled but no URL was returned.");
                    System.exit(1);
                    return;
                }

                // Always print the URL so the user can copy it even if the
                // browser-open step fails or they're on a headless machine.
                System.out.println("dashboard: " + url);

                if (noOpen) {
                    return;
                }
                if (!openInBrowser(url)) {
                    System.out.println("(could not open browser automatically — copy the URL above)");
                }
            } catch (DaemonClient.DaemonClientException e) {
                System.err.println("Error: " + e.getMessage());
                System.exit(1);
            } catch (IOException e) {
                System.err.println("Failed to connect to daemon: " + e.getMessage());
                System.exit(1);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                System.err.println("Interrupted while starting daemon.");
                System.exit(1);
            }
        }

        /**
         * Opens {@code url} in the system browser. Returns false if the open
         * attempt failed (no display, sandbox, missing helper) so the caller
         * can fall back to printing the URL — never throws, never blocks the
         * CLI on a failed browse attempt.
         *
         * <p>Note: {@code xdg-open} (Linux) and macOS {@code open} are
         * best-effort launchers; they routinely return success even when the
         * underlying browse failed (no DISPLAY, broken handler chain, …). We
         * intentionally do NOT wait on the helper — a "true" return only
         * means the helper was launched, not that a browser actually opened.
         * The caller must always print the URL too so a failed open is
         * recoverable from the terminal.
         */
        private static boolean openInBrowser(String url) {
            java.util.Objects.requireNonNull(url, "url");
            // Try the platform-native opener first. macOS `open` and Linux
            // `xdg-open` work in more environments than Java's Desktop API
            // (which can fail in headless JVMs even when a browser exists).
            String os = System.getProperty("os.name", "").toLowerCase();
            String[] cmd;
            if (os.contains("mac")) {
                cmd = new String[]{"open", url};
            } else if (os.contains("win")) {
                cmd = new String[]{"rundll32", "url.dll,FileProtocolHandler", url};
            } else {
                cmd = new String[]{"xdg-open", url};
            }
            try {
                var pb = new ProcessBuilder(cmd).redirectOutput(ProcessBuilder.Redirect.DISCARD)
                        .redirectError(ProcessBuilder.Redirect.DISCARD);
                pb.start();
                return true;
            } catch (IOException e) {
                return false;
            }
        }
    }
}
