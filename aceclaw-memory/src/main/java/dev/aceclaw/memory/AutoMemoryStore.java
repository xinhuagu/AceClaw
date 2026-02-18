package dev.aceclaw.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

/**
 * Persistent auto-memory store backed by JSONL files with HMAC-SHA256 signing.
 *
 * <p>Storage layout:
 * <pre>
 *   ~/.aceclaw/memory/
 *     memory.key          — 32-byte secret key for HMAC signing
 *     global.jsonl         — global memories (cross-project insights)
 *     {project-hash}.jsonl — per-project memories
 * </pre>
 *
 * <p>Each line in a JSONL file is a {@link MemoryEntry} JSON object. On load,
 * entries with invalid HMAC signatures are skipped (tamper detection).
 *
 * <p>Thread-safe: uses {@link CopyOnWriteArrayList} for the in-memory index
 * and a {@link ReentrantLock} to serialize all file I/O operations, preventing
 * JSONL corruption from concurrent writes.
 */
public final class AutoMemoryStore {

    private static final Logger log = LoggerFactory.getLogger(AutoMemoryStore.class);

    private static final String MEMORY_DIR = "memory";
    private static final String KEY_FILE = "memory.key";
    private static final String GLOBAL_FILE = "global.jsonl";
    private static final int KEY_SIZE_BYTES = 32;

    private final Path memoryDir;
    private final ObjectMapper mapper;
    private final MemorySigner signer;
    private final CopyOnWriteArrayList<MemoryEntry> entries;
    /** Serializes all file I/O to prevent JSONL corruption from concurrent writes. */
    private final ReentrantLock fileLock = new ReentrantLock();
    /** Serializes in-memory access tracking to prevent lost updates from concurrent queries. */
    private final ReentrantLock accessLock = new ReentrantLock();
    private DailyJournal dailyJournal;

    /**
     * Creates a memory store under the given aceclaw home directory.
     *
     * @param aceclawHome the aceclaw home directory (e.g. ~/.aceclaw)
     * @throws IOException if the memory directory or key file cannot be initialized
     */
    public AutoMemoryStore(Path aceclawHome) throws IOException {
        this.memoryDir = aceclawHome.resolve(MEMORY_DIR);
        Files.createDirectories(memoryDir);

        this.mapper = new ObjectMapper();
        this.mapper.registerModule(new JavaTimeModule());

        this.signer = new MemorySigner(loadOrCreateKey());
        this.entries = new CopyOnWriteArrayList<>();
    }

    /**
     * Creates a workspace-scoped memory store using the new workspace directory layout.
     *
     * <p>Storage: {@code ~/.aceclaw/workspaces/{hash}/memory/}
     * <p>Also initializes a {@link DailyJournal} for the workspace.
     *
     * @param aceclawHome   the aceclaw home directory (e.g. ~/.aceclaw)
     * @param workspacePath the workspace/project directory
     * @return the initialized memory store
     * @throws IOException if directories cannot be created
     */
    public static AutoMemoryStore forWorkspace(Path aceclawHome, Path workspacePath) throws IOException {
        var store = new AutoMemoryStore(aceclawHome);
        store.load(workspacePath);

        // Initialize workspace-scoped journal
        Path workspaceMemDir = WorkspacePaths.resolve(aceclawHome, workspacePath);
        store.dailyJournal = new DailyJournal(workspaceMemDir);

        return store;
    }

    /**
     * Returns the daily journal (may be null if not initialized via {@link #forWorkspace}).
     */
    public DailyJournal getDailyJournal() {
        return dailyJournal;
    }

    /**
     * Loads all memories from the global file and the given project file.
     *
     * @param projectPath the project working directory (used to derive the project-specific file)
     */
    public void load(Path projectPath) {
        fileLock.lock();
        try {
            entries.clear();

            // Load global memories
            loadFile(memoryDir.resolve(GLOBAL_FILE));

            // Load project-specific memories
            if (projectPath != null) {
                String projectHash = projectHash(projectPath);
                loadFile(memoryDir.resolve(projectHash + ".jsonl"));
            }

            log.info("Loaded {} memories ({} global + project)", entries.size(),
                    countByFile(GLOBAL_FILE));
        } finally {
            fileLock.unlock();
        }
    }

