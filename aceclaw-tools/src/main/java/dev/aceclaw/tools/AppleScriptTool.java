package dev.aceclaw.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.aceclaw.core.agent.Tool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * Executes AppleScript via the {@code osascript} command.
 *
 * <p>macOS only. Passes each line of the script as a separate {@code -e} argument.
 * 30-second timeout, 30K character output cap.
 */
public final class AppleScriptTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(AppleScriptTool.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final int TIMEOUT_SECONDS = 30;
    private static final int MAX_OUTPUT_CHARS = 30_000;

    private final Path workingDir;

    public AppleScriptTool(Path workingDir) {
        this.workingDir = workingDir;
    }

    /**
     * Returns true if the current platform is macOS.
     */
    public static boolean isSupported() {
        return System.getProperty("os.name", "").toLowerCase().contains("mac");
    }

    @Override
    public String name() {
        return "applescript";
    }

    @Override
    public String description() {
        return ToolDescriptionLoader.load(name());
    }

    @Override
    public JsonNode inputSchema() {
        return SchemaBuilder.object()
                .requiredProperty("script", SchemaBuilder.string(
                        "The AppleScript code to execute. Can be multi-line."))
                .build();
    }

    @Override
    public ToolResult execute(String inputJson) throws Exception {
        var input = MAPPER.readTree(inputJson);

        if (!input.has("script") || input.get("script").asText().isBlank()) {
            return new ToolResult("Missing required parameter: script", true);
        }

        var script = input.get("script").asText();

        log.debug("Executing AppleScript ({} chars)", script.length());

        try {
            return runScript(script);
        } catch (IOException e) {
            log.error("Failed to execute AppleScript: {}", e.getMessage());
            return new ToolResult("Error executing AppleScript: " + e.getMessage(), true);
        }
    }

    private ToolResult runScript(String script) throws IOException {
        var command = new ArrayList<String>();
        command.add("/usr/bin/osascript");

        // Split script into lines and pass each as a -e argument
        var lines = script.split("\n");
        for (var line : lines) {
            command.add("-e");
            command.add(line);
        }

        var processBuilder = new ProcessBuilder(command)
                .directory(workingDir.toFile())
                .redirectErrorStream(true);

        var process = processBuilder.start();

        String output;
        try (var reader = process.getInputStream()) {
            output = new String(reader.readAllBytes());
        }

        boolean completed;
        try {
            completed = process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            return new ToolResult("AppleScript interrupted", true);
        }

        if (!completed) {
            process.destroyForcibly();
            return new ToolResult(
                    "AppleScript failed [reason=timeout]: "
                            + truncate(output) + "\n\n(timed out after " + TIMEOUT_SECONDS + " seconds)",
                    true);
        }

        int exitCode = process.exitValue();
        var truncated = truncate(output);

        if (exitCode != 0) {
            String reason = classifyFailureReason(truncated);
            return new ToolResult(
                    "AppleScript failed [reason=" + reason + "]: "
                            + truncated + "\n\n(exit code: " + exitCode + ")",
                    true);
        }

        return new ToolResult(truncated.isEmpty() ? "(no output)" : truncated, false);
    }

    private static String truncate(String output) {
        if (output.length() <= MAX_OUTPUT_CHARS) return output;
        return output.substring(0, MAX_OUTPUT_CHARS) +
               "\n... (output truncated, " + output.length() + " total characters)";
    }

    private static String classifyFailureReason(String output) {
        String text = output == null ? "" : output.toLowerCase(Locale.ROOT);
        if (text.contains("not allowed assistive access")
                || text.contains("not authorized")
                || text.contains("not permitted")) {
            return "permission_blocked";
        }
        if (text.contains("variable") && text.contains("not defined")) {
            return "script_logic_error";
        }
        if (text.contains("can't get")
                || text.contains("cannot get")
                || text.contains("doesn't understand")
                || text.contains("invalid index")) {
            return "element_not_found";
        }
        if (text.contains("timed out") || text.contains("timeout")) {
            return "timeout";
        }
        return "execution_error";
    }
}
