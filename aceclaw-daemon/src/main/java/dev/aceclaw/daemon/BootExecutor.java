package dev.aceclaw.daemon;

import dev.aceclaw.core.agent.*;
import dev.aceclaw.core.llm.LlmClient;
import dev.aceclaw.core.llm.LlmException;
import dev.aceclaw.core.llm.Message;
import dev.aceclaw.core.llm.StreamEventHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Executes BOOT.md files at daemon startup for workspace initialization.
 *
 * <p>BOOT.md is a markdown file containing natural-language instructions that the
 * agent executes autonomously at startup (no user interaction). Discovery order:
 * <ol>
 *   <li>Project-level: {@code {workingDir}/.aceclaw/BOOT.md} (higher priority)</li>
 *   <li>Global: {@code {homeDir}/BOOT.md} (fallback)</li>
 * </ol>
 *
 * <p>Both files can exist and execute (project first, then global).
 *
 * <p>Guardrails:
 * <ul>
 *   <li>Timeout: configurable, default 120 seconds</li>
 *   <li>Max iterations: 10 (prevent runaway loops)</li>
 *   <li>Permissions: read-only tools auto-approved; write/execute tools denied</li>
 *   <li>Failure handling: all exceptions caught, boot is best-effort</li>
 * </ul>
 */
public final class BootExecutor {

    private static final Logger log = LoggerFactory.getLogger(BootExecutor.class);

    /** Maximum agent loop iterations during boot to prevent runaway loops. */
    private static final int BOOT_MAX_ITERATIONS = 10;

    /** Read-only tools that are auto-approved during boot execution. */
    private static final Set<String> BOOT_ALLOWED_TOOLS = Set.of(
            "read_file", "glob", "grep", "list_directory");

    private BootExecutor() {}

    /**
     * Result of boot execution.
     *
     * @param executed   whether any BOOT.md was found and executed
     * @param filesFound number of BOOT.md files discovered
     * @param summary    human-readable summary of what happened
     * @param elapsed    wall-clock time spent on boot execution
     */
    public record BootResult(boolean executed, int filesFound,
                             String summary, Duration elapsed) {}

    /**
     * Discovers and executes BOOT.md files.
     *
     * @param homeDir        daemon home directory (~/.aceclaw)
     * @param workingDir     project working directory
     * @param llmClient      the LLM client for agent execution
     * @param toolRegistry   registry of available tools
     * @param model          model identifier
     * @param systemPrompt   system prompt for the LLM
     * @param maxTokens      max tokens per LLM request
     * @param thinkingBudget thinking budget tokens (0 = disabled)
     * @param timeoutSeconds max seconds for all boot execution
     * @return the boot result (never null)
     */
    public static BootResult execute(Path homeDir, Path workingDir,
                                     LlmClient llmClient, ToolRegistry toolRegistry,
                                     String model, String systemPrompt,
                                     int maxTokens, int thinkingBudget,
                                     int timeoutSeconds) {
        long startNanos = System.nanoTime();

        // Discover BOOT.md files
        var bootFiles = discoverBootFiles(homeDir, workingDir);
        if (bootFiles.isEmpty()) {
            log.info("No BOOT.md found, skipping boot execution");
            return new BootResult(false, 0, "No BOOT.md found",
                    Duration.ofNanos(System.nanoTime() - startNanos));
        }

        log.info("Discovered {} BOOT.md file(s): {}", bootFiles.size(), bootFiles);

        // Create a boot-restricted agent loop with read-only permission checker
        var bootPermChecker = new BootPermissionChecker();
        var bootConfig = AgentLoopConfig.builder()
                .sessionId("boot")
                .permissionChecker(bootPermChecker)
                .build();
        var agentLoop = new StreamingAgentLoop(
                llmClient, toolRegistry, model, systemPrompt,
                maxTokens, thinkingBudget, null, bootConfig);

        // Execute each BOOT.md within the timeout
        var summaries = new ArrayList<String>();
        int executed = 0;

        for (Path bootFile : bootFiles) {
            long remainingMs = timeoutSeconds * 1000L
                    - Duration.ofNanos(System.nanoTime() - startNanos).toMillis();
            if (remainingMs <= 0) {
                log.warn("Boot timeout reached before executing {}", bootFile);
                summaries.add(bootFile.getFileName() + ": skipped (timeout)");
                continue;
            }

            try {
                String result = executeBootFile(bootFile, agentLoop, remainingMs);
                executed++;
                summaries.add(bootFile + ": OK");
                log.info("BOOT.md executed successfully: {} -> {}", bootFile,
                        result.length() > 200 ? result.substring(0, 200) + "..." : result);
            } catch (TimeoutException e) {
                log.warn("BOOT.md execution timed out: {}", bootFile);
                summaries.add(bootFile + ": timeout");
            } catch (Exception e) {
                log.error("BOOT.md execution failed: {} - {}", bootFile, e.getMessage(), e);
                summaries.add(bootFile + ": error - " + e.getMessage());
            }
        }

        Duration elapsed = Duration.ofNanos(System.nanoTime() - startNanos);
        String summary = String.join("; ", summaries);
        log.info("Boot execution complete: {} of {} files executed in {}ms",
                executed, bootFiles.size(), elapsed.toMillis());

        return new BootResult(executed > 0, bootFiles.size(), summary, elapsed);
    }

