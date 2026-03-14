package dev.aceclaw.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.aceclaw.core.agent.CancellationAware;
import dev.aceclaw.core.agent.CancellationToken;
import dev.aceclaw.core.agent.Tool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Executes a shell command and returns stdout/stderr.
 *
 * <p>On Unix/macOS, commands are executed via {@code /bin/bash -c},
 * falling back to {@code /bin/sh -c} when bash is not present (e.g. Alpine/BusyBox).
 * On Windows, commands are executed via {@code cmd.exe /c}.
 * Timeout is configurable; output is captured and truncated if it exceeds size limits.
 */
public final class BashExecTool implements Tool, CancellationAware {

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

    private static final File DEV_NULL = new File(IS_WINDOWS ? "NUL" : "/dev/null");

    private final Path workingDir;
    private volatile CancellationToken cancellationToken;
    private volatile Process activeProcess;

    public BashExecTool(Path workingDir) {
        this.workingDir = workingDir;
    }

    @Override
    public void setCancellationToken(CancellationToken token) {
        this.cancellationToken = token;
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
                .redirectErrorStream(true)
                .redirectInput(ProcessBuilder.Redirect.from(DEV_NULL));

        var process = processBuilder.start();
        activeProcess = process;

        // Read stdout in a virtual thread so it doesn't block the timeout
        var outputHolder = new AtomicReference<>("");
        var readerThread = Thread.ofVirtual().name("bash-stdout-reader").start(() -> {
            try (var is = process.getInputStream()) {
                outputHolder.set(new String(is.readAllBytes()));
            } catch (IOException ignored) {
                // process destroyed — partial output already in holder
            }
        });

        // Spawn cancel-watcher if a cancellation token is present
        var token = this.cancellationToken;
        var cancelWatcher = (token != null)
                ? Thread.ofVirtual().name("bash-cancel-watcher").start(() -> {
                    try {
                        while (process.isAlive() && !token.isCancelled()) {
                            Thread.sleep(200);
                        }
                        if (token.isCancelled() && process.isAlive()) {
                            log.debug("Cancellation requested — destroying process");
                            process.destroyForcibly();
                        }
                    } catch (InterruptedException ignored) {
                        // shutting down
                    }
                })
                : null;

        try {
            boolean completed;
            try {
                completed = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                process.destroyForcibly();
                readerThread.join(2000);
                return new ToolResult("Command interrupted", true);
            }

            if (!completed) {
                process.destroyForcibly();
                readerThread.join(2000);
                var truncated = truncateOutput(outputHolder.get());
                return new ToolResult(
                        truncated + "\n\n(Command timed out after " + timeoutSeconds + " seconds)",
                        true);
            }

            // Check if we were cancelled (process exited due to destroyForcibly from watcher)
            if (token != null && token.isCancelled()) {
                readerThread.join(2000);
                return new ToolResult("Command cancelled", true);
            }

            readerThread.join(5000);
            String output = outputHolder.get();
            int exitCode = process.exitValue();
            var truncated = truncateOutput(output);

            if (exitCode != 0) {
                boolean benign = exitCode == 1 && isBenignExit1Command(command);
                if (benign) {
                    String hint = isDiffCommand(command)
                            ? "(exit code: 1 — files differ)"
                            : "(exit code: 1 — no match found)";
                    return new ToolResult(truncated + "\n\n" + hint, false);
                }
                return new ToolResult(
                        truncated + "\n\n(exit code: " + exitCode + ")",
                        true);
            }

            return new ToolResult(truncated, false);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            return new ToolResult("Command interrupted", true);
        } finally {
            activeProcess = null;
            if (cancelWatcher != null) {
                cancelWatcher.interrupt();
            }
        }
    }

    /**
     * Checks whether the command starts with a program whose exit code 1 is benign.
     * Handles pipes (checks the last command segment, whose exit code the shell returns),
     * full paths (e.g. /usr/bin/grep), and Windows paths with backslash separators.
     */
    static boolean isBenignExit1Command(String command) {
        String baseName = extractLastCommandBaseName(command);
        return baseName != null && BENIGN_EXIT1_COMMANDS.contains(baseName);
    }

    /**
     * Returns true if the effective command (last in pipeline) is {@code diff}.
     */
    private static boolean isDiffCommand(String command) {
        return "diff".equals(extractLastCommandBaseName(command));
    }

    /**
     * Extracts the base program name from the last command in a pipeline.
     * Handles quote-aware pipe splitting, Unix/Windows path prefixes, and .exe suffixes.
     *
     * @return the lowercase base name (e.g. "grep"), or null if command is blank
     */
    static String extractLastCommandBaseName(String command) {
        if (command == null || command.isBlank()) return null;

        // Find the last unquoted pipe to isolate the final pipeline segment
        String segment = command.strip();
        int pipeIdx = lastUnquotedPipeIndex(segment);
        if (pipeIdx >= 0 && pipeIdx < segment.length() - 1) {
            segment = segment.substring(pipeIdx + 1).strip();
        }

        // Extract the first token (the program name)
        String firstToken = segment.split("\\s+", 2)[0];

        // Strip path prefix — handle both Unix (/) and Windows (\) separators
        int slashIdx = Math.max(firstToken.lastIndexOf('/'), firstToken.lastIndexOf('\\'));
        String baseName = slashIdx >= 0 ? firstToken.substring(slashIdx + 1) : firstToken;

        // Strip .exe suffix (Windows)
        if (baseName.toLowerCase(Locale.ROOT).endsWith(".exe") && baseName.length() > 4) {
            baseName = baseName.substring(0, baseName.length() - 4);
        }

        return baseName.toLowerCase(Locale.ROOT);
    }

    /**
     * Finds the index of the last unquoted, non-escaped {@code |} that is not
     * part of a {@code ||} operator. Returns -1 if no pipeline pipe is found.
     */
    private static int lastUnquotedPipeIndex(String s) {
        boolean inSingle = false;
        boolean inDouble = false;
        boolean escaped = false;
        int lastPipe = -1;

        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (escaped) {
                escaped = false;
                continue;
            }
            if (c == '\\') {
                escaped = true;
                continue;
            }
            if (c == '\'' && !inDouble) {
                inSingle = !inSingle;
                continue;
            }
            if (c == '"' && !inSingle) {
                inDouble = !inDouble;
                continue;
            }
            if (c == '|' && !inSingle && !inDouble) {
                // Skip || (logical OR, not pipeline)
                if (i + 1 < s.length() && s.charAt(i + 1) == '|') {
                    i++; // skip next |
                    continue;
                }
                lastPipe = i;
            }
        }
        return lastPipe;
    }

    private static String truncateOutput(String output) {
        if (output.length() <= MAX_OUTPUT_CHARS) {
            return output;
        }
        return output.substring(0, MAX_OUTPUT_CHARS) +
               "\n... (output truncated, " + output.length() + " total characters)";
    }
}
