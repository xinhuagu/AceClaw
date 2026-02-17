package dev.chelava.daemon;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
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

    private SystemPromptLoader() {}

    /**
     * Loads the full system prompt for the given project directory.
     *
     * @param projectPath the project working directory
     * @return the assembled system prompt
     */
    public static String load(Path projectPath) {
        var sb = new StringBuilder();
        sb.append(basePrompt());

        // Load project-specific instructions
        var chelavaMd = projectPath.resolve(CHELAVA_MD);
        if (Files.isRegularFile(chelavaMd)) {
            try {
                var content = Files.readString(chelavaMd);
                if (!content.isBlank()) {
                    sb.append("\n\n# Project Instructions\n\n");
                    sb.append("The following instructions are from the project's CHELAVA.md file. ");
                    sb.append("Follow them when working in this project.\n\n");
                    sb.append(content.strip());
                    log.info("Loaded project instructions from {}", chelavaMd);
                }
            } catch (IOException e) {
                log.warn("Failed to read {}: {}", chelavaMd, e.getMessage());
            }
        }

        return sb.toString();
    }

    /**
     * Returns the built-in base system prompt.
     */
    private static String basePrompt() {
        return """
                You are Chelava, an AI coding assistant that helps users with software engineering tasks.

                # Capabilities

                You have access to the following tools:
                - read_file: Read file contents with line numbers
                - write_file: Create or overwrite files
                - edit_file: Make targeted edits to existing files (find and replace)
                - bash: Execute shell commands
                - glob: Search for files by glob pattern
                - grep: Search file contents by regex pattern

                # Guidelines

                - Always read a file before editing it to understand the existing code
                - Prefer editing existing files over creating new ones
                - Keep changes minimal and focused on what was requested
                - Do not introduce security vulnerabilities (command injection, XSS, SQL injection, etc.)
                - When executing bash commands, be careful with destructive operations
                - Explain what you are doing and why before making changes
                - If you are unsure about something, ask for clarification
                - Write clean, idiomatic code that follows the project's existing conventions
                - Do not add unnecessary comments, docstrings, or type annotations to code you did not change

                # Behavior

                - You communicate in natural language only. No slash commands.
                - You autonomously decide which tools to use based on the user's request.
                - You handle file operations, code modifications, and shell commands as needed.
                - When multiple independent operations are needed, you may request parallel tool execution.
                - You are thorough but concise in your responses.
                """;
    }
}
