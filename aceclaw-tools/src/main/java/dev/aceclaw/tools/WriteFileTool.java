package dev.aceclaw.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.aceclaw.core.agent.Tool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Writes content to a file, creating it if it does not exist.
 *
 * <p>Overwrites the entire file contents. Creates parent directories as needed.
 */
public final class WriteFileTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(WriteFileTool.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Path workingDir;

    public WriteFileTool(Path workingDir) {
        this.workingDir = workingDir;
    }

    @Override
    public String name() {
        return "write_file";
    }

    @Override
    public String description() {
        return "Writes content to a file. WARNING: This tool completely overwrites the file.\n" +
               "- If the file exists, you MUST read it first with read_file to understand the current content.\n" +
               "- ALWAYS prefer edit_file over write_file for modifying existing files.\n" +
               "- Only use write_file when creating new files or when you need to replace the entire content.\n" +
               "- Creates parent directories automatically if needed.\n" +
               "- Use this INSTEAD of bash with echo, cat heredoc, or redirection.";
    }

    @Override
    public JsonNode inputSchema() {
        return SchemaBuilder.object()
                .requiredProperty("file_path", SchemaBuilder.string(
                        "Absolute or relative path to the file to write"))
                .requiredProperty("content", SchemaBuilder.string(
                        "The content to write to the file"))
                .build();
    }

    @Override
    public ToolResult execute(String inputJson) throws Exception {
        var input = MAPPER.readTree(inputJson);

        if (!input.has("file_path") || input.get("file_path").asText().isBlank()) {
            return new ToolResult("Missing required parameter: file_path", true);
        }
        if (!input.has("content")) {
            return new ToolResult("Missing required parameter: content", true);
        }

        var filePath = resolveFilePath(input.get("file_path").asText());
        var content = input.get("content").asText();

        log.debug("Writing file: {} ({} chars)", filePath, content.length());

        try {
            // Create parent directories if they don't exist
            var parent = filePath.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }

            Files.writeString(filePath, content);
            return new ToolResult("File written successfully: " + filePath, false);
        } catch (IOException e) {
            log.error("Failed to write file {}: {}", filePath, e.getMessage());
            return new ToolResult("Error writing file: " + e.getMessage(), true);
        }
    }

    private Path resolveFilePath(String raw) {
        var path = Path.of(raw);
        if (path.isAbsolute()) {
            return path;
        }
        return workingDir.resolve(path).normalize();
    }
}
