package dev.aceclaw.memory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Stream;

/**
 * Manages persistent markdown memory files per workspace.
 *
 * <p>Inspired by Claude Code's auto memory directory pattern. The agent writes
 * persistent markdown files (MEMORY.md + topic files) that are injected into
 * the system prompt and accessible via standard file tools.
 *
 * <p>Storage layout:
 * <pre>
 *   ~/.aceclaw/workspaces/{hash}/memory/
 *     MEMORY.md          — always injected into system prompt (first 200 lines)
 *     debugging.md       — topic file (agent reads on demand)
 *     patterns.md        — topic file
 *     ...
 * </pre>
 */
public final class MarkdownMemoryStore {

    private static final Logger log = LoggerFactory.getLogger(MarkdownMemoryStore.class);

    private static final String MEMORY_MD = "MEMORY.md";
    private static final int MAX_PROMPT_LINES = 200;
    private static final long MAX_FILE_SIZE = 50 * 1024; // 50KB per file
    private static final long MAX_TOTAL_SIZE = 500 * 1024; // 500KB total per workspace

    private final Path memoryDir;
    /** Serializes write operations to prevent TOCTOU on size checks. */
    private final ReentrantLock writeLock = new ReentrantLock();

    /**
     * Creates a markdown memory store for the given workspace memory directory.
     *
     * @param memoryDir the workspace memory directory
     *                  (e.g. ~/.aceclaw/workspaces/{hash}/memory/)
     * @throws IOException if the directory cannot be created
     */
    public MarkdownMemoryStore(Path memoryDir) throws IOException {
        this.memoryDir = memoryDir;
        Files.createDirectories(memoryDir);
    }

    /**
     * Creates a markdown memory store for a workspace using standard workspace paths.
     *
     * @param aceclawHome   the aceclaw home directory (e.g. ~/.aceclaw)
     * @param workspacePath the workspace/project directory
     * @return the markdown memory store
     * @throws IOException if directories cannot be created
     */
    public static MarkdownMemoryStore forWorkspace(Path aceclawHome, Path workspacePath) throws IOException {
        Path workspaceMemDir = WorkspacePaths.resolve(aceclawHome, workspacePath);
        return new MarkdownMemoryStore(workspaceMemDir);
    }

    /**
     * Loads the MEMORY.md file content, returning only the first {@value #MAX_PROMPT_LINES}
     * lines for system prompt injection.
     *
     * @return the first 200 lines of MEMORY.md, or null if the file doesn't exist
     */
    public String loadMemoryMd() {
        Path file = memoryDir.resolve(MEMORY_MD);
        if (!Files.isRegularFile(file)) return null;

        try {
            List<String> lines = Files.readAllLines(file);
            if (lines.isEmpty()) return null;

            int limit = Math.min(lines.size(), MAX_PROMPT_LINES);
            String content = String.join("\n", lines.subList(0, limit));
            if (lines.size() > MAX_PROMPT_LINES) {
                content += "\n\n<!-- Truncated at " + MAX_PROMPT_LINES +
                        " lines. Full file has " + lines.size() + " lines. -->";
            }
            return content.isBlank() ? null : content;
        } catch (IOException e) {
            log.warn("Failed to read MEMORY.md: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Writes the full MEMORY.md content.
     *
     * @param content the content to write
     * @throws IOException if the file cannot be written or exceeds size limits
     */
    public void writeMemoryMd(String content) throws IOException {
        writeFile(MEMORY_MD, content);
    }

    /**
     * Reads a topic file by name.
     *
     * @param name the topic file name (e.g. "debugging.md")
     * @return the file content, or null if not found
     */
    public String readTopicFile(String name) {
        validateFileName(name);
        Path file = memoryDir.resolve(name);
        if (!Files.isRegularFile(file)) return null;

        try {
            String content = Files.readString(file);
            return content.isBlank() ? null : content;
        } catch (IOException e) {
            log.warn("Failed to read topic file {}: {}", name, e.getMessage());
            return null;
        }
    }

    /**
     * Writes a topic file.
     *
     * @param name    the topic file name (e.g. "debugging.md")
     * @param content the content to write
     * @throws IOException if the file cannot be written or exceeds size limits
     */
    public void writeTopicFile(String name, String content) throws IOException {
        validateFileName(name);
        writeFile(name, content);
    }

    /**
     * Lists all available topic files (excluding MEMORY.md).
     *
     * @return list of topic file names, sorted alphabetically
     */
    public List<String> listTopicFiles() {
        if (!Files.isDirectory(memoryDir)) return List.of();

        try (Stream<Path> stream = Files.list(memoryDir)) {
            return stream
                    .filter(Files::isRegularFile)
                    .map(p -> p.getFileName().toString())
                    .filter(name -> name.endsWith(".md") && !name.equals(MEMORY_MD))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            log.warn("Failed to list topic files: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * Returns the memory directory path.
     */
    public Path memoryDir() {
        return memoryDir;
    }

    private void writeFile(String name, String content) throws IOException {
        byte[] bytes = content.getBytes();

        // Check per-file size limit (can be done outside lock — deterministic, no TOCTOU)
        if (bytes.length > MAX_FILE_SIZE) {
            throw new IOException("File " + name + " exceeds maximum size of " +
                    (MAX_FILE_SIZE / 1024) + "KB (actual: " + (bytes.length / 1024) + "KB)");
        }

        writeLock.lock();
        try {
            // Check total workspace size limit under lock to prevent TOCTOU
            long currentTotal = calculateTotalSize();
            Path target = memoryDir.resolve(name);
            long existingSize = Files.exists(target) ? Files.size(target) : 0;
            long newTotal = currentTotal - existingSize + bytes.length;
            if (newTotal > MAX_TOTAL_SIZE) {
                throw new IOException("Total memory size would exceed " +
                        (MAX_TOTAL_SIZE / 1024) + "KB limit (current: " +
                        (currentTotal / 1024) + "KB, new file: " + (bytes.length / 1024) + "KB)");
            }

            Files.writeString(target, content);
            log.debug("Wrote memory file: {} ({} bytes)", name, bytes.length);
        } finally {
            writeLock.unlock();
        }
    }

    private long calculateTotalSize() {
        if (!Files.isDirectory(memoryDir)) return 0;

        try (Stream<Path> stream = Files.list(memoryDir)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".md"))
                    .mapToLong(p -> {
                        try { return Files.size(p); }
                        catch (IOException e) { return 0; }
                    })
                    .sum();
        } catch (IOException e) {
            return 0;
        }
    }

    private static void validateFileName(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("File name cannot be null or blank");
        }
        if (!name.endsWith(".md")) {
            throw new IllegalArgumentException("File name must end with .md: " + name);
        }
        if (name.contains("/") || name.contains("\\") || name.contains("..")) {
            throw new IllegalArgumentException("File name must not contain path separators: " + name);
        }
        if (name.equals(MEMORY_MD)) {
            throw new IllegalArgumentException("Use writeMemoryMd() to write MEMORY.md");
        }
    }
}
