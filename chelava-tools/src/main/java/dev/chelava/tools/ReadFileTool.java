package dev.chelava.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.chelava.core.agent.Tool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Collectors;

/**
 * Reads file contents with optional line range.
 *
 * <p>Returns file contents prefixed with line numbers. Supports offset and
 * limit parameters for reading portions of large files.
 */
public final class ReadFileTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(ReadFileTool.class);

    /** Maximum lines to return when no limit is specified. */
    private static final int DEFAULT_LIMIT = 2000;

    /** Maximum characters per line before truncation. */
    private static final int MAX_LINE_LENGTH = 2000;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Path workingDir;

    public ReadFileTool(Path workingDir) {
        this.workingDir = workingDir;
    }

    @Override
    public String name() {
        return "read_file";
    }

    @Override
    public String description() {
        return "Reads a file from the filesystem. Returns contents with line numbers. " +
               "Supports optional offset (starting line, 1-based) and limit (max lines to read).";
    }

    @Override
    public JsonNode inputSchema() {
        return SchemaBuilder.object()
                .requiredProperty("file_path", SchemaBuilder.string(
                        "Absolute or relative path to the file to read"))
                .optionalProperty("offset", SchemaBuilder.integer(
                        "Line number to start reading from (1-based). Defaults to 1."))
                .optionalProperty("limit", SchemaBuilder.integer(
                        "Maximum number of lines to read. Defaults to " + DEFAULT_LIMIT + "."))
                .build();
    }

    @Override
    public ToolResult execute(String inputJson) throws Exception {
        var input = MAPPER.readTree(inputJson);

        var filePath = resolveFilePath(input);
        int offset = getIntParam(input, "offset", 1);
        int limit = getIntParam(input, "limit", DEFAULT_LIMIT);

        if (offset < 1) {
            return new ToolResult("offset must be >= 1", true);
        }
        if (limit < 1) {
            return new ToolResult("limit must be >= 1", true);
        }

        if (!Files.exists(filePath)) {
            return new ToolResult("File not found: " + filePath, true);
        }
        if (Files.isDirectory(filePath)) {
            return new ToolResult("Path is a directory, not a file: " + filePath, true);
        }
        if (!Files.isReadable(filePath)) {
            return new ToolResult("File is not readable: " + filePath, true);
        }

        log.debug("Reading file: {} (offset={}, limit={})", filePath, offset, limit);

        try {
            return readFileContents(filePath, offset, limit);
        } catch (IOException e) {
            log.error("Failed to read file {}: {}", filePath, e.getMessage());
            return new ToolResult("Error reading file: " + e.getMessage(), true);
        }
    }

    private ToolResult readFileContents(Path filePath, int offset, int limit) throws IOException {
        var allLines = Files.readAllLines(filePath);
        int totalLines = allLines.size();

        if (totalLines == 0) {
            return new ToolResult("(empty file)", false);
        }

        // offset is 1-based
        int startIndex = Math.min(offset - 1, totalLines);
        int endIndex = Math.min(startIndex + limit, totalLines);

        if (startIndex >= totalLines) {
            return new ToolResult("offset " + offset + " exceeds file length (" + totalLines + " lines)", true);
        }

        var sb = new StringBuilder();
        int lineNumWidth = String.valueOf(endIndex).length();

        for (int i = startIndex; i < endIndex; i++) {
            var line = allLines.get(i);
            if (line.length() > MAX_LINE_LENGTH) {
                line = line.substring(0, MAX_LINE_LENGTH) + "... (truncated)";
            }
            sb.append(String.format("%" + lineNumWidth + "d\t%s%n", i + 1, line));
        }

        // Append a summary if not all lines were returned
        if (endIndex < totalLines || startIndex > 0) {
            sb.append(String.format("%n(Showing lines %d-%d of %d total)", offset, endIndex, totalLines));
        }

        return new ToolResult(sb.toString(), false);
    }

    private Path resolveFilePath(JsonNode input) {
        var raw = input.get("file_path").asText();
        var path = Path.of(raw);
        if (path.isAbsolute()) {
            return path;
        }
        return workingDir.resolve(path).normalize();
    }

    private static int getIntParam(JsonNode input, String field, int defaultValue) {
        if (input.has(field) && !input.get(field).isNull()) {
            return input.get(field).asInt(defaultValue);
        }
        return defaultValue;
    }
}
