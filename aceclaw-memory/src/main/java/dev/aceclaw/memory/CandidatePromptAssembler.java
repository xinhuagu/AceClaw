package dev.aceclaw.memory;

import dev.aceclaw.core.agent.ContextEstimator;

import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Assembles a markdown section of promoted learning candidates for injection
 * into the system prompt. Only PROMOTED candidates are included.
 *
 * <p>Candidates are grouped by tool tag, ordered by score DESC, evidence DESC,
 * recency DESC, and capped by count and character budget.
 */
public final class CandidatePromptAssembler {

    private static final double DEFAULT_TOKEN_HEADROOM_FACTOR = 0.85;
    private static final String TOKEN_HEADROOM_FACTOR_PROPERTY =
            "aceclaw.candidate.injection.tokenHeadroomFactor";
    private static final Pattern TOKEN_SPLIT = Pattern.compile("[^a-z0-9]+");

    private CandidatePromptAssembler() {}

    /**
     * Assembles the promoted candidate section for system prompt injection.
     *
     * @param store  the candidate store to query
     * @param config injection configuration
     * @return markdown section, or empty string if no promoted candidates or injection disabled
     */
    public static String assemble(CandidateStore store, Config config) {
        return assembleWithMetadata(store, config).section();
    }

    /**
     * Assembles the promoted candidate section and returns metadata of injected candidate IDs.
     */
    public static AssemblyResult assembleWithMetadata(CandidateStore store, Config config) {
        return assembleWithMetadata(store, config, null, List.of());
    }

    /**
     * Assembles the promoted candidate section and returns metadata of injected candidate IDs.
     * Relevance is biased toward the current task query and active file set when provided.
     */
    public static AssemblyResult assembleWithMetadata(CandidateStore store, Config config,
                                                      String queryHint, List<String> activePaths) {
        Objects.requireNonNull(store, "store");
        Objects.requireNonNull(config, "config");

        if (!config.enabled) {
            return new AssemblyResult("", List.of());
        }

        var promoted = store.byState(CandidateState.PROMOTED);
        if (promoted.isEmpty()) {
            return new AssemblyResult("", List.of());
        }

        // Filter by category allowlist
        var filtered = promoted;
        if (config.allowedCategories != null && !config.allowedCategories.isEmpty()) {
            filtered = promoted.stream()
                    .filter(c -> config.allowedCategories.contains(c.category()))
                    .toList();
        }
        if (filtered.isEmpty()) {
            return new AssemblyResult("", List.of());
        }

        var queryTokens = tokenize(queryHint);
        var pathTokens = tokenize(String.join(" ", activePaths == null ? List.of() : activePaths));

        // Sort by task relevance DESC, then score DESC, then evidenceCount DESC, then lastSeenAt DESC
        var sorted = filtered.stream()
                .sorted(Comparator.<LearningCandidate>comparingDouble(
                                candidate -> relevanceScore(candidate, queryTokens, pathTokens))
                        .reversed()
                        .thenComparing(Comparator.comparingDouble(LearningCandidate::score).reversed())
                        .thenComparing(Comparator.comparingInt(LearningCandidate::evidenceCount).reversed())
                        .thenComparing(Comparator.comparing(LearningCandidate::lastSeenAt).reversed()))
                .toList();

        // Apply count cap
        var capped = sorted.size() > config.maxCount
                ? sorted.subList(0, config.maxCount) : sorted;

        // Group by tool tag (preserving order)
        var grouped = new LinkedHashMap<String, java.util.List<LearningCandidate>>();
        for (var c : capped) {
            grouped.computeIfAbsent(c.toolTag(), _ -> new java.util.ArrayList<>()).add(c);
        }

        // Build markdown section with char budget enforcement
        var sb = new StringBuilder();
        var injectedIds = new java.util.ArrayList<String>();
        var header = "\n## Learned Strategies (Promoted Candidates)\n\n";
        sb.append(header);
        int effectiveTokenBudget = effectiveTokenBudget(config.maxTokens);
        int tokenBudget = Math.max(0, effectiveTokenBudget - ContextEstimator.estimateTokens(header));

        for (var entry : grouped.entrySet()) {
            var toolSection = new StringBuilder();
            toolSection.append("### ").append(entry.getKey()).append("\n");
            for (var candidate : entry.getValue()) {
                var line = String.format("- %s (confidence: %.2f, evidence: %d)\n",
                        candidate.content(), candidate.score(), candidate.evidenceCount());
                toolSection.append(line);
            }

            int toolTokens = ContextEstimator.estimateTokens(toolSection.toString());
            if (toolTokens <= tokenBudget) {
                sb.append(toolSection);
                tokenBudget -= toolTokens;
                for (var candidate : entry.getValue()) {
                    injectedIds.add(candidate.id());
                }
            } else {
                break;
            }
        }

        // If nothing fit within the budget after the header, return empty
        String result = sb.toString();
        if (result.equals("\n## Learned Strategies (Promoted Candidates)\n\n")) {
            return new AssemblyResult("", List.of());
        }

        return new AssemblyResult(result, List.copyOf(injectedIds));
    }