    /**
     * Adds a new memory entry. The entry is signed, persisted to disk, and added
     * to the in-memory index.
     *
     * @param category the type of memory
     * @param content  the insight in natural language
     * @param tags     searchable tags
     * @param source   what triggered the memory (e.g. "session:abc123")
     * @param global   if true, stored in global.jsonl; otherwise in the project file
     * @param projectPath the project directory (required if global=false)
     * @return the created entry
     */
    public MemoryEntry add(MemoryEntry.Category category, String content,
                           List<String> tags, String source,
                           boolean global, Path projectPath) {
        String id = UUID.randomUUID().toString();
        Instant now = Instant.now();

        // Build unsigned entry to compute signable payload
        var unsigned = new MemoryEntry(id, category, content, tags, now, source, null, 0, null);
        String hmac = signer.sign(unsigned.signablePayload());

        var entry = new MemoryEntry(id, category, content, tags, now, source, hmac, 0, null);

        // Persist to disk first, then update in-memory index
        String fileName = global ? GLOBAL_FILE : projectHash(projectPath) + ".jsonl";
        if (appendEntry(memoryDir.resolve(fileName), entry)) {
            entries.add(entry);
            log.debug("Added memory: category={}, tags={}, file={}", category, tags, fileName);
        } else {
            log.warn("Memory not added to index due to disk write failure: id={}", id);
        }
        return entry;
    }

    /**
     * Retrieves memories matching the given category filter and/or tag filter.
     *
     * @param category optional category filter (null = all categories)
     * @param tags     optional tag filter (null/empty = all tags; entry must contain at least one)
     * @param limit    maximum entries to return (0 = unlimited)
     * @return matching entries, most recent first
     */
    public List<MemoryEntry> query(MemoryEntry.Category category, List<String> tags, int limit) {
        var stream = entries.stream();

        if (category != null) {
            stream = stream.filter(e -> e.category() == category);
        }

        if (tags != null && !tags.isEmpty()) {
            var tagSet = new HashSet<>(tags);
            stream = stream.filter(e -> e.tags().stream().anyMatch(tagSet::contains));
        }

        stream = stream.sorted(Comparator.comparing(MemoryEntry::createdAt).reversed());

        if (limit > 0) {
            stream = stream.limit(limit);
        }

        var results = stream.toList();
        trackAccess(results);
        return results;
    }

    /**
     * Returns all loaded memories.
     */
    public List<MemoryEntry> all() {
        return List.copyOf(entries);
    }

    /**
     * Returns an unmodifiable view of the entries list for consolidation.
     */
    public List<MemoryEntry> entries() {
        return Collections.unmodifiableList(entries);
    }

    /**
     * Replaces the current entries with the given list (used by consolidator).
     * Also rewrites all backing files.
     */
    public void replaceEntries(List<MemoryEntry> newEntries, Path projectPath) {
        fileLock.lock();
        try {
            entries.clear();
            entries.addAll(newEntries);
            rewriteFile(memoryDir.resolve(GLOBAL_FILE));
            if (projectPath != null) {
                rewriteFile(memoryDir.resolve(projectHash(projectPath) + ".jsonl"));
            }
        } finally {
            fileLock.unlock();
        }
    }

    /**
     * Returns the count of loaded memories.
     */
    public int size() {
        return entries.size();
    }

    /**
     * Removes a memory entry by ID. Rewrites the file without the deleted entry.
     *
     * @param id          the entry ID to remove
     * @param projectPath the project directory (to determine which file to rewrite)
     * @return true if the entry was found and removed
     */
    public boolean remove(String id, Path projectPath) {
        fileLock.lock();
        try {
            var removed = entries.stream().filter(e -> e.id().equals(id)).findFirst();
            if (removed.isEmpty()) return false;

            entries.removeIf(e -> e.id().equals(id));

            // Rewrite both files (we don't know which file it's in without tracking)
            rewriteFile(memoryDir.resolve(GLOBAL_FILE));
            if (projectPath != null) {
                rewriteFile(memoryDir.resolve(projectHash(projectPath) + ".jsonl"));
            }

            log.debug("Removed memory: id={}", id);
            return true;
        } finally {
            fileLock.unlock();
        }
    }

