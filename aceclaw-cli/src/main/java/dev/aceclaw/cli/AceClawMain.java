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
    version = "aceclaw 0.1.0-SNAPSHOT",
    description = "AI coding agent — Device as Agent",
    subcommands = { AceClawMain.DaemonCommand.class, AceClawMain.ModelsCommand.class }
)
public final class AceClawMain implements Runnable {

    static final String VERSION = "0.1.0-SNAPSHOT";
    private static final Path CODEX_AUTH_FILE = Path.of(
            System.getProperty("user.home"), ".codex", "auth.json");

    @Override
    public void run() {
        // Pre-flight: if copilot provider and no cached OAuth token, run device-code flow
        ensureCopilotAuth();

        try (DaemonClient client = DaemonStarter.ensureRunning()) {

            // Fetch health status to get model info and context window size
            String model = "unknown";
            int contextWindowTokens = 0;
            try {
                JsonNode health = client.sendRequest("health.status", null);
                model = health.path("model").asText("unknown");
                contextWindowTokens = health.path("contextWindowTokens").asInt(0);
            } catch (Exception e) {
                // Non-fatal; banner will show "unknown" model
            }

            // Create a session for the current working directory
            var params = client.objectMapper().createObjectNode();
            String project = Path.of(System.getProperty("user.dir")).toString();
            params.put("project", project);

            JsonNode session = client.sendRequest("session.create", params);
            String sessionId = session.get("sessionId").asText();

            // Detect git branch for status bar
            String gitBranch = detectGitBranch(project);

            // Enter REPL with session info
            var sessionInfo = new TerminalRepl.SessionInfo(
                    VERSION, model, project, contextWindowTokens, gitBranch);
            var repl = new TerminalRepl(client, sessionId, sessionInfo);
            repl.run();

            // Destroy session on exit
            try {
                var destroyParams = client.objectMapper().createObjectNode();
                destroyParams.put("sessionId", sessionId);
                client.sendRequest("session.destroy", destroyParams);
            } catch (Exception e) {
                // Best-effort cleanup; daemon may already be shutting down
            }

        } catch (DaemonClient.DaemonClientException e) {
            if (e.getMessage() != null && e.getMessage().contains("API key")) {
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
    private static void ensureCopilotAuth() {
        try {
            AceClawConfig config = AceClawConfig.load(null);
            if (!"copilot".equals(config.provider())) {
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
     * Starts the daemon in the foreground (blocking).
     */
    @Command(
        name = "start",
        description = "Start the daemon in foreground"
    )
    static final class DaemonStartCommand implements Runnable {
        @Override
        public void run() {
            System.out.println("Starting AceClaw daemon in foreground...");
            var daemon = AceClawDaemon.createDefault();
            try {
                daemon.start();
            } catch (AceClawDaemon.DaemonException e) {
                System.err.println("Daemon failed to start: " + e.getMessage());
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
}
