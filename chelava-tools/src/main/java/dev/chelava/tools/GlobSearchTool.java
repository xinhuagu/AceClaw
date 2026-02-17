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
import java.util.regex.PatternSyntaxException;
import java.util.List;

/**
 * Searches for files matching a glob pattern.
 *
 * <p>Uses {@link java.nio.file.FileSystem#getPathMatcher(String)} with glob
 * syntax. Returns matching file paths sorted by modification time (most recent first).
 */
public final class GlobSearchTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(GlobSearchTool.class);

    /** Maximum number of results to return. */
    private static final int MAX_RESULTS = 200;

    /** Maximum depth to traverse. */
    private static final int MAX_DEPTH = 20;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Path workingDir;

    public GlobSearchTool(Path workingDir) {
        this.workingDir = workingDir;
    }

    @Override
    public String name() {
        return "glob";
    }

    @Override
    public String description() {
        return "Searches for files matching a glob pattern. " +
               "Supports patterns like \"**/*.java\", \"src/**/*.ts\", \"*.json\". " +
               "Returns matching file paths sorted by modification time (most recent first).";
    }

    @Override
    public JsonNode inputSchema() {
        return SchemaBuilder.object()
                .requiredProperty("pattern", SchemaBuilder.string(
                        "Glob pattern to match files (e.g. \"**/*.java\", \"src/**/*.ts\")"))
                .optionalProperty("path", SchemaBuilder.string(
                        "Directory to search in. Defaults to the working directory."))
                .build();
    }

    @Override
    public ToolResult execute(String inputJson) throws Exception {
        var input = MAPPER.readTree(inputJson);

        if (!input.has("pattern") || input.get("pattern").asText().isBlank()) {
            return new ToolResult("Missing required parameter: pattern", true);
        }

        var pattern = input.get("pattern").asText();
        var searchDir = resolveSearchDir(input);

        if (!Files.exists(searchDir)) {
            return new ToolResult("Directory not found: " + searchDir, true);
        }
        if (!Files.isDirectory(searchDir)) {
            return new ToolResult("Path is not a directory: " + searchDir, true);
        }

        log.debug("Glob search: pattern={}, dir={}", pattern, searchDir);

        try {
            var matches = findMatches(searchDir, pattern);
            return formatResults(matches, searchDir, pattern);
        } catch (PatternSyntaxException e) {
            return new ToolResult("Invalid glob pattern: " + e.getMessage(), true);
        } catch (IOException e) {
            log.error("Glob search failed: {}", e.getMessage());
            return new ToolResult("Search failed: " + e.getMessage(), true);
        }
    }

    private List<Path> findMatches(Path searchDir, String pattern) throws IOException {
        var matcher = FileSystems.getDefault().getPathMatcher("glob:" + pattern);
        var results = new ArrayList<Path>();

        Files.walkFileTree(searchDir, java.util.EnumSet.noneOf(FileVisitOption.class), MAX_DEPTH,
                new SimpleFileVisitor<>() {
                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                        var relativePath = searchDir.relativize(file);
                        if (matcher.matches(relativePath)) {
                            results.add(file);
                        }
                        if (results.size() >= MAX_RESULTS) {
                            return FileVisitResult.TERMINATE;
                        }
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                        var dirName = dir.getFileName();
                        if (dirName != null) {
                            var name = dirName.toString();
                            // Skip common hidden/build directories
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

        // Sort by modification time (most recent first)
        results.sort((a, b) -> {
            try {
                return Files.getLastModifiedTime(b).compareTo(Files.getLastModifiedTime(a));
            } catch (IOException e) {
                return 0;
            }
        });

        return results;
    }

    private ToolResult formatResults(List<Path> matches, Path searchDir, String pattern) {
        if (matches.isEmpty()) {
            return new ToolResult("No files found matching pattern: " + pattern, false);
        }

        var sb = new StringBuilder();
        for (var match : matches) {
            sb.append(match).append('\n');
        }

        if (matches.size() >= MAX_RESULTS) {
            sb.append("\n(Results limited to ").append(MAX_RESULTS).append(" files)");
        }

        return new ToolResult(sb.toString().trim(), false);
    }

    private Path resolveSearchDir(JsonNode input) {
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
}
