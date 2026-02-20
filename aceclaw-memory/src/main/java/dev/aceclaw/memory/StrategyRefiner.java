package dev.aceclaw.memory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Heuristic-based strategy refinement that consolidates raw insights into
 * higher-level strategies, anti-patterns, and strengthened preferences.
 *
 * <p>Four refinement strategies:
 * <ol>
 *   <li><strong>Error Recovery Consolidation</strong> — groups similar ERROR_RECOVERY entries
 *       by tool tag and creates a single SUCCESSFUL_STRATEGY</li>
 *   <li><strong>Tool Sequence Optimization</strong> — groups similar PATTERN entries tagged
 *       "tool-sequence" into consolidated strategy entries</li>
 *   <li><strong>Anti-Pattern Generation</strong> — detects recurring errors without clear
 *       resolution and creates ANTI_PATTERN entries</li>
 *   <li><strong>User Preference Strengthening</strong> — groups similar CORRECTION/PREFERENCE
 *       entries into consolidated PREFERENCE entries</li>
 * </ol>
 *
 * <p>Each strategy requires at least {@value #MIN_ENTRIES_TO_CONSOLIDATE} similar entries
 * to trigger consolidation. Old entries are removed and replaced with the refined strategy.
 *
 * <p>This class is stateless; debouncing is managed by the caller ({@code SelfImprovementEngine}).
 */
public final class StrategyRefiner {

    private static final Logger log = LoggerFactory.getLogger(StrategyRefiner.class);

    /** Minimum number of similar entries required to trigger consolidation. */
    static final int MIN_ENTRIES_TO_CONSOLIDATE = 3;

    /** Jaccard similarity threshold for grouping error recoveries and sequences. */
    static final double SIMILARITY_THRESHOLD = 0.7;

    /** Lower Jaccard threshold for user preference grouping (user text varies more). */
    static final double PREFERENCE_SIMILARITY_THRESHOLD = 0.6;

    /** Maximum character length for generated strategy content. */
    static final int MAX_CONTENT_LENGTH = 300;

    private static final String SOURCE_TAG = "strategy-refiner";

    private final AutoMemoryStore memoryStore;

    public StrategyRefiner(AutoMemoryStore memoryStore) {
        this.memoryStore = Objects.requireNonNull(memoryStore, "memoryStore");
    }

    /**
     * Result of a refinement run.
     *
     * @param strategiesCreated      number of new SUCCESSFUL_STRATEGY entries created
     * @param entriesConsolidated    total number of old entries removed
     * @param antiPatternsCreated    number of new ANTI_PATTERN entries created
     * @param preferencesStrengthened number of new consolidated PREFERENCE entries created
     */
    public record RefinementResult(int strategiesCreated, int entriesConsolidated,
                                    int antiPatternsCreated, int preferencesStrengthened) {
        public boolean hasChanges() {
            return strategiesCreated > 0 || entriesConsolidated > 0
                    || antiPatternsCreated > 0 || preferencesStrengthened > 0;
        }
    }

    /**
     * Runs all four refinement strategies on the memory store.
     *
     * @param recentInsights recent insights from the current analysis (used for context, not directly consumed)
     * @param projectPath    the project directory for memory storage
     * @return the refinement result
     */
    public RefinementResult refine(List<Insight> recentInsights, Path projectPath) {
        int strategies = 0;
        int consolidated = 0;
        int antiPatterns = 0;
        int preferences = 0;

        try {
            var r1 = consolidateErrorRecoveries(projectPath);
            strategies += r1[0];
            consolidated += r1[1];
        } catch (Exception e) {
            log.warn("Error recovery consolidation failed: {}", e.getMessage());
        }

        try {
            var r2 = optimizeToolSequences(projectPath);
            strategies += r2[0];
            consolidated += r2[1];
        } catch (Exception e) {
            log.warn("Tool sequence optimization failed: {}", e.getMessage());
        }

        try {
            var r3 = generateAntiPatterns(projectPath);
            antiPatterns += r3[0];
            consolidated += r3[1];
        } catch (Exception e) {
            log.warn("Anti-pattern generation failed: {}", e.getMessage());
        }

        try {
            var r4 = strengthenPreferences(projectPath);
            preferences += r4[0];
            consolidated += r4[1];
        } catch (Exception e) {
            log.warn("Preference strengthening failed: {}", e.getMessage());
        }

        var result = new RefinementResult(strategies, consolidated, antiPatterns, preferences);
        if (result.hasChanges()) {
            log.info("Strategy refinement: {} strategies, {} consolidated, {} anti-patterns, {} preferences",
                    strategies, consolidated, antiPatterns, preferences);
        }
        return result;
    }

    /**
     * Strategy 1: Group similar ERROR_RECOVERY entries by tool tag.
     * 3+ similar → create 1 SUCCESSFUL_STRATEGY, remove old entries.
     *
     * @return int[]{strategiesCreated, entriesConsolidated}
     */
    private int[] consolidateErrorRecoveries(Path projectPath) {
        var entries = memoryStore.query(MemoryEntry.Category.ERROR_RECOVERY, null, 0);
        if (entries.size() < MIN_ENTRIES_TO_CONSOLIDATE) return new int[]{0, 0};

        // Skip already-refined entries, and only consolidate entries with clear resolutions
        entries = entries.stream()
                .filter(e -> !e.tags().contains("strategy-refined"))
                .filter(e -> {
                    String lower = e.content().toLowerCase();
                    return lower.contains("resolved by") || lower.contains("fix:");
                })
                .toList();
        if (entries.size() < MIN_ENTRIES_TO_CONSOLIDATE) return new int[]{0, 0};

        var groups = groupBySimilarity(entries, SIMILARITY_THRESHOLD);

        int strategiesCreated = 0;
        int entriesRemoved = 0;

        for (var group : groups) {
            if (group.size() < MIN_ENTRIES_TO_CONSOLIDATE) continue;

            // Find the most common tool tag
            String toolTag = findMostCommonTag(group);
            String commonResolution = findCommonContent(group);

            String content = truncate("When %s fails, resolve by: %s".formatted(
                    toolTag != null ? toolTag : "tool", commonResolution));

            var tags = new ArrayList<String>();
            if (toolTag != null) tags.add(toolTag);
            tags.add("strategy-refined");
            tags.add("error-consolidated");

            memoryStore.add(MemoryEntry.Category.SUCCESSFUL_STRATEGY, content,
                    tags, SOURCE_TAG, false, projectPath);

            for (var entry : group) {
                memoryStore.remove(entry.id(), projectPath);
                entriesRemoved++;
            }
            strategiesCreated++;
        }

        return new int[]{strategiesCreated, entriesRemoved};
    }

    /**
     * Strategy 2: Group similar PATTERN entries tagged "tool-sequence".
     * 3+ similar → create SUCCESSFUL_STRATEGY with most common sequence.
     *
     * @return int[]{strategiesCreated, entriesConsolidated}
     */
    private int[] optimizeToolSequences(Path projectPath) {
        var entries = memoryStore.query(MemoryEntry.Category.PATTERN,
                List.of("tool-sequence", "repeated_tool_sequence"), 0);
        if (entries.size() < MIN_ENTRIES_TO_CONSOLIDATE) return new int[]{0, 0};

        // Skip already-refined entries
        entries = entries.stream()
                .filter(e -> !e.tags().contains("strategy-refined"))
                .toList();
        if (entries.size() < MIN_ENTRIES_TO_CONSOLIDATE) return new int[]{0, 0};

        var groups = groupBySimilarity(entries, SIMILARITY_THRESHOLD);

        int strategiesCreated = 0;
        int entriesRemoved = 0;

        for (var group : groups) {
            if (group.size() < MIN_ENTRIES_TO_CONSOLIDATE) continue;

            // Use the shortest content (most concise representation)
            var shortest = group.stream()
                    .min(Comparator.comparingInt(e -> e.content().length()))
                    .orElse(group.getFirst());

            String content = truncate("Optimized tool sequence: %s".formatted(shortest.content()));

            // Collect tool tags from group
            var allTags = new LinkedHashSet<String>();
            for (var entry : group) {
                for (var tag : entry.tags()) {
                    if (!tag.equals("pattern") && !tag.equals("tool-sequence")
                            && !tag.equals("repeated_tool_sequence")) {
                        allTags.add(tag);
                    }
                }
            }
            var tags = new ArrayList<>(allTags);
            tags.add("strategy-refined");
            tags.add("sequence-optimized");

            memoryStore.add(MemoryEntry.Category.SUCCESSFUL_STRATEGY, content,
                    tags, SOURCE_TAG, false, projectPath);

            for (var entry : group) {
                memoryStore.remove(entry.id(), projectPath);
                entriesRemoved++;
            }
            strategiesCreated++;
        }

        return new int[]{strategiesCreated, entriesRemoved};
    }

    /**
     * Strategy 3: Detect recurring errors without clear resolution → create ANTI_PATTERN.
     * 3+ similar error descriptions → anti-pattern.
     *
     * @return int[]{antiPatternsCreated, entriesConsolidated}
     */
    private int[] generateAntiPatterns(Path projectPath) {
        var entries = memoryStore.query(MemoryEntry.Category.ERROR_RECOVERY, null, 0);
        if (entries.size() < MIN_ENTRIES_TO_CONSOLIDATE) return new int[]{0, 0};

        // Focus on entries that look like unresolved errors (content lacks "resolve" or "fix")
        var unresolvedEntries = entries.stream()
                .filter(e -> !e.tags().contains("strategy-refined")
                        && !e.tags().contains("anti-pattern")
                        && !e.tags().contains("error-consolidated"))
                .filter(e -> {
                    String lower = e.content().toLowerCase();
                    return !lower.contains("resolved by") && !lower.contains("fix:");
                })
                .toList();

        if (unresolvedEntries.size() < MIN_ENTRIES_TO_CONSOLIDATE) return new int[]{0, 0};

        var groups = groupBySimilarity(unresolvedEntries, SIMILARITY_THRESHOLD);

        int antiPatternsCreated = 0;
        int entriesRemoved = 0;

        for (var group : groups) {
            if (group.size() < MIN_ENTRIES_TO_CONSOLIDATE) continue;

            String toolTag = findMostCommonTag(group);
            String errorDescription = findCommonContent(group);

            String content = truncate("Avoid: %s — causes recurring errors".formatted(errorDescription));

            var tags = new ArrayList<String>();
            if (toolTag != null) tags.add(toolTag);
            tags.add("anti-pattern");

            memoryStore.add(MemoryEntry.Category.ANTI_PATTERN, content,
                    tags, SOURCE_TAG, false, projectPath);

            for (var entry : group) {
                memoryStore.remove(entry.id(), projectPath);
                entriesRemoved++;
            }
            antiPatternsCreated++;
        }

        return new int[]{antiPatternsCreated, entriesRemoved};
    }

    /**
     * Strategy 4: Group similar CORRECTION + PREFERENCE entries → consolidated PREFERENCE.
     * Uses lower similarity threshold (0.6) since user text varies more.
     *
     * @return int[]{preferencesCreated, entriesConsolidated}
     */
    private int[] strengthenPreferences(Path projectPath) {
        var corrections = memoryStore.query(MemoryEntry.Category.CORRECTION, null, 0);
        var preferences = memoryStore.query(MemoryEntry.Category.PREFERENCE, null, 0);

        var combined = new ArrayList<MemoryEntry>();
        combined.addAll(corrections);
        combined.addAll(preferences);

        // Skip already-strengthened entries
        combined = combined.stream()
                .filter(e -> !e.tags().contains("preference-strengthened"))
                .collect(Collectors.toCollection(ArrayList::new));

        if (combined.size() < MIN_ENTRIES_TO_CONSOLIDATE) return new int[]{0, 0};

        var groups = groupBySimilarity(combined, PREFERENCE_SIMILARITY_THRESHOLD);

        int preferencesCreated = 0;
        int entriesRemoved = 0;

        for (var group : groups) {
            if (group.size() < MIN_ENTRIES_TO_CONSOLIDATE) continue;

            String commonContent = findCommonContent(group);
            String content = truncate("User preference: %s".formatted(commonContent));

            var tags = List.of("preference-strengthened");

            memoryStore.add(MemoryEntry.Category.PREFERENCE, content,
                    tags, SOURCE_TAG, false, projectPath);

            for (var entry : group) {
                memoryStore.remove(entry.id(), projectPath);
                entriesRemoved++;
            }
            preferencesCreated++;
        }

        return new int[]{preferencesCreated, entriesRemoved};
    }

    // -- Utility methods --

    /**
     * Groups entries by Jaccard similarity. Returns groups of similar entries.
     */
    private List<List<MemoryEntry>> groupBySimilarity(List<MemoryEntry> entries, double threshold) {
        var groups = new ArrayList<List<MemoryEntry>>();
        var assigned = new boolean[entries.size()];

        for (int i = 0; i < entries.size(); i++) {
            if (assigned[i]) continue;

            var group = new ArrayList<MemoryEntry>();
            group.add(entries.get(i));
            assigned[i] = true;

            for (int j = i + 1; j < entries.size(); j++) {
                if (assigned[j]) continue;

                double similarity = jaccardSimilarity(
                        entries.get(i).content(), entries.get(j).content());
                if (similarity >= threshold) {
                    group.add(entries.get(j));
                    assigned[j] = true;
                }
            }

            groups.add(group);
        }

        return groups;
    }

    /**
     * Finds the most common non-generic tag across a group of entries.
     * Skips meta-tags like "error-recovery", "pattern", etc.
     */
    private String findMostCommonTag(List<MemoryEntry> group) {
        var tagCounts = new HashMap<String, Integer>();
        var metaTags = Set.of("error-recovery", "pattern", "tool-sequence",
                "repeated_tool_sequence", "successful-strategy", "strategy-refined",
                "error-consolidated", "anti-pattern", "preference-strengthened");

        for (var entry : group) {
            for (var tag : entry.tags()) {
                if (!metaTags.contains(tag)) {
                    tagCounts.merge(tag, 1, Integer::sum);
                }
            }
        }

        return tagCounts.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse(null);
    }

    /**
     * Extracts common content from a group of similar entries.
     * Returns the shortest entry's content as the most concise representation.
     */
    private String findCommonContent(List<MemoryEntry> group) {
        return group.stream()
                .min(Comparator.comparingInt(e -> e.content().length()))
                .map(MemoryEntry::content)
                .orElse("unknown");
    }

    /**
     * Computes Jaccard similarity between two strings based on token sets.
     */
    static double jaccardSimilarity(String a, String b) {
        if (a == null || b == null || a.isBlank() || b.isBlank()) return 0.0;

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

    private static String truncate(String text) {
        if (text == null) return "";
        if (text.length() <= MAX_CONTENT_LENGTH) return text;
        return text.substring(0, MAX_CONTENT_LENGTH - 3) + "...";
    }
}
