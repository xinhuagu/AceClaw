package dev.aceclaw.memory;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Similarity helper for candidate merge/dedup decisions.
 */
public final class CandidateSimilarity {

    private CandidateSimilarity() {}

    /**
     * Blended similarity over text and tags with category/tool hard constraints.
     */
    public static double score(LearningCandidate a, LearningCandidate b) {
        if (a.category() != b.category()) return 0.0;
        if (!a.toolTag().equalsIgnoreCase(b.toolTag())) return 0.0;
        double content = jaccard(a.content(), b.content());
        double tags = jaccard(String.join(" ", a.tags()), String.join(" ", b.tags()));
        return (0.7 * content) + (0.3 * tags);
    }

    static double jaccard(String left, String right) {
        if (left == null || right == null || left.isBlank() || right.isBlank()) return 0.0;
        Set<String> leftTokens = tokenize(left);
        Set<String> rightTokens = tokenize(right);
        if (leftTokens.isEmpty() && rightTokens.isEmpty()) return 1.0;
        if (leftTokens.isEmpty() || rightTokens.isEmpty()) return 0.0;
        var intersection = new HashSet<>(leftTokens);
        intersection.retainAll(rightTokens);
        var union = new HashSet<>(leftTokens);
        union.addAll(rightTokens);
        return (double) intersection.size() / union.size();
    }

    private static Set<String> tokenize(String text) {
        var out = new HashSet<String>();
        for (String token : text.toLowerCase(Locale.ROOT).split("\\W+")) {
            if (!token.isBlank()) {
                out.add(token);
            }
        }
        return out;
    }
}
