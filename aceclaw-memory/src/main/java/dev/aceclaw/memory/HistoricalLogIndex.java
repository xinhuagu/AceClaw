package dev.aceclaw.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import dev.aceclaw.core.agent.ToolMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Append-only historical index over session-close snapshots.
 *
 * <p>The index is global to the AceClaw home directory and supports lightweight
 * cross-session queries without replaying every transcript.
 */
public final class HistoricalLogIndex {

    private static final Logger log = LoggerFactory.getLogger(HistoricalLogIndex.class);

    private static final String INDEX_DIR = "index";
    private static final String TOOL_FILE = "tool_invocations.jsonl";
    private static final String ERROR_FILE = "errors.jsonl";
    private static final String PATTERN_FILE = "patterns.jsonl";

    private final Path indexDir;
    private final Path toolFile;
    private final Path errorFile;
    private final Path patternFile;
    private final ObjectMapper mapper;
    private final ReentrantLock fileLock = new ReentrantLock();

    public HistoricalLogIndex(Path aceclawHome) throws IOException {
        Objects.requireNonNull(aceclawHome, "aceclawHome");
        this.indexDir = aceclawHome.resolve(INDEX_DIR);
        Files.createDirectories(indexDir);
        this.toolFile = indexDir.resolve(TOOL_FILE);
        this.errorFile = indexDir.resolve(ERROR_FILE);
        this.patternFile = indexDir.resolve(PATTERN_FILE);
        this.mapper = new ObjectMapper().registerModule(new JavaTimeModule());
    }

    public void index(HistoricalSessionSnapshot snapshot) throws IOException {
        Objects.requireNonNull(snapshot, "snapshot");
        fileLock.lock();
        try {
            for (var toolEntry : toToolEntries(snapshot)) {
                append(toolFile, toolEntry);
            }
            for (var errorEntry : toErrorEntries(snapshot)) {
                append(errorFile, errorEntry);
            }
            for (var patternEntry : toPatternEntries(snapshot)) {
                append(patternFile, patternEntry);
            }
        } finally {
            fileLock.unlock();
        }
    }

    public List<ToolInvocationEntry> queryByTool(String toolName, Instant from, Instant to) {
        Objects.requireNonNull(toolName, "toolName");
        return toolInvocations(null, from, to).stream()
                .filter(entry -> toolName.equals(entry.tool()))
                .sorted(Comparator.comparing(ToolInvocationEntry::timestamp).reversed())
                .toList();
    }

    public List<ErrorEntry> queryByErrorClass(ErrorClass errorClass, Instant from, Instant to) {
        Objects.requireNonNull(errorClass, "errorClass");
        return errorEntries(null, from, to).stream()
                .filter(entry -> entry.errorClass() == errorClass)
                .sorted(Comparator.comparing(ErrorEntry::timestamp).reversed())
                .toList();
    }

    public List<ToolInvocationEntry> toolInvocations(String workspaceHash, Instant from, Instant to) {
        return read(toolFile, ToolInvocationEntry.class).stream()
                .filter(entry -> withinWorkspace(entry.workspaceHash(), workspaceHash))
                .filter(entry -> withinRange(entry.timestamp(), from, to))
                .toList();
    }

    public List<ErrorEntry> errorEntries(String workspaceHash, Instant from, Instant to) {
        return read(errorFile, ErrorEntry.class).stream()
                .filter(entry -> withinWorkspace(entry.workspaceHash(), workspaceHash))
                .filter(entry -> withinRange(entry.timestamp(), from, to))
                .toList();
    }

    public Map<String, Integer> toolInvocationCounts(Instant from, Instant to) {
        var counts = new HashMap<String, Integer>();
        for (var entry : toolInvocations(null, from, to)) {
            counts.merge(entry.tool(), entry.invocationCount(), Integer::sum);
        }
        return Map.copyOf(counts);
    }

    public Map<ErrorClass, Integer> errorCounts(Instant from, Instant to) {
        var counts = new EnumMap<ErrorClass, Integer>(ErrorClass.class);
        for (var entry : errorEntries(null, from, to)) {
            counts.merge(entry.errorClass(), 1, Integer::sum);
        }
        return Map.copyOf(counts);
    }

    public Set<String> sessionIds(String workspaceHash) {
        Objects.requireNonNull(workspaceHash, "workspaceHash");
        if (workspaceHash.isBlank()) {
            throw new IllegalArgumentException("workspaceHash must not be blank");
        }
        fileLock.lock();
        try {
            var ids = new LinkedHashSet<String>();
            toolInvocations(workspaceHash, null, null).forEach(entry -> ids.add(entry.sessionId()));
            errorEntries(workspaceHash, null, null).forEach(entry -> ids.add(entry.sessionId()));
            patterns(workspaceHash, null, null).forEach(entry -> ids.add(entry.sessionId()));
            return Set.copyOf(ids);
        } finally {
            fileLock.unlock();
        }
    }

