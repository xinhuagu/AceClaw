package dev.chelava.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.chelava.core.agent.Tool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Performs exact string replacements in files.
 *
 * <p>Replaces all occurrences of a search string with a replacement string
 * within the specified file. The search is exact (not regex).
 */
public final class EditFileTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(EditFileTool.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Path workingDir;

    public EditFileTool(Path workingDir) {
        this.workingDir = workingDir;
    }

    @Override
    public String name() {
        return "edit_file";
    }

    @Override
    public String description() {
        return "Performs exact string replacement in a file. Finds old_string and replaces it " +
               "with new_string. The old_string must be unique in the file (or use replace_all=true " +
               "to replace every occurrence). The file must exist.";
    }

    @Override
    public JsonNode inputSchema() {
        return SchemaBuilder.object()
                .requiredProperty("file_path", SchemaBuilder.string(
                        "Absolute or relative path to the file to edit"))
                .requiredProperty("old_string", SchemaBuilder.string(
                        "The exact text to search for in the file"))
                .requiredProperty("new_string", SchemaBuilder.string(
                        "The text to replace old_string with"))
                .optionalProperty("replace_all", SchemaBuilder.bool(
                        "If true, replace all occurrences. Default is false (requires unique match)."))
                .build();
    }

    @Override
    public ToolResult execute(String inputJson) throws Exception {
        var input = MAPPER.readTree(inputJson);

        if (!input.has("file_path") || input.get("file_path").asText().isBlank()) {
            return new ToolResult("Missing required parameter: file_path", true);
        }
        if (!input.has("old_string")) {
            return new ToolResult("Missing required parameter: old_string", true);
        }
        if (!input.has("new_string")) {
            return new ToolResult("Missing required parameter: new_string", true);
        }

        var filePath = resolveFilePath(input.get("file_path").asText());
        var oldString = input.get("old_string").asText();
        var newString = input.get("new_string").asText();
        boolean replaceAll = input.has("replace_all") && input.get("replace_all").asBoolean(false);

        if (oldString.equals(newString)) {
            return new ToolResult("old_string and new_string are identical", true);
        }

        if (!Files.exists(filePath)) {
            return new ToolResult("File not found: " + filePath, true);
        }
        if (Files.isDirectory(filePath)) {
            return new ToolResult("Path is a directory, not a file: " + filePath, true);
        }

        log.debug("Editing file: {} (replaceAll={})", filePath, replaceAll);

        try {
            var content = Files.readString(filePath);

            if (!content.contains(oldString)) {
                return new ToolResult("old_string not found in file: " + filePath, true);
            }

            if (!replaceAll) {
                // Verify uniqueness: old_string must appear exactly once
                int firstIndex = content.indexOf(oldString);
                int secondIndex = content.indexOf(oldString, firstIndex + 1);
                if (secondIndex != -1) {
                    int count = countOccurrences(content, oldString);
                    return new ToolResult(
                            "old_string is not unique in the file (found " + count +
                            " occurrences). Use replace_all=true to replace all, " +
                            "or provide more context to make old_string unique.", true);
                }
            }

            var newContent = content.replace(oldString, newString);
            Files.writeString(filePath, newContent);

            int replacements = replaceAll ? countOccurrences(content, oldString) : 1;
            return new ToolResult(
                    "File edited successfully: " + filePath + " (" + replacements + " replacement(s))",
                    false);
        } catch (IOException e) {
            log.error("Failed to edit file {}: {}", filePath, e.getMessage());
            return new ToolResult("Error editing file: " + e.getMessage(), true);
        }
    }

    private Path resolveFilePath(String raw) {
        var path = Path.of(raw);
        if (path.isAbsolute()) {
            return path;
        }
        return workingDir.resolve(path).normalize();
    }

    private static int countOccurrences(String text, String search) {
        int count = 0;
        int index = 0;
        while ((index = text.indexOf(search, index)) != -1) {
            count++;
            index += search.length();
        }
        return count;
    }
}
