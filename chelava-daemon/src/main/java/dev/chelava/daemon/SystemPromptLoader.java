package dev.chelava.daemon;

import dev.chelava.memory.AutoMemoryStore;
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
 *   <li>Optional project-specific instructions from {@code CHELAVA.md} in the project root</li>
 * </ol>
 *
 * <p>If a {@code CHELAVA.md} file exists in the project directory, its contents are
 * appended to the base prompt under a "Project Instructions" section.
 */
public final class SystemPromptLoader {

    private static final Logger log = LoggerFactory.getLogger(SystemPromptLoader.class);

    private static final String CHELAVA_MD = "CHELAVA.md";
    private static final String BASE_PROMPT_RESOURCE = "/system-prompt.md";

    private SystemPromptLoader() {}

    /** Global chelava config directory. */
    private static final Path GLOBAL_CONFIG_DIR = Path.of(
            System.getProperty("user.home"), ".chelava");

    /**
     * Loads the full system prompt for the given project directory.
     *
     * <p>Memory hierarchy (all loaded, later sources override earlier):
     * <ol>
     *   <li>Built-in base prompt (agent capabilities, guidelines)</li>
     *   <li>Global user instructions from {@code ~/.chelava/CHELAVA.md}</li>
     *   <li>Project-specific instructions from {@code {project}/CHELAVA.md}</li>
     *   <li>Project .chelava instructions from {@code {project}/.chelava/CHELAVA.md}</li>
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

        // Environment context
        sb.append("\n\n# Environment\n\n");
        sb.append("- Working directory: ").append(projectPath.toAbsolutePath().normalize()).append("\n");
        sb.append("- All relative file paths are resolved against this directory.\n");
        sb.append("- Use read_file, glob, and grep with relative paths from this directory.\n");

        // 1. Global user instructions (~/.chelava/CHELAVA.md)
        appendInstructions(sb, GLOBAL_CONFIG_DIR.resolve(CHELAVA_MD),
                "User Instructions", "your global ~/.chelava/CHELAVA.md file");

        // 2. Project root instructions ({project}/CHELAVA.md)
        appendInstructions(sb, projectPath.resolve(CHELAVA_MD),
                "Project Instructions", "the project's CHELAVA.md file");

        // 3. Project .chelava dir instructions ({project}/.chelava/CHELAVA.md)
        appendInstructions(sb, projectPath.resolve(".chelava").resolve(CHELAVA_MD),
                "Project Configuration Instructions", "the project's .chelava/CHELAVA.md file");

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
     * Appends instructions from a CHELAVA.md file if it exists.
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
        return "You are Chelava, an AI coding assistant. Use the available tools to help the user.\n";
    }
}
