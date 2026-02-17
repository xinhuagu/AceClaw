package dev.chelava.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.chelava.core.agent.Tool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Searches file contents for lines matching a regex pattern.
 *
 * <p>Walks the file tree and searches each text file for matching lines.
 * Supports optional glob-based file filtering and context lines.
 */
public final class GrepSearchTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(GrepSearchTool.class);

    /** Maximum number of matching files to return. */
    private static final int MAX_MATCHING_FILES = 50;

    /** Maximum number of match lines to return. */
    private static final int MAX_MATCHES = 500;

    /** Maximum depth to traverse. */
    private static final int MAX_DEPTH = 20;

    /** Max file size to search (skip binary/large files). */
    private static final long MAX_FILE_SIZE = 1_048_576; // 1 MB

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Path workingDir;

    public GrepSearchTool(Path workingDir) {
        this.workingDir = workingDir;
    }

    @Override
    public String name() {
        return "grep";
    }

    @Override
    public String description() {
        return "Searches file contents for lines matching a regex pattern. " +
               "Returns matching lines with file paths and line numbers. " +
               "Supports optional file glob filter (e.g. \"*.java\") and context lines.";
    }

    @Override
    public JsonNode inputSchema() {
        return SchemaBuilder.object()
                .requiredProperty("pattern", SchemaBuilder.string(
                        "Regular expression pattern to search for"))
                .optionalProperty("path", SchemaBuilder.string(
                        "Directory or file to search in. Defaults to working directory."))
                .optionalProperty("include", SchemaBuilder.string(
                        "Glob pattern to filter files (e.g. \"*.java\", \"*.{ts,tsx}\")"))
                .optionalProperty("context", SchemaBuilder.integer(
                        "Number of context lines to show before and after each match (default: 0)"))
                .build();
    }

    @Override
    public ToolResult execute(String inputJson) throws Exception {
        var input = MAPPER.readTree(inputJson);

        if (!input.has("pattern") || input.get("pattern").asText().isBlank()) {
            return new ToolResult("Missing required parameter: pattern", true);
        }

        var patternStr = input.get("pattern").asText();
        var searchPath = resolveSearchPath(input);
        var includeGlob = getStringParam(input, "include", null);
        int contextLines = getIntParam(input, "context", 0);

        if (contextLines < 0) contextLines = 0;
        if (contextLines > 10) contextLines = 10;

        Pattern regex;
        try {
            regex = Pattern.compile(patternStr);
        } catch (PatternSyntaxException e) {
            return new ToolResult("Invalid regex pattern: " + e.getMessage(), true);
        }

        if (!Files.exists(searchPath)) {
            return new ToolResult("Path not found: " + searchPath, true);
        }

        log.debug("Grep search: pattern={}, path={}, include={}", patternStr, searchPath, includeGlob);

        try {
            if (Files.isRegularFile(searchPath)) {
                // Search a single file
                var results = searchFile(searchPath, regex, contextLines);
                return formatResults(results, patternStr);
            } else {
                // Search directory tree
                var results = searchDirectory(searchPath, regex, includeGlob, contextLines);
                return formatResults(results, patternStr);
            }
        } catch (IOException e) {
            log.error("Grep search failed: {}", e.getMessage());
            return new ToolResult("Search failed: " + e.getMessage(), true);
        }
    }

    private List<MatchResult> searchDirectory(Path dir, Pattern regex, String includeGlob,
                                               int contextLines) throws IOException {
        PathMatcher includeMatcher = includeGlob != null
                ? FileSystems.getDefault().getPathMatcher("glob:" + includeGlob)
                : null;

        var results = new ArrayList<MatchResult>();
        var matchingFileCount = new int[]{0};

        Files.walkFileTree(dir, java.util.EnumSet.noneOf(FileVisitOption.class), MAX_DEPTH,
                new SimpleFileVisitor<>() {
                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                        if (matchingFileCount[0] >= MAX_MATCHING_FILES || results.size() >= MAX_MATCHES) {
                            return FileVisitResult.TERMINATE;
                        }

                        // Skip large files
                        if (attrs.size() > MAX_FILE_SIZE) {
                            return FileVisitResult.CONTINUE;
                        }

                        // Apply include filter
                        if (includeMatcher != null) {
                            var fileName = file.getFileName();
                            if (fileName != null && !includeMatcher.matches(fileName)) {
                                return FileVisitResult.CONTINUE;
                            }
                        }

                        // Skip binary files
                        try {
                            var contentType = Files.probeContentType(file);
                            if (contentType != null && !contentType.startsWith("text/") &&
                                !contentType.equals("application/json") &&
                                !contentType.equals("application/xml") &&
                                !contentType.equals("application/javascript")) {
                                return FileVisitResult.CONTINUE;
                            }
                        } catch (IOException ignored) {
                            // Proceed anyway if probe fails
                        }

                        try {
                            var fileResults = searchFile(file, regex, contextLines);
                            if (!fileResults.isEmpty()) {
                                matchingFileCount[0]++;
                                results.addAll(fileResults);
                            }
                        } catch (IOException ignored) {
                            // Skip files that can't be read
                        }

                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult preVisitDirectory(Path d, BasicFileAttributes attrs) {
                        var dirName = d.getFileName();
                        if (dirName != null) {
                            var name = dirName.toString();
                            if (name.startsWith(".") || name.equals("node_modules") ||
                                name.equals("build") || name.equals("target") ||
                                name.equals("__pycache__")) {
                                return FileVisitResult.SKIP_SUBTREE;
                            }
                        }
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFileFailed(Path file, IOException exc) {
                        return FileVisitResult.CONTINUE;
                    }
                });

        return results;
    }

    private List<MatchResult> searchFile(Path file, Pattern regex, int contextLines) throws IOException {
        var lines = Files.readAllLines(file);
        var results = new ArrayList<MatchResult>();

        for (int i = 0; i < lines.size(); i++) {
            var line = lines.get(i);
            if (regex.matcher(line).find()) {
                var sb = new StringBuilder();

                // Context before
                int beforeStart = Math.max(0, i - contextLines);
                for (int j = beforeStart; j < i; j++) {
                    sb.append(String.format("%d- %s%n", j + 1, lines.get(j)));
                }

                // Matching line
                sb.append(String.format("%d: %s", i + 1, line));

                // Context after
                int afterEnd = Math.min(lines.size(), i + contextLines + 1);
                for (int j = i + 1; j < afterEnd; j++) {
                    sb.append(String.format("%n%d- %s", j + 1, lines.get(j)));
                }

                results.add(new MatchResult(file, i + 1, sb.toString()));

                if (results.size() >= MAX_MATCHES) {
                    break;
                }
            }
        }

        return results;
    }

    private ToolResult formatResults(List<MatchResult> results, String pattern) {
        if (results.isEmpty()) {
            return new ToolResult("No matches found for pattern: " + pattern, false);
        }

        var sb = new StringBuilder();
        Path currentFile = null;

        for (var result : results) {
            if (!result.file.equals(currentFile)) {
                if (currentFile != null) {
                    sb.append('\n');
                }
                sb.append(result.file).append(":\n");
                currentFile = result.file;
            }
            sb.append(result.formattedMatch).append('\n');
        }

        if (results.size() >= MAX_MATCHES) {
            sb.append("\n(Results truncated at ").append(MAX_MATCHES).append(" matches)");
        }

        return new ToolResult(sb.toString().trim(), false);
    }

    private Path resolveSearchPath(JsonNode input) {
        if (input.has("path") && !input.get("path").isNull() && !input.get("path").asText().isBlank()) {
            var raw = input.get("path").asText();
            var path = Path.of(raw);
            if (path.isAbsolute()) {
                return path;
            }
            return workingDir.resolve(path).normalize();
        }
        return workingDir;
    }

    private static String getStringParam(JsonNode input, String field, String defaultValue) {
        if (input.has(field) && !input.get(field).isNull() && !input.get(field).asText().isBlank()) {
            return input.get(field).asText();
        }
        return defaultValue;
    }

    private static int getIntParam(JsonNode input, String field, int defaultValue) {
        if (input.has(field) && !input.get(field).isNull()) {
            return input.get(field).asInt(defaultValue);
        }
        return defaultValue;
    }

    private record MatchResult(Path file, int lineNumber, String formattedMatch) {}
}
