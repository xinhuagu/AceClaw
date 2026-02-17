package dev.chelava.cli;

import com.fasterxml.jackson.databind.JsonNode;
import dev.chelava.daemon.ChelavaDaemon;
import picocli.CommandLine;
import picocli.CommandLine.Command;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Main entry point for the Chelava CLI.
 *
 * <p>Acts as a thin client that connects to the daemon process via Unix Domain Socket.
 * Auto-starts the daemon if it is not already running.
 *
 * <p>Usage:
 * <pre>
 *   chelava              - auto-start daemon, create session, enter REPL
 *   chelava daemon start - start daemon in foreground
 *   chelava daemon stop  - stop daemon via JSON-RPC
 *   chelava daemon status - show daemon health status
 * </pre>
 */
@Command(
    name = "chelava",
    mixinStandardHelpOptions = true,
    version = "chelava 0.1.0-SNAPSHOT",
    description = "AI coding agent — Device as Agent",
    subcommands = { ChelavaMain.DaemonCommand.class }
)
public final class ChelavaMain implements Runnable {

    @Override
    public void run() {
        try (DaemonClient client = DaemonStarter.ensureRunning()) {
            System.out.println("Connected to Chelava daemon.");

            // Create a session for the current working directory
            var params = client.objectMapper().createObjectNode();
            params.put("project", Path.of(System.getProperty("user.dir")).toString());

            JsonNode session = client.sendRequest("session.create", params);
            String sessionId = session.get("sessionId").asText();
            System.out.println("Project: " + session.get("project").asText());

            // Enter REPL
            var repl = new TerminalRepl(client, sessionId);
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
                System.err.println("Set ANTHROPIC_API_KEY or add apiKey to ~/.chelava/config.json");
            } else {
                System.err.println("Error: " + e.getMessage());
            }
            System.exit(1);
        } catch (IOException e) {
            System.err.println("Failed to connect to daemon: " + e.getMessage());
            System.err.println("Check if the daemon is running with: chelava daemon status");
            System.exit(1);
        }
    }

    public static void main(String[] args) {
        int exitCode = new CommandLine(new ChelavaMain()).execute(args);
        System.exit(exitCode);
    }

    // -- Daemon subcommand group -----------------------------------------

    /**
     * Subcommand group for daemon lifecycle management.
     */
    @Command(
        name = "daemon",
        description = "Manage the Chelava daemon process",
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
            System.out.println("Starting Chelava daemon in foreground...");
            var daemon = ChelavaDaemon.createDefault();
            try {
                daemon.start();
            } catch (ChelavaDaemon.DaemonException e) {
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