    private static double relevanceScore(LearningCandidate candidate,
                                         Set<String> queryTokens,
                                         Set<String> pathTokens) {
        if (queryTokens.isEmpty() && pathTokens.isEmpty()) {
            return 0.0;
        }
        var candidateTokens = new HashSet<String>();
        candidateTokens.addAll(tokenize(candidate.content()));
        candidateTokens.addAll(tokenize(candidate.toolTag()));
        for (var tag : candidate.tags()) {
            candidateTokens.addAll(tokenize(tag));
        }

        double score = 0.0;
        score += overlapScore(candidateTokens, queryTokens, 1.0);
        score += overlapScore(candidateTokens, pathTokens, 0.6);
        if (!queryTokens.isEmpty() && queryTokens.contains(candidate.toolTag().toLowerCase())) {
            score += 1.5;
        }
        return score;
    }

    private static double overlapScore(Set<String> candidateTokens,
                                       Set<String> hintTokens,
                                       double weight) {
        if (candidateTokens.isEmpty() || hintTokens.isEmpty()) {
            return 0.0;
        }
        int matches = 0;
        for (var token : hintTokens) {
            if (candidateTokens.contains(token)) {
                matches++;
            }
        }
        return matches * weight;
    }

    private static Set<String> tokenize(String text) {
        if (text == null || text.isBlank()) {
            return Set.of();
        }
        var tokens = new HashSet<String>();
        for (var raw : TOKEN_SPLIT.split(text.toLowerCase())) {
            if (raw.length() >= 3) {
                tokens.add(raw);
            }
        }
        return Set.copyOf(tokens);
    }

    static int effectiveTokenBudget(int maxTokens) {
        if (maxTokens <= 0) {
            return 0;
        }
        double factor = DEFAULT_TOKEN_HEADROOM_FACTOR;
        String configured = System.getProperty(TOKEN_HEADROOM_FACTOR_PROPERTY);
        if (configured != null && !configured.isBlank()) {
            try {
                factor = Double.parseDouble(configured);
            } catch (NumberFormatException ignored) {
                factor = DEFAULT_TOKEN_HEADROOM_FACTOR;
            }
        }
        if (factor <= 0.0 || factor > 1.0) {
            factor = DEFAULT_TOKEN_HEADROOM_FACTOR;
        }
        return Math.max(1, (int) Math.floor(maxTokens * factor));
    }

    /**
     * Result of prompt assembly with associated injected candidate IDs.
     */
    public record AssemblyResult(String section, List<String> candidateIds) {
        public AssemblyResult {
            Objects.requireNonNull(section, "section");
            candidateIds = candidateIds != null ? List.copyOf(candidateIds) : List.of();
        }
    }

    /**
     * Configuration for candidate prompt assembly.
     */
    public record Config(
            boolean enabled,
            int maxCount,
            int maxTokens,
            Set<MemoryEntry.Category> allowedCategories
    ) {
        public Config {
            if (maxCount < 0) {
                throw new IllegalArgumentException("maxCount must be non-negative");
            }
            if (maxTokens < 0) {
                throw new IllegalArgumentException("maxTokens must be non-negative");
            }
            allowedCategories = allowedCategories != null ? Set.copyOf(allowedCategories) : Set.of();
        }

        public static Config defaults() {
            return new Config(true, 10, 1200, Set.of());
        }

        public static Config disabled() {
            return new Config(false, 0, 0, Set.of());
        }
    }
}