    /**
     * Searches memories using hybrid ranking (TF-IDF + recency + frequency).
     *
     * @param query    search query in natural language
     * @param category optional category filter (null = all)
     * @param limit    maximum results (0 = unlimited)
     * @return ranked entries, highest relevance first
     */
    public List<MemoryEntry> search(String query, MemoryEntry.Category category, int limit) {
        var results = MemorySearchEngine.search(List.copyOf(entries), query, category, limit);
        trackAccess(results);
        return results;
    }

    /**
     * Formats relevant memories as a prompt section for injection into the system prompt.
     *
     * @param projectPath the project directory for project-specific memories
     * @param maxEntries  maximum entries to include
     * @return the formatted memory section, or empty string if no memories
     */
    public String formatForPrompt(Path projectPath, int maxEntries) {
        return formatForPrompt(projectPath, maxEntries, null);
    }

    /**
     * Formats memories as a prompt section, optionally ranked by a query hint.
     *
     * @param projectPath the project directory for project-specific memories
     * @param maxEntries  maximum entries to include
     * @param queryHint   optional query to rank results by relevance (null = recency order)
     * @return the formatted memory section, or empty string if no memories
     */
    public String formatForPrompt(Path projectPath, int maxEntries, String queryHint) {
        if (entries.isEmpty()) return "";

        List<MemoryEntry> relevant;
        if (queryHint != null && !queryHint.isBlank()) {
            relevant = search(queryHint, null, maxEntries);
        } else {
            relevant = query(null, null, maxEntries);
        }
        if (relevant.isEmpty()) return "";

        return formatEntries(relevant);
    }

    /**
     * Formats a list of entries as a markdown section for system prompt injection.
     */
    private String formatEntries(List<MemoryEntry> relevant) {
        var sb = new StringBuilder();
        sb.append("\n\n# Auto-Memory\n\n");
        sb.append("The following are insights learned from previous sessions. ");
        sb.append("Use them to avoid repeating mistakes and follow established patterns.\n\n");

        // Group by category for readability
        var byCategory = relevant.stream()
                .collect(Collectors.groupingBy(MemoryEntry::category));

        for (var cat : MemoryEntry.Category.values()) {
            var catEntries = byCategory.get(cat);
            if (catEntries == null || catEntries.isEmpty()) continue;

            sb.append("## ").append(formatCategory(cat)).append("\n\n");
            for (var entry : catEntries) {
                sb.append("- ").append(entry.content());
                if (!entry.tags().isEmpty()) {
                    sb.append(" [").append(String.join(", ", entry.tags())).append("]");
                }
                sb.append("\n");
            }
            sb.append("\n");
        }

        return sb.toString();
    }

    // -- internal --------------------------------------------------------

    /**
     * Updates access tracking for returned entries (lazy, in-place).
     * Replaces each accessed entry in the CopyOnWriteArrayList with an updated copy.
     */
    private void trackAccess(List<MemoryEntry> accessed) {
        if (accessed.isEmpty()) return;
        accessLock.lock();
        try {
            var accessedIds = new HashSet<String>();
            for (var entry : accessed) {
                accessedIds.add(entry.id());
            }
            for (int i = 0; i < entries.size(); i++) {
                var entry = entries.get(i);
                if (accessedIds.contains(entry.id())) {
                    entries.set(i, entry.withAccess());
                }
            }
        } finally {
            accessLock.unlock();
        }
    }

    private void loadFile(Path file) {
        if (!Files.isRegularFile(file)) return;

        try {
            var lines = Files.readAllLines(file);
            int loaded = 0;
            int skipped = 0;
            for (String line : lines) {
                if (line.isBlank()) continue;
                try {
                    var entry = mapper.readValue(line, MemoryEntry.class);
                    if (entry.hmac() != null && signer.verify(entry.signablePayload(), entry.hmac())) {
                        entries.add(entry);
                        loaded++;
                    } else {
                        skipped++;
                        log.warn("Skipped tampered memory entry: id={}", entry.id());
                    }
                } catch (Exception e) {
                    skipped++;
                    log.warn("Skipped malformed memory entry: {}", e.getMessage());
                }
            }
            log.debug("Loaded {} entries from {} ({} skipped)", loaded, file.getFileName(), skipped);
        } catch (IOException e) {
            log.warn("Failed to read memory file {}: {}", file, e.getMessage());
        }
    }

