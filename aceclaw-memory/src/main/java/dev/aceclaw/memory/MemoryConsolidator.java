package dev.aceclaw.memory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Periodic memory maintenance: deduplication, similarity merging, and age-based pruning.
 *
 * <p>Consolidation passes:
 * <ol>
 *   <li><strong>Exact dedup</strong> — remove entries with identical content (keep newest)</li>
 *   <li><strong>Similarity merge</strong> — entries with >80% token overlap in same category
 *       are merged into one, keeping the newest and best tags</li>
 *   <li><strong>Age prune</strong> — entries older than 90 days with no matching tags
 *       to recent sessions are archived (moved to archived.jsonl)</li>
 * </ol>
 *
 * <p>Triggered at session end, runs on a virtual thread.
 */
public final class MemoryConsolidator {

    private static final Logger log = LoggerFactory.getLogger(MemoryConsolidator.class);

    private static final double SIMILARITY_THRESHOLD = 0.80;
    private static final Duration AGE_THRESHOLD = Duration.ofDays(90);
    private static final String ARCHIVE_FILE = "archived.jsonl";

    private MemoryConsolidator() {}

    /**
     * Result of a consolidation run.
     *
     * @param deduped number of duplicate entries removed
     * @param merged  number of entries merged (pairs reduced to singles)
     * @param pruned  number of entries archived due to age
     */
    public record ConsolidationResult(int deduped, int merged, int pruned) {
        public boolean hasChanges() {
            return deduped > 0 || merged > 0 || pruned > 0;
        }
    }

    /**
     * Runs all consolidation passes on the given memory store.
     *
     * @param store       the auto-memory store to consolidate
     * @param projectPath the project directory for file operations
     * @param archiveDir  the directory for archived entries
     * @return the consolidation result
     */
    public static ConsolidationResult consolidate(AutoMemoryStore store, Path projectPath,
                                                   Path archiveDir) {
        if (store == null || store.size() == 0) {
            return new ConsolidationResult(0, 0, 0);
        }

        var entries = new ArrayList<>(store.entries());
        int originalSize = entries.size();

        // Pass 1: Exact dedup
        int deduped = dedup(entries);

        // Pass 2: Similarity merge
        int merged = similarityMerge(entries);

        // Pass 3: Age prune
        int pruned = agePrune(entries, archiveDir);

        if (deduped > 0 || merged > 0 || pruned > 0) {
            store.replaceEntries(entries, projectPath);
            log.info("Consolidation complete: {} deduped, {} merged, {} pruned (from {} entries)",
                    deduped, merged, pruned, originalSize);
        }

        return new ConsolidationResult(deduped, merged, pruned);
    }

    /**
     * Pass 1: Remove entries with identical content, keeping the newest.
     */
    static int dedup(List<MemoryEntry> entries) {
        var seen = new HashMap<String, MemoryEntry>();
        var toRemove = new ArrayList<MemoryEntry>();

        for (var entry : entries) {
            String key = entry.category() + "|" + entry.content().strip();
            var existing = seen.get(key);
            if (existing != null) {
                // Keep the newer one
                if (entry.createdAt().isAfter(existing.createdAt())) {
                    toRemove.add(existing);
                    seen.put(key, entry);
                } else {
                    toRemove.add(entry);
                }
            } else {
                seen.put(key, entry);
            }
        }

        entries.removeAll(toRemove);
        return toRemove.size();
    }

    /**
     * Pass 2: Merge entries in the same category with >80% token overlap.
     */
    static int similarityMerge(List<MemoryEntry> entries) {
        int mergeCount = 0;
        var toRemove = new ArrayList<MemoryEntry>();

        // Group by category for comparison
        var byCategory = entries.stream()
                .collect(Collectors.groupingBy(MemoryEntry::category));

        for (var catEntries : byCategory.values()) {
            if (catEntries.size() < 2) continue;

            for (int i = 0; i < catEntries.size(); i++) {
                if (toRemove.contains(catEntries.get(i))) continue;

                for (int j = i + 1; j < catEntries.size(); j++) {
                    if (toRemove.contains(catEntries.get(j))) continue;

                    var a = catEntries.get(i);
                    var b = catEntries.get(j);

                    double similarity = tokenSimilarity(a.content(), b.content());
                    if (similarity >= SIMILARITY_THRESHOLD) {
                        // Keep the newer one with merged tags
                        var newer = a.createdAt().isAfter(b.createdAt()) ? a : b;
                        var older = newer == a ? b : a;

                        // Merge tags from both entries
                        var mergedTags = new LinkedHashSet<>(newer.tags());
                        mergedTags.addAll(older.tags());

                        var merged = new MemoryEntry(
                                newer.id(), newer.category(), newer.content(),
                                List.copyOf(mergedTags), newer.createdAt(),
                                newer.source(), newer.hmac(),
                                newer.accessCount() + older.accessCount(),
                                newer.lastAccessedAt());

                        // Replace the newer entry with the merged version
                        int idx = entries.indexOf(newer);
                        if (idx >= 0) {
                            entries.set(idx, merged);
                        }

                        toRemove.add(older);
                        mergeCount++;
                    }
                }
            }
        }

        entries.removeAll(toRemove);
        return mergeCount;
    }

    /**
     * Pass 3: Archive entries older than 90 days with zero access count.
     *
     * <p>If {@code archiveDir} is null, pruning is skipped entirely to prevent
     * silent data loss (entries would be removed without being archived).
     */
    static int agePrune(List<MemoryEntry> entries, Path archiveDir) {
        if (archiveDir == null) {
            log.debug("Skipping age pruning: no archive directory configured");
            return 0;
        }

        Instant cutoff = Instant.now().minus(AGE_THRESHOLD);
        var toArchive = new ArrayList<MemoryEntry>();

        for (var entry : entries) {
            if (entry.createdAt().isBefore(cutoff) && entry.accessCount() == 0) {
                toArchive.add(entry);
            }
        }

        if (toArchive.isEmpty()) {
            return 0;
        }

        try {
            Files.createDirectories(archiveDir);
            Path archiveFile = archiveDir.resolve(ARCHIVE_FILE);
            var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            mapper.registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());

            var sb = new StringBuilder();
            for (var entry : toArchive) {
                sb.append(mapper.writeValueAsString(entry)).append("\n");
            }
            Files.writeString(archiveFile, sb.toString(),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            log.debug("Archived {} entries to {}", toArchive.size(), archiveFile);
        } catch (IOException e) {
            log.warn("Failed to write archive file: {}", e.getMessage());
            return 0; // Don't remove entries if archiving failed
        }

        entries.removeAll(toArchive);
        return toArchive.size();
    }

    /**
     * Computes the Jaccard similarity between two strings based on token sets.
     * Returns a value between 0.0 (no overlap) and 1.0 (identical tokens).
     */
    static double tokenSimilarity(String a, String b) {
        var tokensA = tokenize(a);
        var tokensB = tokenize(b);

        if (tokensA.isEmpty() && tokensB.isEmpty()) return 1.0;
        if (tokensA.isEmpty() || tokensB.isEmpty()) return 0.0;

        var intersection = new HashSet<>(tokensA);
        intersection.retainAll(tokensB);

        var union = new HashSet<>(tokensA);
        union.addAll(tokensB);

        return (double) intersection.size() / union.size();
    }

    private static Set<String> tokenize(String text) {
        if (text == null || text.isBlank()) return Set.of();

        return Arrays.stream(text.toLowerCase().split("\\W+"))
                .filter(t -> t.length() >= 2)
                .collect(Collectors.toSet());
    }
}
