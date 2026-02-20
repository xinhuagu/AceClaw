package dev.aceclaw.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.aceclaw.core.agent.Tool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Writes content to a file, creating it if it does not exist.
 *
 * <p>Overwrites the entire file contents. Creates parent directories as needed.
 *
 * <p><b>Read-before-write enforcement:</b> If the target file already exists,
 * it must have been read (via {@code read_file}) in the current session before
 * it can be overwritten. This prevents accidental data loss from blind writes.
 * New files (that don't exist yet) are exempt from this check.
 */
public final class WriteFileTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(WriteFileTool.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Path workingDir;

    /**
     * Tracks files that have been read in the current session.
     * Shared with ReadFileTool via {@link #markFileRead(Path)}.
     */
    private final Set<Path> readFiles;

    public WriteFileTool(Path workingDir) {
        this(workingDir, ConcurrentHashMap.newKeySet());
    }

    /**
     * Creates a WriteFileTool with a shared read-tracking set.
     *
     * @param workingDir the working directory for resolving relative paths
     * @param readFiles  shared set tracking which files have been read
     */
    public WriteFileTool(Path workingDir, Set<Path> readFiles) {
        this.workingDir = workingDir;
        this.readFiles = readFiles;
    }

    /**
     * Marks a file as having been read. Called by ReadFileTool.
     */
    public void markFileRead(Path absolutePath) {
        readFiles.add(absolutePath.toAbsolutePath().normalize());
    }

    /**
     * Returns the shared read-tracking set for integration with ReadFileTool.
     *
     * <p><b>Note:</b> This returns the live mutable set, not a copy. Modifications
     * directly affect read-before-write enforcement. Intended for tool wiring in
     * {@link dev.aceclaw.daemon.AceClawDaemon} only — pass this to
     * {@code ReadFileTool}'s constructor so both tools share the same tracking state.
     */
    public Set<Path> readFiles() {
        return readFiles;
    }

    @Override
    public String name() {
        return "write_file";
    }

    @Override
    public String description() {
        return ToolDescriptionLoader.load(name());
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

        // Read-before-write enforcement: existing files must be read first
        if (Files.exists(filePath)) {
            Path normalized = filePath.toAbsolutePath().normalize();
            if (!readFiles.contains(normalized)) {
                log.warn("Write rejected: {} exists but has not been read first", filePath);
                return new ToolResult(
                        "Cannot overwrite existing file without reading it first. " +
                        "Use read_file to read '" + filePath + "' before overwriting it. " +
                        "This prevents accidental data loss.", true);
            }
        }

        log.debug("Writing file: {} ({} chars)", filePath, content.length());

        try {
            // Create parent directories if they don't exist
            var parent = filePath.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }

            Files.writeString(filePath, content, StandardCharsets.UTF_8);

            // Mark as read after writing (so subsequent writes don't require re-read)
            readFiles.add(filePath.toAbsolutePath().normalize());

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
