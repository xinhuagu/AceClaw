package dev.aceclaw.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.aceclaw.core.agent.Tool;
import dev.aceclaw.security.Capability;
import dev.aceclaw.security.CapabilityAware;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.concurrent.TimeUnit;

/**
 * Executes AppleScript via the {@code osascript} command.
 *
 * <p>macOS only. Passes each line of the script as a separate {@code -e} argument.
 * 30-second timeout, 30K character output cap.
 */
public final class AppleScriptTool implements Tool, CapabilityAware {

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
                    truncate(output) + "\n\n(AppleScript timed out after " + TIMEOUT_SECONDS + " seconds)",
                    true);
        }

        int exitCode = process.exitValue();
        var truncated = truncate(output);

        if (exitCode != 0) {
            return new ToolResult(truncated + "\n\n(exit code: " + exitCode + ")", true);
        }

        return new ToolResult(truncated.isEmpty() ? "(no output)" : truncated, false);
    }

    private static String truncate(String output) {
        if (output.length() <= MAX_OUTPUT_CHARS) return output;
        return output.substring(0, MAX_OUTPUT_CHARS) +
               "\n... (output truncated, " + output.length() + " total characters)";
    }

    /**
     * #480 PR 3: structured {@link Capability.OsScript} with language fixed
     * to {@code "applescript"}. Modelled separately from
     * {@link Capability.BashExec} so a privacy-conscious policy can ban
     * AppleScript (which can drive arbitrary GUI apps, read clipboard, mail,
     * notes, etc.) without also banning {@code bash}, and so the audit log
     * records the actual interpreter rather than wrapping every script in a
     * fake bash label.
     */
    @Override
    public Capability toCapability(JsonNode args) {
        if (args == null || !args.has("script") || args.get("script").asText().isBlank()) {
            throw new IllegalArgumentException("applescript requires a non-blank script");
        }
        return new Capability.OsScript("applescript", args.get("script").asText());
    }
}
