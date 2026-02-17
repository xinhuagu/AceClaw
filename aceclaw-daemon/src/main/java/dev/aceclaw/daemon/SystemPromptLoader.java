package dev.aceclaw.daemon;

import dev.aceclaw.memory.AutoMemoryStore;
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
 *   <li>Optional project-specific instructions from {@code ACECLAW.md} in the project root</li>
 * </ol>
 *
 * <p>If a {@code ACECLAW.md} file exists in the project directory, its contents are
 * appended to the base prompt under a "Project Instructions" section.
 */
public final class SystemPromptLoader {

    private static final Logger log = LoggerFactory.getLogger(SystemPromptLoader.class);

    private static final String ACECLAW_MD = "ACECLAW.md";
    private static final String BASE_PROMPT_RESOURCE = "/system-prompt.md";

    private SystemPromptLoader() {}

    /** Global aceclaw config directory. */
    private static final Path GLOBAL_CONFIG_DIR = Path.of(
            System.getProperty("user.home"), ".aceclaw");

    /**
     * Loads the full system prompt for the given project directory.
     *
     * <p>Memory hierarchy (all loaded, later sources override earlier):
     * <ol>
     *   <li>Built-in base prompt (agent capabilities, guidelines)</li>
     *   <li>Global user instructions from {@code ~/.aceclaw/ACECLAW.md}</li>
     *   <li>Project-specific instructions from {@code {project}/ACECLAW.md}</li>
     *   <li>Project .aceclaw instructions from {@code {project}/.aceclaw/ACECLAW.md}</li>
     * </ol>
     *
     * @param projectPath the project working directory
     * @return the assembled system prompt
     */
    public static String load(Path projectPath) {
        return load(projectPath, null);
    }

    /**
     * Loads the full system prompt with auto-memory injection.
     *
     * @param projectPath the project working directory
     * @param memoryStore optional auto-memory store (may be null)
     * @return the assembled system prompt
     */
    public static String load(Path projectPath, AutoMemoryStore memoryStore) {
        var sb = new StringBuilder();
        sb.append(basePrompt());

        // Environment context (like Claude Code's context injection)
        sb.append("\n\n# Environment\n\n");
        sb.append("- Working directory: ").append(projectPath.toAbsolutePath().normalize()).append("\n");
        sb.append("- All relative file paths resolve against this directory.\n");
        sb.append("- Platform: ").append(System.getProperty("os.name")).append("\n");
        sb.append("- Java: ").append(System.getProperty("java.version")).append("\n");
        sb.append("- The current date is: ").append(java.time.LocalDate.now()).append("\n");

        // Inject git context if available
        appendGitContext(sb, projectPath);

        // 1. Global user instructions (~/.aceclaw/ACECLAW.md)
        appendInstructions(sb, GLOBAL_CONFIG_DIR.resolve(ACECLAW_MD),
                "User Instructions", "your global ~/.aceclaw/ACECLAW.md file");

        // 2. Project root instructions ({project}/ACECLAW.md)
        appendInstructions(sb, projectPath.resolve(ACECLAW_MD),
                "Project Instructions", "the project's ACECLAW.md file");

        // 3. Project .aceclaw dir instructions ({project}/.aceclaw/ACECLAW.md)
        appendInstructions(sb, projectPath.resolve(".aceclaw").resolve(ACECLAW_MD),
                "Project Configuration Instructions", "the project's .aceclaw/ACECLAW.md file");

        // 4. Auto-memory (learned insights from previous sessions)
        if (memoryStore != null && memoryStore.size() > 0) {
            String memorySection = memoryStore.formatForPrompt(projectPath, 50);
            if (!memorySection.isEmpty()) {
                sb.append(memorySection);
                log.info("Injected {} auto-memory entries into system prompt", memoryStore.size());
            }
        }

        return sb.toString();
    }

    /**
     * Appends instructions from a ACECLAW.md file if it exists.
     */
    private static void appendInstructions(StringBuilder sb, Path file,
                                           String sectionTitle, String sourceDesc) {
        if (!Files.isRegularFile(file)) return;
        try {
            var content = Files.readString(file);
            if (!content.isBlank()) {
                sb.append("\n\n# ").append(sectionTitle).append("\n\n");
                sb.append("The following instructions are from ").append(sourceDesc).append(". ");
                sb.append("Follow them carefully.\n\n");
                sb.append(content.strip());
                log.info("Loaded {} from {}", sectionTitle.toLowerCase(), file);
            }
        } catch (IOException e) {
            log.warn("Failed to read {}: {}", file, e.getMessage());
        }
    }

    /**
     * Appends git repository context (branch, status, recent commits) if available.
     */
    private static void appendGitContext(StringBuilder sb, Path projectPath) {
        try {
            // Check if it's a git repo
            var gitDir = projectPath.resolve(".git");
            if (!Files.exists(gitDir)) return;

            sb.append("\n# Git Context\n\n");

            // Current branch
            String branch = runGitCommand(projectPath, "git", "rev-parse", "--abbrev-ref", "HEAD");
            if (branch != null) {
                sb.append("- Current branch: ").append(branch).append("\n");
            }

            // Git status (short)
            String status = runGitCommand(projectPath, "git", "status", "--short");
            if (status != null && !status.isBlank()) {
                sb.append("- Status:\n```\n").append(status).append("\n```\n");
            } else {
                sb.append("- Status: clean\n");
            }

            // Recent commits (last 5)
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
