package dev.aceclaw.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.aceclaw.core.agent.Tool;
import dev.aceclaw.security.Capability;
import dev.aceclaw.security.CapabilityAware;
import dev.aceclaw.security.SearchKind;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Lists the contents of a directory in a formatted table.
 *
 * <p>Directories are listed first, then files, both sorted alphabetically.
 * Capped at 1000 entries to avoid overwhelming output.
 */
public final class ListDirTool implements Tool, CapabilityAware {

    private static final Logger log = LoggerFactory.getLogger(ListDirTool.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final int MAX_ENTRIES = 1000;
    private static final DateTimeFormatter DATE_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

    private final Path workingDir;

    public ListDirTool(Path workingDir) {
        this.workingDir = workingDir;
    }

    @Override
    public String name() {
        return "list_directory";
    }

    @Override
    public String description() {
        return ToolDescriptionLoader.load(name());
    }

    @Override
    public JsonNode inputSchema() {
        return SchemaBuilder.object()
                .optionalProperty("path", SchemaBuilder.string(
                        "Directory path to list. Defaults to the working directory."))
                .build();
    }

    @Override
    public ToolResult execute(String inputJson) throws Exception {
        var input = MAPPER.readTree(inputJson);
        var dirPath = resolveDir(input);

        if (!Files.exists(dirPath)) {
            return new ToolResult("Directory not found: " + dirPath, true);
        }
        if (!Files.isDirectory(dirPath)) {
            return new ToolResult("Path is not a directory: " + dirPath, true);
        }

        log.debug("Listing directory: {}", dirPath);

        try {
            return listContents(dirPath);
        } catch (IOException e) {
            log.error("Failed to list directory {}: {}", dirPath, e.getMessage());
            return new ToolResult("Error listing directory: " + e.getMessage(), true);
        }
    }

    private ToolResult listContents(Path dirPath) throws IOException {
        var dirs = new ArrayList<Entry>();
        var files = new ArrayList<Entry>();

        try (var stream = Files.list(dirPath)) {
            var entries = stream.limit(MAX_ENTRIES + 1).toList();
            boolean truncated = entries.size() > MAX_ENTRIES;
            var limited = truncated ? entries.subList(0, MAX_ENTRIES) : entries;

            for (var path : limited) {
                try {
                    var attrs = Files.readAttributes(path, BasicFileAttributes.class);
                    var name = path.getFileName().toString();
                    var isDir = attrs.isDirectory();
                    var size = isDir ? 0L : attrs.size();
                    var modified = attrs.lastModifiedTime().toInstant();

                    var entry = new Entry(isDir, name, size, modified);
                    if (isDir) {
                        dirs.add(entry);
                    } else {
                        files.add(entry);
                    }
                } catch (IOException e) {
                    // Skip entries we cannot read
                }
            }

            dirs.sort(Comparator.comparing(Entry::name, String.CASE_INSENSITIVE_ORDER));
            files.sort(Comparator.comparing(Entry::name, String.CASE_INSENSITIVE_ORDER));

            var sb = new StringBuilder();
            sb.append(String.format("%-5s %10s  %-16s  %s%n", "Type", "Size", "Modified", "Name"));
            sb.append(String.format("%-5s %10s  %-16s  %s%n", "----", "----------", "----------------", "----"));

            for (var d : dirs) {
                sb.append(formatEntry(d));
            }
            for (var f : files) {
                sb.append(formatEntry(f));
            }

            int total = dirs.size() + files.size();
            sb.append(String.format("%n%d directories, %d files", dirs.size(), files.size()));
            if (truncated) {
                sb.append(String.format(" (listing capped at %d entries)", MAX_ENTRIES));
            }

            return new ToolResult(sb.toString(), false);
        }
    }

    private static String formatEntry(Entry entry) {
        String type = entry.isDir() ? "dir" : "file";
        String size = entry.isDir() ? "-" : formatSize(entry.size());
        String modified = DATE_FMT.format(entry.modified());
        return String.format("%-5s %10s  %-16s  %s%n", type, size, modified, entry.name());
    }

    private static String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024 * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024));
        return String.format("%.1f GB", bytes / (1024.0 * 1024 * 1024));
    }

    private Path resolveDir(JsonNode input) {
        if (input.has("path") && !input.get("path").isNull() && !input.get("path").asText().isBlank()) {
            return PathResolver.resolve(input.get("path").asText(), workingDir);
        }
        return workingDir;
    }

    private record Entry(boolean isDir, String name, long size, Instant modified) {}

    /**
     * #480 PR 3: structured {@link Capability.FileSearch} with
     * {@link SearchKind#LIST}. Pattern is empty because {@code list_directory}
     * has no filter — it returns every entry. Sharing {@link Capability.FileSearch}
     * with {@code glob} and {@code grep} (rather than minting a third
     * variant) keeps the policy surface compact while still letting a rule
     * distinguish content reads ({@code GREP}) from name-only views
     * ({@code GLOB}, {@code LIST}).
     */
    @Override
    public Capability toCapability(JsonNode args) {
        return new Capability.FileSearch(resolveDir(args == null ? MAPPER.createObjectNode() : args),
                "", SearchKind.LIST);
    }
}
