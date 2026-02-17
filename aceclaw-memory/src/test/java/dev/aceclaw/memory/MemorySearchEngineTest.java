package dev.aceclaw.memory;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class MemorySearchEngineTest {

    @Test
    void tokenization() {
        var tokens = MemorySearchEngine.tokenize("Use sealed interfaces for type hierarchies!");
        assertThat(tokens).contains("use", "sealed", "interfaces", "for", "type", "hierarchies");
        // Single-char tokens filtered out
        assertThat(tokens).noneMatch(t -> t.length() < 2);
    }

    @Test
    void tokenizationHandlesNullAndBlank() {
        assertThat(MemorySearchEngine.tokenize(null)).isEmpty();
        assertThat(MemorySearchEngine.tokenize("")).isEmpty();
        assertThat(MemorySearchEngine.tokenize("   ")).isEmpty();
    }

    @Test
    void tfidfRelevanceRanking() {
        var now = Instant.now();
        var entries = List.of(
                entry("Use Java records for immutable data", List.of("java", "records"), now),
                entry("Use Gradle Kotlin DSL for builds", List.of("gradle", "kotlin"), now),
                entry("Java sealed interfaces are useful", List.of("java", "patterns"), now)
        );

        var results = MemorySearchEngine.search(entries, "java records", null, 10);
        assertThat(results).isNotEmpty();
        // The entry mentioning both "java" and "records" should rank first
        assertThat(results.getFirst().content()).contains("Java records");
    }

    @Test
    void recencyScoring() {
        var now = Instant.now();
        var oldEntry = entry("Old pattern", List.of("test"), now.minus(30, ChronoUnit.DAYS));
        var recentEntry = entry("Recent pattern", List.of("test"), now.minus(1, ChronoUnit.DAYS));

        // Both entries have "pattern" — recency should boost the recent one
        var results = MemorySearchEngine.search(List.of(oldEntry, recentEntry), "pattern", null, 10);
        assertThat(results).hasSize(2);
        assertThat(results.getFirst().content()).isEqualTo("Recent pattern");
    }

    @Test
    void frequencyBoost() {
        var now = Instant.now();
        var manyTags = entry("Entry with matching tags", List.of("java", "gradle", "build"), now);
        var fewTags = entry("Entry with few tags that also mentions java and gradle and build",
                List.of("misc"), now);

        // Search with terms that match tags of the first entry
        var results = MemorySearchEngine.search(List.of(manyTags, fewTags), "java gradle build", null, 10);
        assertThat(results).isNotEmpty();
        // Entry with matching tags should rank higher due to frequency boost
        assertThat(results.getFirst().content()).contains("matching tags");
    }

    @Test
    void categoryFilter() {
        var now = Instant.now();
        var entries = List.of(
                entry("Java mistake", List.of("java"), now, MemoryEntry.Category.MISTAKE),
                entry("Java pattern", List.of("java"), now, MemoryEntry.Category.PATTERN)
        );

        var mistakes = MemorySearchEngine.search(entries, "java", MemoryEntry.Category.MISTAKE, 10);
        assertThat(mistakes).hasSize(1);
        assertThat(mistakes.getFirst().category()).isEqualTo(MemoryEntry.Category.MISTAKE);
    }

    @Test
    void limitResults() {
        var now = Instant.now();
        var entries = List.of(
                entry("Java one", List.of("java"), now),
                entry("Java two", List.of("java"), now),
                entry("Java three", List.of("java"), now)
        );

        var results = MemorySearchEngine.search(entries, "java", null, 2);
        assertThat(results).hasSize(2);
    }

    @Test
    void emptyQueryReturnsNothing() {
        var entries = List.of(entry("Some content", List.of("tag"), Instant.now()));
        assertThat(MemorySearchEngine.search(entries, "", null, 10)).isEmpty();
        assertThat(MemorySearchEngine.search(entries, null, null, 10)).isEmpty();
    }

    @Test
    void emptyEntriesReturnsNothing() {
        assertThat(MemorySearchEngine.search(List.of(), "query", null, 10)).isEmpty();
    }

    @Test
    void recencyDecayFormula() {
        var now = Instant.now();

        // Fresh entry: score ~1.0
        var fresh = entry("test", List.of(), now);
        double freshScore = MemorySearchEngine.computeRecency(fresh, now);
        assertThat(freshScore).isCloseTo(1.0, org.assertj.core.data.Offset.offset(0.01));

        // 7-day old entry: score ~0.5 (half-life)
        var weekOld = entry("test", List.of(), now.minus(7, ChronoUnit.DAYS));
        double weekScore = MemorySearchEngine.computeRecency(weekOld, now);
        assertThat(weekScore).isCloseTo(0.5, org.assertj.core.data.Offset.offset(0.01));

        // 14-day old entry: score ~0.25
        var twoWeeksOld = entry("test", List.of(), now.minus(14, ChronoUnit.DAYS));
        double twoWeekScore = MemorySearchEngine.computeRecency(twoWeeksOld, now);
        assertThat(twoWeekScore).isCloseTo(0.25, org.assertj.core.data.Offset.offset(0.01));
    }

    @Test
    void frequencyBoostFormula() {
        var queryTokens = Set.of("java", "gradle", "build");

        // Entry with 2 matching tags
        var entry = entry("Some entry", List.of("java", "gradle", "unrelated"), Instant.now());
        double score = MemorySearchEngine.computeFrequency(entry, queryTokens);
        assertThat(score).isCloseTo(Math.log(3.0), org.assertj.core.data.Offset.offset(0.001));

        // Entry with 0 matching tags
        var noMatch = entry("Some entry", List.of("python", "pip"), Instant.now());
        double noScore = MemorySearchEngine.computeFrequency(noMatch, queryTokens);
        assertThat(noScore).isCloseTo(0.0, org.assertj.core.data.Offset.offset(0.001));
    }

    // -- Helpers --------------------------------------------------------

    private static MemoryEntry entry(String content, List<String> tags, Instant createdAt) {
        return entry(content, tags, createdAt, MemoryEntry.Category.PATTERN);
    }

    private static MemoryEntry entry(String content, List<String> tags, Instant createdAt,
                                     MemoryEntry.Category category) {
        return new MemoryEntry(
                java.util.UUID.randomUUID().toString(),
                category, content, tags, createdAt, "test", "fake-hmac");
    }
}