    public void clearWorkspace(String workspaceHash) throws IOException {
        Objects.requireNonNull(workspaceHash, "workspaceHash");
        if (workspaceHash.isBlank()) {
            throw new IllegalArgumentException("workspaceHash must not be blank");
        }

        fileLock.lock();
        try {
            var retainedTools = new ArrayList<>(toolInvocations(null, null, null).stream()
                    .filter(entry -> !workspaceHash.equals(entry.workspaceHash()))
                    .toList());
            var retainedErrors = new ArrayList<>(errorEntries(null, null, null).stream()
                    .filter(entry -> !workspaceHash.equals(entry.workspaceHash()))
                    .toList());
            var retainedPatterns = new ArrayList<>(patterns(null, null, null).stream()
                    .filter(entry -> !workspaceHash.equals(entry.workspaceHash()))
                    .toList());

            rewrite(toolFile, retainedTools);
            rewrite(errorFile, retainedErrors);
            rewrite(patternFile, retainedPatterns);
        } finally {
            fileLock.unlock();
        }
    }

    public void replaceSessions(String workspaceHash, List<HistoricalSessionSnapshot> snapshots) throws IOException {
        Objects.requireNonNull(workspaceHash, "workspaceHash");
        Objects.requireNonNull(snapshots, "snapshots");
        if (workspaceHash.isBlank()) {
            throw new IllegalArgumentException("workspaceHash must not be blank");
        }

        fileLock.lock();
        try {
            var sessionIdsToReplace = snapshots.stream()
                    .map(HistoricalSessionSnapshot::sessionId)
                    .collect(java.util.stream.Collectors.toSet());
            var retainedTools = new ArrayList<>(toolInvocations(null, null, null).stream()
                    .filter(entry -> !workspaceHash.equals(entry.workspaceHash())
                            || !sessionIdsToReplace.contains(entry.sessionId()))
                    .toList());
            var retainedErrors = new ArrayList<>(errorEntries(null, null, null).stream()
                    .filter(entry -> !workspaceHash.equals(entry.workspaceHash())
                            || !sessionIdsToReplace.contains(entry.sessionId()))
                    .toList());
            var retainedPatterns = new ArrayList<>(patterns(null, null, null).stream()
                    .filter(entry -> !workspaceHash.equals(entry.workspaceHash())
                            || !sessionIdsToReplace.contains(entry.sessionId()))
                    .toList());

            for (var snapshot : snapshots) {
                if (!workspaceHash.equals(snapshot.workspaceHash())) {
                    throw new IllegalArgumentException("snapshot workspaceHash does not match replace target");
                }
                retainedTools.addAll(toToolEntries(snapshot));
                retainedErrors.addAll(toErrorEntries(snapshot));
                retainedPatterns.addAll(toPatternEntries(snapshot));
            }

            rewrite(toolFile, retainedTools);
            rewrite(errorFile, retainedErrors);
            rewrite(patternFile, retainedPatterns);
        } finally {
            fileLock.unlock();
        }
    }

    public List<PatternEntry> patterns(Instant from, Instant to) {
        return patterns(null, from, to).stream()
                .sorted(Comparator.comparing(PatternEntry::timestamp).reversed())
                .toList();
    }

    public List<PatternEntry> patterns(String workspaceHash, Instant from, Instant to) {
        return read(patternFile, PatternEntry.class).stream()
                .filter(entry -> withinWorkspace(entry.workspaceHash(), workspaceHash))
                .filter(entry -> withinRange(entry.timestamp(), from, to))
                .toList();
    }

    private List<ToolInvocationEntry> toToolEntries(HistoricalSessionSnapshot snapshot) {
        var entries = new ArrayList<ToolInvocationEntry>();
        for (var metric : snapshot.toolMetrics().values()) {
            entries.add(new ToolInvocationEntry(
                    snapshot.sessionId(),
                    snapshot.workspaceHash(),
                    metric.toolName(),
                    snapshot.closedAt(),
                    metric.totalInvocations(),
                    metric.successCount(),
                    metric.errorCount(),
                    metric.totalExecutionMs()
            ));
        }
        return entries;
    }

