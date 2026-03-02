package dev.aceclaw.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.aceclaw.core.agent.Tool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Executes a shell command and returns stdout/stderr.
 *
 * <p>On Unix/macOS, commands are executed via {@code /bin/bash -c},
 * falling back to {@code /bin/sh -c} when bash is not present (e.g. Alpine/BusyBox).
 * On Windows, commands are executed via {@code cmd.exe /c}.
 * Timeout is configurable; output is captured and truncated if it exceeds size limits.
 */
public final class BashExecTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(BashExecTool.class);

    /** Default command timeout in seconds. */
    private static final int DEFAULT_TIMEOUT_SECONDS = 120;

    /** Maximum timeout allowed in seconds (10 minutes). */
    private static final int MAX_TIMEOUT_SECONDS = 600;

    /** Maximum output size in characters. */
    private static final int MAX_OUTPUT_CHARS = 30_000;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * Commands where exit code 1 means "no match" or "difference found", not an error.
     * <ul>
     *   <li>grep/egrep/fgrep: exit 1 = no lines matched, exit 2+ = real error</li>
     *   <li>diff: exit 1 = files differ, exit 2+ = real error</li>
     * </ul>
     */
    private static final Set<String> BENIGN_EXIT1_COMMANDS = Set.of(
            "grep", "egrep", "fgrep", "diff");

        static final boolean IS_WINDOWS = System.getProperty("os.name", "")
            .toLowerCase(Locale.ROOT).startsWith("win");

    /** Prefer bash, fall back to sh for Alpine/BusyBox. */
    static final String UNIX_SHELL = Files.exists(Path.of("/bin/bash")) ? "/bin/bash" : "/bin/sh";

    private final Path workingDir;

    public BashExecTool(Path workingDir) {
        this.workingDir = workingDir;
    }

    @Override
    public String name() {
        return "bash";
    }

    @Override
    public String description() {
        return ToolDescriptionLoader.load(name());
    }

    @Override
    public JsonNode inputSchema() {
        return SchemaBuilder.object()
                .requiredProperty("command", SchemaBuilder.string(
                        "The shell command to execute"))
                .optionalProperty("timeout", SchemaBuilder.integer(
                        "Timeout in seconds (default: 120, max: 600)"))
                .build();
    }

    @Override
    public ToolResult execute(String inputJson) throws Exception {
        var input = MAPPER.readTree(inputJson);

        if (!input.has("command") || input.get("command").asText().isBlank()) {
            return new ToolResult("Missing required parameter: command", true);
        }

        var command = input.get("command").asText();
        int timeoutSeconds = DEFAULT_TIMEOUT_SECONDS;
        if (input.has("timeout") && !input.get("timeout").isNull()) {
            timeoutSeconds = Math.max(1, Math.min(input.get("timeout").asInt(DEFAULT_TIMEOUT_SECONDS), MAX_TIMEOUT_SECONDS));
        }

        log.debug("Executing shell command: {} (timeout: {}s, windows: {})", command, timeoutSeconds, IS_WINDOWS);

        try {
            return runCommand(command, timeoutSeconds);
        } catch (IOException e) {
            log.error("Failed to execute command: {}", e.getMessage());
            return new ToolResult("Failed to execute command: " + e.getMessage(), true);
        }
    }

    private ToolResult runCommand(String command, int timeoutSeconds) throws IOException {
        ProcessBuilder processBuilder;
        if (IS_WINDOWS) {
            processBuilder = new ProcessBuilder("cmd.exe", "/c", command);
        } else {
            processBuilder = new ProcessBuilder(UNIX_SHELL, "-c", command);
        }
        processBuilder.directory(workingDir.toFile())
                .redirectErrorStream(true);

        var process = processBuilder.start();

        String output;
        try (var reader = process.getInputStream()) {
            output = new String(reader.readAllBytes());
        }

        boolean completed;
        try {
            completed = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            return new ToolResult("Command interrupted", true);
        }

        if (!completed) {
            process.destroyForcibly();
            var truncated = truncateOutput(output);
            return new ToolResult(
                    truncated + "\n\n(Command timed out after " + timeoutSeconds + " seconds)",
                    true);
        }

        int exitCode = process.exitValue();
        var truncated = truncateOutput(output);

        if (exitCode != 0) {
            boolean benign = exitCode == 1 && isBenignExit1Command(command);
            if (benign) {
                return new ToolResult(
                        truncated + "\n\n(exit code: 1 — no match found)",
                        false);
            }
            return new ToolResult(
                    truncated + "\n\n(exit code: " + exitCode + ")",
                    true);
        }

        return new ToolResult(truncated, false);
    }

    /**
     * Checks whether the command starts with a program whose exit code 1 is benign.
     * Handles pipes (checks the last command segment, whose exit code the shell returns) and full paths (e.g. /usr/bin/grep).
     */
    static boolean isBenignExit1Command(String command) {
        if (command == null || command.isBlank()) return false;

        // For pipelines, check the last command (its exit code is what the shell returns)
        String segment = command.strip();
        int pipeIdx = segment.lastIndexOf('|');
        if (pipeIdx >= 0 && pipeIdx < segment.length() - 1) {
            segment = segment.substring(pipeIdx + 1).strip();
        }

        // Extract the first token (the program name)
        String firstToken = segment.split("\\s+", 2)[0];

        // Strip path prefix (e.g. /usr/bin/grep → grep)
        int slashIdx = firstToken.lastIndexOf('/');
        String baseName = slashIdx >= 0 ? firstToken.substring(slashIdx + 1) : firstToken;

        return BENIGN_EXIT1_COMMANDS.contains(baseName);
    }

    private static String truncateOutput(String output) {
        if (output.length() <= MAX_OUTPUT_CHARS) {
            return output;
        }
        return output.substring(0, MAX_OUTPUT_CHARS) +
               "\n... (output truncated, " + output.length() + " total characters)";
    }
}
