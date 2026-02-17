package dev.aceclaw.daemon;

import dev.aceclaw.memory.AutoMemoryStore;
import dev.aceclaw.memory.DailyJournal;
import dev.aceclaw.memory.MemoryTierLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Loads and assembles the system prompt for the agent.
 *
 * <p>The system prompt is composed of:
 * <ol>
 *   <li>A built-in base prompt describing the agent's capabilities and behavior</li>
 *   <li>Environment context (working dir, platform, git status)</li>
 *   <li>6-tier memory hierarchy via {@link MemoryTierLoader}</li>
 * </ol>
 */
public final class SystemPromptLoader {

    private static final Logger log = LoggerFactory.getLogger(SystemPromptLoader.class);

    private static final String BASE_PROMPT_RESOURCE = "/system-prompt.md";

    private SystemPromptLoader() {}

    /** Global aceclaw config directory. */
    private static final Path GLOBAL_CONFIG_DIR = Path.of(
            System.getProperty("user.home"), ".aceclaw");

    /**
     * Loads the full system prompt for the given project directory.
     */
    public static String load(Path projectPath) {
        return load(projectPath, null, null, null, null);
    }

    /**
     * Loads the full system prompt with auto-memory injection.
     */
    public static String load(Path projectPath, AutoMemoryStore memoryStore) {
        return load(projectPath, memoryStore, null, null, null);
    }

    /**
     * Loads the full system prompt with auto-memory injection and model identity.
     */
    public static String load(Path projectPath, AutoMemoryStore memoryStore,
                              String model, String provider) {
        return load(projectPath, memoryStore, null, model, provider);
    }

    /**
     * Loads the full system prompt with 6-tier memory, daily journal, and model identity.
     *
     * @param projectPath the project working directory
     * @param memoryStore optional auto-memory store (may be null)
     * @param journal     optional daily journal (may be null)
     * @param model       the LLM model name (may be null)
     * @param provider    the LLM provider name (may be null)
     * @return the assembled system prompt
     */
    public static String load(Path projectPath, AutoMemoryStore memoryStore,
                              DailyJournal journal, String model, String provider) {
        var sb = new StringBuilder();
        sb.append(basePrompt());

        // Environment context
        appendEnvironmentContext(sb, projectPath, model, provider);

        // Git context
        appendGitContext(sb, projectPath);

        // 6-tier memory hierarchy via MemoryTierLoader
        var tierResult = MemoryTierLoader.loadAll(
                GLOBAL_CONFIG_DIR, projectPath, memoryStore, journal);
        String tierContent = MemoryTierLoader.assembleForSystemPrompt(
                tierResult, memoryStore, projectPath, 50);
        if (!tierContent.isEmpty()) {
            sb.append(tierContent);
            log.info("Injected {} memory tiers into system prompt", tierResult.tiersLoaded());
        }

        return sb.toString();
    }

    /**
     * Appends environment context (working dir, platform, Java version, date, model).
     */
    private static void appendEnvironmentContext(StringBuilder sb, Path projectPath,
                                                  String model, String provider) {
        sb.append("\n\n# Environment\n\n");
        sb.append("- Working directory: ").append(projectPath.toAbsolutePath().normalize()).append("\n");
        sb.append("- All relative file paths resolve against this directory.\n");
        sb.append("- Platform: ").append(System.getProperty("os.name")).append("\n");
        sb.append("- Java: ").append(System.getProperty("java.version")).append("\n");
        sb.append("- The current date is: ").append(java.time.LocalDate.now()).append("\n");
        if (model != null && !model.isBlank()) {
            sb.append("- You are powered by the model: ").append(model).append("\n");
        }
        if (provider != null && !provider.isBlank()) {
            sb.append("- Provider: ").append(provider).append("\n");
        }
    }

    /**
     * Appends git repository context (branch, status, recent commits) if available.
     */
    private static void appendGitContext(StringBuilder sb, Path projectPath) {
        try {
            var gitDir = projectPath.resolve(".git");
            if (!Files.exists(gitDir)) return;

            sb.append("\n# Git Context\n\n");

            String branch = runGitCommand(projectPath, "git", "rev-parse", "--abbrev-ref", "HEAD");
            if (branch != null) {
                sb.append("- Current branch: ").append(branch).append("\n");
            }

            String status = runGitCommand(projectPath, "git", "status", "--short");
            if (status != null && !status.isBlank()) {
                sb.append("- Status:\n```\n").append(status).append("\n```\n");
            } else {
                sb.append("- Status: clean\n");
            }

            String recentLog = runGitCommand(projectPath,
                    "git", "log", "--oneline", "-5", "--no-decorate");
            if (recentLog != null && !recentLog.isBlank()) {
                sb.append("- Recent commits:\n```\n").append(recentLog).append("\n```\n");
            }

        } catch (Exception e) {
            log.debug("Failed to gather git context: {}", e.getMessage());
        }
    }

    /**
     * Runs a git command and returns the trimmed stdout, or null on failure.
     */
    private static String runGitCommand(Path workingDir, String... command) {
        try {
            var pb = new ProcessBuilder(command);
            pb.directory(workingDir.toFile());
            pb.redirectErrorStream(true);
            var process = pb.start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            int exitCode = process.waitFor();
            return exitCode == 0 ? output : null;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Returns the built-in base system prompt from the classpath resource.
     * Falls back to a minimal prompt if the resource cannot be loaded.
     */
    private static String basePrompt() {
        try (InputStream is = SystemPromptLoader.class.getResourceAsStream(BASE_PROMPT_RESOURCE)) {
            if (is != null) {
                String prompt = new String(is.readAllBytes(), StandardCharsets.UTF_8);
                log.debug("Loaded base prompt from resource ({} chars)", prompt.length());
                return prompt;
            }
        } catch (IOException e) {
            log.warn("Failed to load base prompt resource: {}", e.getMessage());
        }
        log.warn("Base prompt resource not found, using fallback");
        return "You are AceClaw, an AI coding assistant. Use the available tools to help the user.\n";
    }
}
