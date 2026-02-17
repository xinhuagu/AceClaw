package dev.aceclaw.memory;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Hybrid memory search engine combining TF-IDF relevance, recency decay,
 * and frequency boost scoring.
 *
 * <p>Final score = {@code 0.5 * tfidf + 0.35 * recency + 0.15 * frequency}
 *
 * <p>TF-IDF: Pure Java implementation with simple whitespace tokenization.
 * Recency: Exponential decay with configurable half-life (default 7 days).
 * Frequency: {@code log(1 + matchingTagCount)} boost for entries whose tags
 * overlap with query tokens.
 */
public final class MemorySearchEngine {

    private static final double WEIGHT_TFIDF = 0.50;
    private static final double WEIGHT_RECENCY = 0.35;
    private static final double WEIGHT_FREQUENCY = 0.15;

    /** Half-life for recency decay in days. */
    private static final double RECENCY_HALF_LIFE_DAYS = 7.0;

    private MemorySearchEngine() {}

    /**
     * Searches the given entries using hybrid ranking.
     *
     * @param entries  all available memory entries
     * @param query    the search query (natural language)
     * @param category optional category filter (null = all)
     * @param limit    maximum results (0 = unlimited)
     * @return ranked entries, highest score first
     */
    public static List<MemoryEntry> search(List<MemoryEntry> entries, String query,
                                           MemoryEntry.Category category, int limit) {
        if (entries.isEmpty() || query == null || query.isBlank()) {
            return List.of();
        }

        // Apply category filter first
        var filtered = category != null
                ? entries.stream().filter(e -> e.category() == category).toList()
                : entries;

        if (filtered.isEmpty()) return List.of();

        var queryTokens = tokenize(query);
        if (queryTokens.isEmpty()) return List.of();

        // Build document frequency map across all filtered entries
        var docFrequency = buildDocFrequency(filtered);
        int totalDocs = filtered.size();
        Instant now = Instant.now();

        // Score each entry
        var scored = filtered.stream()
                .map(entry -> new ScoredEntry(entry, computeScore(
                        entry, queryTokens, docFrequency, totalDocs, now)))
                .filter(se -> se.score > 0.0)
                .sorted(Comparator.comparingDouble(ScoredEntry::score).reversed())
                .map(ScoredEntry::entry);

        if (limit > 0) {
            scored = scored.limit(limit);
        }

        return scored.toList();
    }

    // -- Scoring --------------------------------------------------------

    private static double computeScore(MemoryEntry entry, Set<String> queryTokens,
                                       Map<String, Integer> docFrequency, int totalDocs,
                                       Instant now) {
        double tfidf = computeTfIdf(entry, queryTokens, docFrequency, totalDocs);
        double recency = computeRecency(entry, now);
        double frequency = computeFrequency(entry, queryTokens);

        return WEIGHT_TFIDF * tfidf + WEIGHT_RECENCY * recency + WEIGHT_FREQUENCY * frequency;
    }

    /**
     * Computes TF-IDF score for a single entry against query tokens.
     */
    static double computeTfIdf(MemoryEntry entry, Set<String> queryTokens,
                               Map<String, Integer> docFrequency, int totalDocs) {
        var entryTokens = tokenize(entry.content());
        if (entryTokens.isEmpty()) return 0.0;

        // Term frequency: count of each token in this entry
        var tf = new HashMap<String, Integer>();
        for (var token : tokenize(entry.content() + " " + String.join(" ", entry.tags()))) {
            tf.merge(token, 1, Integer::sum);
        }

        // Sum TF-IDF for each query token present in the entry
        double score = 0.0;
        for (var queryToken : queryTokens) {
            int termFreq = tf.getOrDefault(queryToken, 0);
            if (termFreq == 0) continue;

            int docFreq = docFrequency.getOrDefault(queryToken, 0);
            if (docFreq == 0) continue;

            // TF: log(1 + tf)
            double tfScore = Math.log(1.0 + termFreq);
            // IDF: log(totalDocs / docFreq)
            double idfScore = Math.log((double) totalDocs / docFreq);
            score += tfScore * idfScore;
        }

        // Normalize by number of query tokens to keep score in [0, ~1] range
        return score / queryTokens.size();
    }

    /**
     * Computes recency score using exponential decay.
     * Score = 2^(-age_days / half_life)
     */
    static double computeRecency(MemoryEntry entry, Instant now) {
        if (entry.createdAt() == null) return 0.0;
        double ageDays = Duration.between(entry.createdAt(), now).toHours() / 24.0;
        if (ageDays < 0) ageDays = 0; // future entries treated as fresh
        return Math.pow(2.0, -ageDays / RECENCY_HALF_LIFE_DAYS);
    }

    /**
     * Computes frequency boost based on tag overlap with query tokens.
     * Score = log(1 + matchingTagCount)
     */
    static double computeFrequency(MemoryEntry entry, Set<String> queryTokens) {
        long matchingTags = entry.tags().stream()
                .map(String::toLowerCase)
                .filter(queryTokens::contains)
                .count();
        return Math.log(1.0 + matchingTags);
    }

    // -- Tokenization ---------------------------------------------------

    /**
     * Simple whitespace + punctuation tokenizer. Returns lowercase tokens.
     */
    static Set<String> tokenize(String text) {
        if (text == null || text.isBlank()) return Set.of();
        return Arrays.stream(text.toLowerCase().split("[\\s\\p{Punct}]+"))
                .filter(t -> t.length() >= 2)
                .collect(Collectors.toSet());
    }

    /**
     * Builds a document frequency map: for each token, how many entries contain it.
     */
    private static Map<String, Integer> buildDocFrequency(List<MemoryEntry> entries) {
        var docFreq = new HashMap<String, Integer>();
        for (var entry : entries) {
            var tokens = tokenize(entry.content() + " " + String.join(" ", entry.tags()));
            for (var token : tokens) {
                docFreq.merge(token, 1, Integer::sum);
            }
        }
        return docFreq;
    }

    // -- Internal -------------------------------------------------------

    private record ScoredEntry(MemoryEntry entry, double score) {}
}
