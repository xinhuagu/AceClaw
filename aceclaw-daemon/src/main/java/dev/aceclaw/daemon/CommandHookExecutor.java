package dev.aceclaw.daemon;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.aceclaw.core.agent.HookEvent;
import dev.aceclaw.core.agent.HookExecutor;
import dev.aceclaw.core.agent.HookResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * Executes command-type hooks via ProcessBuilder.
 *
 * <p>For each matching hook:
 * <ol>
 *   <li>Spawns a shell process with the hook command</li>
 *   <li>Pipes JSON context to stdin</li>
 *   <li>Reads stdout/stderr</li>
 *   <li>Interprets exit code: 0=proceed, 2=block, other=non-blocking error</li>
 * </ol>
 *
 * <p>For {@link HookEvent.PreToolUse} events, the first {@link HookResult.Block}
 * stops remaining hooks. For post-execution events, all hooks always run.
 */
public final class CommandHookExecutor implements HookExecutor {

    private static final Logger log = LoggerFactory.getLogger(CommandHookExecutor.class);

    private static final boolean IS_WINDOWS = System.getProperty("os.name", "")
            .toLowerCase(Locale.ROOT).startsWith("win");
    private static final String UNIX_SHELL = Files.exists(Path.of("/bin/bash"))
            ? "/bin/bash" : "/bin/sh";

    private final HookRegistry registry;
    private final ObjectMapper objectMapper;
    private final Path fallbackWorkingDir;

    public CommandHookExecutor(HookRegistry registry, ObjectMapper objectMapper, Path workingDir) {
        this.registry = registry;
        this.objectMapper = objectMapper;
        this.fallbackWorkingDir = workingDir;
    }

    @Override
    public HookResult execute(HookEvent event) {
        List<HookConfig> hooks = registry.resolve(event);
        if (hooks.isEmpty()) {
            return new HookResult.Proceed();
        }

        boolean isPreToolUse = event instanceof HookEvent.PreToolUse;
        HookResult lastResult = new HookResult.Proceed();

        for (var hookConfig : hooks) {
            var result = executeOne(hookConfig, event);

            if (result instanceof HookResult.Block && isPreToolUse) {
                // PreToolUse: first Block stops remaining hooks
                return result;
            }

            if (result instanceof HookResult.Proceed proceed && isPreToolUse) {
                // Accumulate the last Proceed (may have updatedInput)
                lastResult = proceed;
            }

            // PostToolUse / PostToolUseFailure: always continue, never block
            if (result instanceof HookResult.Block blocked && !isPreToolUse) {
                log.warn("PostToolUse hook returned Block (exit 2) — ignored for post-execution event: {}",
                        blocked.reason());
            }

            if (result instanceof HookResult.Error err) {
                log.warn("Hook error (exit {}): {}", err.exitCode(), err.message());
            }
        }

        return lastResult;
    }

