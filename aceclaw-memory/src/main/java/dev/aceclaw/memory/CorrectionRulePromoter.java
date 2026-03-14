package dev.aceclaw.memory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Detects repeated corrections/mistakes in auto-memory and promotes them
 * to hard rules in ACECLAW.md.
 *
 * <p>This closes the self-learning loop: when the agent makes the same mistake
 * 2+ times across sessions, the correction is automatically elevated from
 * Tier 6 (auto-memory, low priority, easily drowned in noise) to Tier 3
 * (workspace memory, high priority, always visible in system prompt).
 *
 * <p>Lifecycle:
 * <ol>
 *   <li>Scan auto-memory for CORRECTION and MISTAKE entries</li>
 *   <li>Group similar entries using Jaccard token similarity</li>
 *   <li>For groups with {@value #MIN_OCCURRENCES}+ entries, generate a rule</li>
 *   <li>Skip rules whose fingerprint is already in ACECLAW.md</li>
 *   <li>Append new rules to ACECLAW.md under a marked section</li>
 * </ol>
 *
 * <p>Triggered during the maintenance pipeline (alongside consolidation).
 */
public final class CorrectionRulePromoter {

    private static final Logger log = LoggerFactory.getLogger(CorrectionRulePromoter.class);

    /** Minimum number of similar corrections before promoting to a rule. */
    static final int MIN_OCCURRENCES = 2;

    /** Similarity threshold for grouping corrections (looser than dedup's 0.80). */
    static final double SIMILARITY_THRESHOLD = 0.50;

    /** Categories eligible for promotion. */
    private static final Set<MemoryEntry.Category> PROMOTABLE_CATEGORIES = Set.of(
            MemoryEntry.Category.CORRECTION,
            MemoryEntry.Category.MISTAKE
    );

    /** Marker in ACECLAW.md to identify the auto-promoted rules section. */
    static final String SECTION_MARKER = "## Auto-Promoted Rules";

    /** Prefix for fingerprint comments in ACECLAW.md (for dedup across runs). */
    static final String FINGERPRINT_PREFIX = "<!-- rule-fingerprint: ";

    private CorrectionRulePromoter() {}

    /**
     * Scans memory entries for repeated corrections and returns promotable rules.
     *
     * @param entries     all auto-memory entries
     * @param existingFingerprints fingerprints already present in ACECLAW.md
     * @return the promotion result with generated rules
     */
    public static PromotionResult detectRepeatedCorrections(
            List<MemoryEntry> entries,
            Set<String> existingFingerprints) {

        Objects.requireNonNull(entries, "entries");
        Objects.requireNonNull(existingFingerprints, "existingFingerprints");

        // Filter to promotable categories
        var corrections = entries.stream()
                .filter(e -> PROMOTABLE_CATEGORIES.contains(e.category()))
                .toList();

        if (corrections.size() < MIN_OCCURRENCES) {
            return new PromotionResult(List.of(), corrections.size());
        }

        // Group similar corrections
        var groups = groupBySimilarity(corrections);

        // Generate rules for groups that meet the threshold
        var rules = new ArrayList<PromotedRule>();
        for (var group : groups) {
            if (group.size() < MIN_OCCURRENCES) {
                continue;
            }

            String fingerprint = fingerprint(group);
            if (existingFingerprints.contains(fingerprint)) {
                continue;
            }

            var rule = generateRule(group, fingerprint);
            rules.add(rule);
        }

        return new PromotionResult(List.copyOf(rules), corrections.size());
    }

    /**
     * Reads existing rule fingerprints from ACECLAW.md.
     *
     * @param aceClawMdPath path to ACECLAW.md
     * @return set of fingerprints already present
     */
    public static Set<String> loadExistingFingerprints(Path aceClawMdPath) {
        if (!Files.isReadable(aceClawMdPath)) {
            return Set.of();
        }
        try {
            var content = Files.readString(aceClawMdPath);
            return content.lines()
                    .filter(line -> line.startsWith(FINGERPRINT_PREFIX))
                    .map(line -> line.substring(FINGERPRINT_PREFIX.length(),
                            line.indexOf(" -->", FINGERPRINT_PREFIX.length())))
                    .collect(Collectors.toSet());
        } catch (IOException e) {
            log.warn("Failed to read ACECLAW.md for fingerprints: {}", e.getMessage());
            return Set.of();
        }
    }

    /**
     * Appends promoted rules to ACECLAW.md.
     *
     * @param aceClawMdPath path to ACECLAW.md (created if absent)
     * @param rules         the rules to append
     * @return number of rules written
     */
    public static int appendRules(Path aceClawMdPath, List<PromotedRule> rules) throws IOException {
        if (rules.isEmpty()) {
            return 0;
        }

        Files.createDirectories(aceClawMdPath.getParent());

        var sb = new StringBuilder();

        // Check if the section marker already exists
        boolean hasSection = false;
        if (Files.isRegularFile(aceClawMdPath)) {
            var existing = Files.readString(aceClawMdPath);
            hasSection = existing.contains(SECTION_MARKER);
            if (!hasSection) {
                sb.append("\n\n");
            }
        }

        if (!hasSection) {
            sb.append(SECTION_MARKER).append("\n\n");
            sb.append("> Rules below were auto-promoted from repeated corrections. ");
            sb.append("They indicate mistakes made 2+ times across sessions.\n\n");
        }

        for (var rule : rules) {
            sb.append(FINGERPRINT_PREFIX).append(rule.fingerprint()).append(" -->\n");
            sb.append("### ").append(rule.title()).append("\n\n");
            sb.append(rule.ruleText()).append("\n\n");
            sb.append("_Sources: ").append(rule.occurrences())
                    .append(" corrections across sessions. ");
            sb.append("Tags: ").append(String.join(", ", rule.tags())).append("_\n\n");
        }

        Files.writeString(aceClawMdPath, sb.toString(),
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);

        log.info("Appended {} auto-promoted rules to {}", rules.size(), aceClawMdPath);
        return rules.size();
    }

    // -- internal --------------------------------------------------------

    /**
     * Groups corrections by similarity. Uses single-linkage clustering:
     * if any pair in a group has similarity >= threshold, they belong together.
     */
    static List<List<MemoryEntry>> groupBySimilarity(List<MemoryEntry> corrections) {
        var groups = new ArrayList<List<MemoryEntry>>();
        var assigned = new boolean[corrections.size()];

        for (int i = 0; i < corrections.size(); i++) {
            if (assigned[i]) continue;

            var group = new ArrayList<MemoryEntry>();
            group.add(corrections.get(i));
            assigned[i] = true;

            for (int j = i + 1; j < corrections.size(); j++) {
                if (assigned[j]) continue;

                // Check similarity against any member of the group
                for (var member : group) {
                    double sim = MemoryConsolidator.tokenSimilarity(
                            member.content(), corrections.get(j).content());
                    if (sim >= SIMILARITY_THRESHOLD) {
                        group.add(corrections.get(j));
                        assigned[j] = true;
                        break;
                    }
                }
            }

            groups.add(group);
        }

        return groups;
    }

    /**
     * Generates a deterministic fingerprint for a group of corrections.
     * Based on sorted, normalized content tokens shared across all entries.
     */
    static String fingerprint(List<MemoryEntry> group) {
        // Use the intersection of tokens from all entries
        Set<String> commonTokens = null;
        for (var entry : group) {
            var tokens = tokenize(entry.content());
            if (commonTokens == null) {
                commonTokens = new HashSet<>(tokens);
            } else {
                commonTokens.retainAll(tokens);
            }
        }
        if (commonTokens == null || commonTokens.isEmpty()) {
            // Fallback: hash of the newest entry's content
            var newest = group.stream()
                    .max(Comparator.comparing(MemoryEntry::createdAt))
                    .orElse(group.getFirst());
            return Integer.toHexString(newest.content().hashCode());
        }
        var sorted = new ArrayList<>(commonTokens);
        Collections.sort(sorted);
        return Integer.toHexString(sorted.toString().hashCode());
    }

    /**
     * Generates a rule from a group of similar corrections.
     */
    static PromotedRule generateRule(List<MemoryEntry> group, String fingerprint) {
        // Use the newest entry as the primary source
        var newest = group.stream()
                .max(Comparator.comparing(MemoryEntry::createdAt))
                .orElse(group.getFirst());

        // Collect all tags across the group
        var allTags = new LinkedHashSet<String>();
        for (var entry : group) {
            allTags.addAll(entry.tags());
        }

        // Generate title from tags or content
        String title = generateTitle(newest, allTags);

        // Build the rule text
        String ruleText = "**" + newest.content().strip() + "**";

        var sourceIds = group.stream()
                .map(MemoryEntry::id)
                .toList();

        return new PromotedRule(
                title,
                ruleText,
                fingerprint,
                List.copyOf(allTags),
                sourceIds,
                group.size(),
                newest.createdAt()
        );
    }

    private static String generateTitle(MemoryEntry entry, Set<String> tags) {
        // Try to extract a meaningful title from tags
        var meaningfulTags = tags.stream()
                .filter(t -> !t.equals("repeated-mistake") && !t.equals("skill"))
                .limit(3)
                .toList();

        if (!meaningfulTags.isEmpty()) {
            return String.join(" / ", meaningfulTags).toUpperCase();
        }

        // Fallback: first 50 chars of content
        String content = entry.content().strip();
        if (content.length() > 50) {
            return content.substring(0, 47) + "...";
        }
        return content;
    }

    private static Set<String> tokenize(String text) {
        if (text == null || text.isBlank()) return Set.of();
        return Arrays.stream(text.toLowerCase().split("\\W+"))
                .filter(t -> t.length() >= 2)
                .collect(Collectors.toSet());
    }

    /**
     * A rule promoted from repeated corrections.
     */
    public record PromotedRule(
            String title,
            String ruleText,
            String fingerprint,
            List<String> tags,
            List<String> sourceMemoryIds,
            int occurrences,
            Instant newestOccurrence
    ) {
        public PromotedRule {
            Objects.requireNonNull(title, "title");
            Objects.requireNonNull(ruleText, "ruleText");
            Objects.requireNonNull(fingerprint, "fingerprint");
            tags = tags != null ? List.copyOf(tags) : List.of();
            sourceMemoryIds = sourceMemoryIds != null ? List.copyOf(sourceMemoryIds) : List.of();
        }
    }

    /**
     * Result of scanning for promotable corrections.
     */
    public record PromotionResult(
            List<PromotedRule> rules,
            int scannedCorrections
    ) {
        public PromotionResult {
            rules = rules != null ? List.copyOf(rules) : List.of();
        }

        public boolean hasPromotions() {
            return !rules.isEmpty();
        }
    }
}