    /**
     * Appends a single entry to a JSONL file under file lock.
     *
     * @return true if the write succeeded, false on failure
     */
    private boolean appendEntry(Path file, MemoryEntry entry) {
        fileLock.lock();
        try {
            String json = mapper.writeValueAsString(entry);
            Files.writeString(file, json + "\n",
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            return true;
        } catch (IOException e) {
            log.error("Failed to persist memory entry: {}", e.getMessage());
            return false;
        } finally {
            fileLock.unlock();
        }
    }

    /**
     * Rewrites a JSONL file, keeping only entries that exist in the in-memory index.
     * Must be called while holding {@link #fileLock}.
     *
     * <p>Uses atomic write-to-temp-then-rename to prevent corruption if the process
     * crashes mid-write.
     */
    private void rewriteFile(Path file) {
        if (!Files.isRegularFile(file)) return;

        try {
            // Re-read the file to get only entries that still exist in our index
            var existingIds = entries.stream()
                    .map(MemoryEntry::id)
                    .collect(Collectors.toSet());

            var lines = Files.readAllLines(file);
            var kept = new StringBuilder();
            for (String line : lines) {
                if (line.isBlank()) continue;
                try {
                    var entry = mapper.readValue(line, MemoryEntry.class);
                    if (existingIds.contains(entry.id())) {
                        kept.append(line).append("\n");
                    }
                } catch (Exception ignored) {
                    // Skip malformed lines
                }
            }

            // Atomic write: write to temp file, then rename
            Path tmpFile = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmpFile, kept.toString(),
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            Files.move(tmpFile, file, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            log.error("Failed to rewrite memory file {}: {}", file, e.getMessage());
        }
    }

    private byte[] loadOrCreateKey() throws IOException {
        Path keyFile = memoryDir.resolve(KEY_FILE);
        if (Files.isRegularFile(keyFile)) {
            byte[] key = Files.readAllBytes(keyFile);
            if (key.length >= KEY_SIZE_BYTES) {
                return key;
            }
            log.warn("Memory key file is too short ({}B), regenerating", key.length);
        }

        // Generate a new random key
        byte[] key = new byte[KEY_SIZE_BYTES];
        new SecureRandom().nextBytes(key);
        Files.write(keyFile, key, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);

        // Set POSIX 600 permissions (owner read/write only) to protect the signing key
        try {
            Files.setPosixFilePermissions(keyFile, Set.of(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE));
        } catch (UnsupportedOperationException e) {
            // Non-POSIX filesystem (e.g. Windows) — skip permission setting
            log.debug("POSIX permissions not supported on this filesystem");
        }

        log.info("Generated new memory signing key at {}", keyFile);
        return key;
    }

    private long countByFile(String fileName) {
        Path file = memoryDir.resolve(fileName);
        if (!Files.isRegularFile(file)) return 0;
        try (var stream = Files.lines(file)) {
            return stream.filter(l -> !l.isBlank()).count();
        } catch (IOException e) {
            return 0;
        }
    }

    private static String projectHash(Path projectPath) {
        // Use a short deterministic hash of the absolute path
        String abs = projectPath.toAbsolutePath().normalize().toString();
        int hash = abs.hashCode();
        return "project-" + Integer.toHexString(hash);
    }

    private static String formatCategory(MemoryEntry.Category category) {
        return switch (category) {
            case MISTAKE -> "Mistakes to Avoid";
            case PATTERN -> "Code Patterns";
            case PREFERENCE -> "User Preferences";
            case CODEBASE_INSIGHT -> "Codebase Insights";
            case STRATEGY -> "Strategies";
            case WORKFLOW -> "Workflows";
            case ENVIRONMENT -> "Environment";
            case RELATIONSHIP -> "Relationships";
            case TERMINOLOGY -> "Terminology";
            case CONSTRAINT -> "Constraints";
            case DECISION -> "Decisions";
            case TOOL_USAGE -> "Tool Usage";
            case COMMUNICATION -> "Communication";
            case CONTEXT -> "Context";
            case CORRECTION -> "Corrections";
            case BOOKMARK -> "Bookmarks";
            case SESSION_SUMMARY -> "Session Summaries";
            case ERROR_RECOVERY -> "Error Recoveries";
            case SUCCESSFUL_STRATEGY -> "Successful Strategies";
            case ANTI_PATTERN -> "Anti-Patterns";
            case USER_FEEDBACK -> "User Feedback";
        };
    }
}