    /**
     * Executes a single hook command and returns its result.
     *
     * <p>Stdout and stderr are read concurrently on virtual threads to avoid
     * pipe deadlocks — if the process writes more than the OS pipe buffer size
     * before we read, it would block waiting for the reader, while we block on
     * {@code waitFor()}.
     */
    private HookResult executeOne(HookConfig hookConfig, HookEvent event) {
        try {
            String stdinJson = buildStdinJson(event);

            ProcessBuilder pb;
            if (IS_WINDOWS) {
                pb = new ProcessBuilder("cmd.exe", "/c", hookConfig.command());
            } else {
                pb = new ProcessBuilder(UNIX_SHELL, "-c", hookConfig.command());
            }
            pb.directory(resolveExecutionDir(event).toFile());

            Process process = pb.start();

            // Start reading stdout and stderr FIRST on virtual threads.
            // This must happen before writing stdin to avoid pipe deadlock:
            // - The process may write stdout/stderr before reading stdin
            // - We must drain both pipes concurrently with waitFor()
            // - If the process exits quickly (not reading stdin), a broken pipe
            //   on stdin write must NOT prevent us from reading stdout/stderr
            var stdoutHolder = new String[1];
            var stderrHolder = new String[1];
            var stdoutThread = Thread.ofVirtual().start(() -> {
                try {
                    stdoutHolder[0] = new String(
                            process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
                } catch (IOException e) {
                    stdoutHolder[0] = "";
                }
            });
            var stderrThread = Thread.ofVirtual().start(() -> {
                try {
                    stderrHolder[0] = new String(
                            process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8).trim();
                } catch (IOException e) {
                    stderrHolder[0] = "";
                }
            });

            // Write JSON to stdin. The hook script may or may not read stdin.
            // If the process exits before we write (fast scripts that ignore stdin),
            // the write may fail with broken pipe — this is expected and non-fatal.
            try (OutputStream os = process.getOutputStream()) {
                os.write(stdinJson.getBytes(StandardCharsets.UTF_8));
                os.flush();
            } catch (IOException e) {
                log.debug("Stdin write failed (process may not read stdin): {}", e.getMessage());
            }

            // Wait for completion with timeout
            boolean finished = process.waitFor(hookConfig.timeout(), TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                stdoutThread.join(1000);
                stderrThread.join(1000);
                log.warn("Hook command timed out after {}s: {}", hookConfig.timeout(), hookConfig.command());
                return new HookResult.Error(-1, "Hook timed out after " + hookConfig.timeout() + "s");
            }

            // Wait for reader threads to finish (process is done, streams will close)
            stdoutThread.join(5000);
            stderrThread.join(5000);

            int exitCode = process.exitValue();
            String stdout = stdoutHolder[0] != null ? stdoutHolder[0] : "";
            String stderr = stderrHolder[0] != null ? stderrHolder[0] : "";

            return interpretExitCode(exitCode, stdout, stderr, hookConfig.command());

        } catch (IOException e) {
            log.error("Failed to execute hook command '{}': {}", hookConfig.command(), e.getMessage());
            return new HookResult.Error(-1, "Command execution failed: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new HookResult.Error(-1, "Hook execution interrupted");
        }
    }

    private Path resolveExecutionDir(HookEvent event) {
        if (event != null && event.cwd() != null && !event.cwd().isBlank()) {
            try {
                var candidate = Paths.get(event.cwd()).toAbsolutePath().normalize();
                if (Files.isDirectory(candidate)) {
                    return candidate;
                }
            } catch (Exception ignored) {
                // fall through to fallback dir
            }
        }
        if (fallbackWorkingDir != null) {
            return fallbackWorkingDir.toAbsolutePath().normalize();
        }
        return Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize();
    }

    /**
     * Interprets the exit code of a hook process.
     */
    private HookResult interpretExitCode(int exitCode, String stdout, String stderr, String command) {
        return switch (exitCode) {
            case 0 -> parseProceedOutput(stdout);
            case 2 -> {
                String reason = !stderr.isEmpty() ? stderr : "Blocked by hook";
                yield new HookResult.Block(reason);
            }
            default -> {
                String message = !stderr.isEmpty() ? stderr : "Exit code " + exitCode;
                yield new HookResult.Error(exitCode, message);
            }
        };
    }

    /**
     * Parses the stdout JSON from a proceeding hook (exit 0).
     * Expected format: {"decision":"allow|deny", "updatedInput":{...}, "additionalContext":"..."}
     */
    private HookResult parseProceedOutput(String stdout) {
        if (stdout == null || stdout.isBlank()) {
            return new HookResult.Proceed(null, null, null);
        }

        try {
            var json = objectMapper.readTree(stdout);
            if (json == null || !json.isObject()) {
                return new HookResult.Proceed(stdout, null, null);
            }

            // Check for explicit deny
            if (json.has("decision") && "deny".equalsIgnoreCase(json.get("decision").asText())) {
                String reason = json.has("additionalContext")
                        ? json.get("additionalContext").asText() : "Denied by hook";
                return new HookResult.Block(reason);
            }

            // Extract optional updated input
            JsonNode updatedInput = json.has("updatedInput") && json.get("updatedInput").isObject()
                    ? json.get("updatedInput") : null;

            // Extract optional additional context
            String additionalContext = json.has("additionalContext")
                    ? json.get("additionalContext").asText(null) : null;

            return new HookResult.Proceed(stdout, updatedInput, additionalContext);

        } catch (Exception e) {
            log.warn("Failed to parse hook stdout as JSON, treating as simple proceed: {}", e.getMessage());
            return new HookResult.Proceed(stdout, null, null);
        }
    }

    /**
     * Builds the JSON payload piped to the hook's stdin.
     */
    private String buildStdinJson(HookEvent event) throws IOException {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("session_id", event.sessionId());
        root.put("cwd", event.cwd());
        root.put("hook_event_name", event.eventName());
        root.put("tool_name", event.toolName());

        if (event.toolInput() != null) {
            root.set("tool_input", event.toolInput());
        } else {
            root.set("tool_input", objectMapper.createObjectNode());
        }

        // Add event-specific fields
        switch (event) {
            case HookEvent.PostToolUse post ->
                    root.put("tool_output", post.toolOutput());
            case HookEvent.PostToolUseFailure fail ->
                    root.put("error", fail.error());
            case HookEvent.PreToolUse _ -> { /* no extra fields */ }
        }

        return objectMapper.writeValueAsString(root);
    }
}