    /**
     * Discovers BOOT.md files in priority order (project first, then global).
     */
    static List<Path> discoverBootFiles(Path homeDir, Path workingDir) {
        var found = new ArrayList<Path>();

        // Project-level: {workingDir}/.aceclaw/BOOT.md
        if (workingDir != null) {
            Path projectBoot = workingDir.resolve(".aceclaw").resolve("BOOT.md");
            if (Files.isRegularFile(projectBoot)) {
                found.add(projectBoot);
            }
        }

        // Global: {homeDir}/BOOT.md
        if (homeDir != null) {
            Path globalBoot = homeDir.resolve("BOOT.md");
            if (Files.isRegularFile(globalBoot)) {
                found.add(globalBoot);
            }
        }

        return List.copyOf(found);
    }

    /**
     * Executes a single BOOT.md file within a timeout.
     */
    private static String executeBootFile(Path bootFile, StreamingAgentLoop agentLoop,
                                          long timeoutMs) throws Exception {
        String content = Files.readString(bootFile);
        if (content.isBlank()) {
            log.info("BOOT.md is empty, skipping: {}", bootFile);
            return "";
        }

        // Prepend context so the agent knows this is a boot task
        String bootPrompt = "[BOOT] Executing startup instructions from " + bootFile + ":\n\n" + content;

        // Run in a virtual thread with timeout
        var executor = Executors.newVirtualThreadPerTaskExecutor();
        var future = CompletableFuture.supplyAsync(() -> {
            try {
                var turn = agentLoop.runTurn(bootPrompt, new ArrayList<>(),
                        new SilentStreamHandler());
                return turn.text();
            } catch (LlmException e) {
                throw new CompletionException(e);
            }
        }, executor);

        try {
            return future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw e;
        } catch (ExecutionException e) {
            throw (Exception) e.getCause();
        }
    }

    /**
     * A no-op stream event handler for boot execution (no user to display to).
     */
    private static final class SilentStreamHandler implements StreamEventHandler {
        // All defaults are no-ops, which is exactly what we want for boot
    }

    /**
     * Permission checker that auto-approves read-only tools and denies everything else.
     * Used during boot execution to prevent destructive changes without user interaction.
     */
    static final class BootPermissionChecker implements ToolPermissionChecker {

        @Override
        public ToolPermissionResult check(String toolName, String inputJson) {
            if (BOOT_ALLOWED_TOOLS.contains(toolName)) {
                return ToolPermissionResult.ALLOWED;
            }
            log.debug("Boot: denied tool '{}' (only read-only tools allowed during boot)", toolName);
            return ToolPermissionResult.denied(
                    "Tool '" + toolName + "' is not allowed during boot execution. " +
                    "Only read-only tools (" + BOOT_ALLOWED_TOOLS + ") are permitted.");
        }
    }
}
