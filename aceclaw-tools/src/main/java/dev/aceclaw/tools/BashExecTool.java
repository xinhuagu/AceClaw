package dev.aceclaw.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.aceclaw.core.agent.Tool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/**
 * Executes a bash command and returns stdout/stderr.
 *
 * <p>Commands are executed via {@code /bin/bash -c} with a configurable
 * timeout. Output is captured and truncated if it exceeds size limits.
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
        return "Executes a bash command with optional timeout. Use this for git, build tools, " +
               "package managers, running tests, and other terminal operations.\n" +
               "IMPORTANT: Do NOT use this tool for file operations:\n" +
               "- To read files, use read_file (NOT cat, head, tail)\n" +
               "- To edit files, use edit_file (NOT sed, awk)\n" +
               "- To create files, use write_file (NOT echo or heredoc)\n" +
               "- To search files, use glob or grep (NOT find, grep, rg)\n" +
               "Commands run in the project working directory with timeout (default 120s, max 600s). " +
               "Output is truncated at 30000 characters.";
    }

    @Override
    public JsonNode inputSchema() {
        return SchemaBuilder.object()
                .requiredProperty("command", SchemaBuilder.string(
                        "The bash command to execute"))
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

        log.debug("Executing bash command: {} (timeout: {}s)", command, timeoutSeconds);

        try {
            return runCommand(command, timeoutSeconds);
        } catch (IOException e) {
            log.error("Failed to execute command: {}", e.getMessage());
            return new ToolResult("Failed to execute command: " + e.getMessage(), true);
        }
    }

    private ToolResult runCommand(String command, int timeoutSeconds) throws IOException {
        var processBuilder = new ProcessBuilder("/bin/bash", "-c", command)
                .directory(workingDir.toFile())
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
            return new ToolResult(
                    truncated + "\n\n(exit code: " + exitCode + ")",
                    true);
        }

        return new ToolResult(truncated, false);
    }

    private static String truncateOutput(String output) {
        if (output.length() <= MAX_OUTPUT_CHARS) {
            return output;
        }
        return output.substring(0, MAX_OUTPUT_CHARS) +
               "\n... (output truncated, " + output.length() + " total characters)";
    }
}