    private List<ErrorEntry> toErrorEntries(HistoricalSessionSnapshot snapshot) {
        var entries = new ArrayList<ErrorEntry>();
        String inferredTool = inferTool(snapshot);
        for (int i = 0; i < snapshot.errorsEncountered().size(); i++) {
            var error = snapshot.errorsEncountered().get(i);
            entries.add(new ErrorEntry(
                    snapshot.sessionId(),
                    snapshot.workspaceHash(),
                    inferredTool,
                    ErrorClass.classify(error),
                    error,
                    ErrorClass.classify(error).defaultRecovery(),
                    i,
                    snapshot.closedAt()
            ));
        }
        return entries;
    }

    private List<PatternEntry> toPatternEntries(HistoricalSessionSnapshot snapshot) {
        var entries = new ArrayList<PatternEntry>();
        if (snapshot.backtrackingDetected()) {
            entries.add(new PatternEntry(
                    snapshot.sessionId(),
                    snapshot.workspaceHash(),
                    PatternType.ERROR_CORRECTION,
                    0.7,
                    "Session required backtracking after an error or correction.",
                    snapshot.closedAt()
            ));
        }
        if (!snapshot.endToEndStrategy().isBlank()) {
            entries.add(new PatternEntry(
                    snapshot.sessionId(),
                    snapshot.workspaceHash(),
                    PatternType.WORKFLOW,
                    0.6,
                    snapshot.endToEndStrategy(),
                    snapshot.closedAt()
            ));
        }
        return entries;
    }

    private String inferTool(HistoricalSessionSnapshot snapshot) {
        if (snapshot.toolMetrics().size() == 1) {
            return snapshot.toolMetrics().keySet().iterator().next();
        }
        for (var command : snapshot.executedCommands()) {
            String normalized = command.trim().toLowerCase(Locale.ROOT);
            if (normalized.startsWith("$ ")) {
                normalized = normalized.substring(2).trim();
            }
            int space = normalized.indexOf(' ');
            String tool = space >= 0 ? normalized.substring(0, space) : normalized;
            if (!tool.isBlank()) {
                return tool;
            }
        }
        return "unknown";
    }

    private <T> void append(Path file, T entry) throws IOException {
        byte[] line = (mapper.writeValueAsString(entry) + "\n").getBytes(StandardCharsets.UTF_8);
        try (var channel = FileChannel.open(file,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND)) {
            try (var ignored = channel.lock()) {
                channel.write(ByteBuffer.wrap(line));
            }
        }
    }

    private <T> void rewrite(Path file, List<T> entries) throws IOException {
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        var builder = new StringBuilder();
        for (var entry : entries) {
            builder.append(mapper.writeValueAsString(entry)).append('\n');
        }
        Files.writeString(tmp, builder.toString(),
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        Files.move(tmp, file,
                java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                java.nio.file.StandardCopyOption.ATOMIC_MOVE);
    }

    private <T> List<T> read(Path file, Class<T> type) {
        if (!Files.exists(file)) {
            return List.of();
        }
        try {
            var entries = new ArrayList<T>();
            for (var line : Files.readAllLines(file)) {
                if (line == null || line.isBlank()) {
                    continue;
                }
                try {
                    entries.add(mapper.readValue(line, type));
                } catch (IOException e) {
                    log.warn("Skipping malformed historical index line in {}: {}", file.getFileName(), e.getMessage());
                }
            }
            return List.copyOf(entries);
        } catch (IOException e) {
            log.warn("Failed to read historical index {}: {}", file.getFileName(), e.getMessage());
            return List.of();
        }
    }

    private static boolean withinRange(Instant ts, Instant from, Instant to) {
        if (from != null && ts.isBefore(from)) {
            return false;
        }
        if (to != null && ts.isAfter(to)) {
            return false;
        }
        return true;
    }

    private static boolean withinWorkspace(String entryWorkspaceHash, String workspaceHash) {
        if (workspaceHash == null || workspaceHash.isBlank()) {
            return true;
        }
        return workspaceHash.equals(entryWorkspaceHash);
    }

    public record ToolInvocationEntry(
            String sessionId,
            String workspaceHash,
            String tool,
            Instant timestamp,
            int invocationCount,
            int successCount,
            int errorCount,
            long totalDurationMs
    ) {}

    public record ErrorEntry(
            String sessionId,
            String workspaceHash,
            String tool,
            ErrorClass errorClass,
            String message,
            String resolution,
            int sequence,
            Instant timestamp
    ) {}

    public record PatternEntry(
            String sessionId,
            String workspaceHash,
            PatternType patternType,
            double confidence,
            String description,
            Instant timestamp
    ) {}
}
