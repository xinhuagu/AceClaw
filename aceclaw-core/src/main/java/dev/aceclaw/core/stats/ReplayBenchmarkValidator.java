package dev.aceclaw.core.stats;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Validates that a replay benchmark suite has sufficient category coverage
 * for meaningful A/B comparison.
 *
 * <p>Required benchmark categories (minimum 1 case each):
 * <ul>
 *   <li>{@code error_recovery} — repeated error recovery scenarios</li>
 *   <li>{@code user_correction} — repeated user preference corrections</li>
 *   <li>{@code workflow_reuse} — repeated workflow patterns</li>
 *   <li>{@code adversarial} — noisy/misleading learning signals</li>
 * </ul>
 *
 * <p>For statistical significance, each category should have at least
 * {@link #MIN_CASES_PER_CATEGORY} cases (default 10).
 */
public final class ReplayBenchmarkValidator {

    /**
     * Minimum cases per category for statistical significance in benchmark results.
     * Below this, metrics are reported as INSUFFICIENT_DATA.
     */
    public static final int MIN_CASES_PER_CATEGORY = 10;

    /**
     * Minimum cases per category for suite schema validation (structural coverage).
     * Matches the default in {@code validate-replay-suite.sh} and Gradle's
     * {@code replaySuiteMinPerCategory}. Lower than {@link #MIN_CASES_PER_CATEGORY}
     * because suite validation checks structure, not statistical power.
     */
    public static final int MIN_CASES_FOR_SUITE_VALIDATION = 3;

    /** Required benchmark categories. */
    public static final Set<String> REQUIRED_CATEGORIES = Set.of(
            "error_recovery",
            "user_correction",
            "workflow_reuse",
            "adversarial"
    );

    private ReplayBenchmarkValidator() {}

    /**
     * Validation result with pass/fail and detailed findings.
     *
     * @param valid    whether the benchmark suite passes validation
     * @param findings list of issues found (empty if valid)
     * @param categoryCounts actual count per category
     */
    public record ValidationResult(
            boolean valid,
            List<String> findings,
            Map<String, Integer> categoryCounts
    ) {
        public ValidationResult {
            findings = findings != null ? List.copyOf(findings) : List.of();
            categoryCounts = categoryCounts != null ? Map.copyOf(categoryCounts) : Map.of();
        }
    }

    /**
     * Validates a list of replay case categories against the required benchmark coverage.
     *
     * @param caseCategories list of category strings from each replay case
     * @return validation result
     */
    public static ValidationResult validate(List<String> caseCategories) {
        var findings = new ArrayList<String>();
        var counts = new LinkedHashMap<String, Integer>();

        // Count cases per category
        for (String cat : caseCategories) {
            String normalized = cat != null ? cat.trim().toLowerCase() : "";
            if (!normalized.isEmpty()) {
                counts.merge(normalized, 1, Integer::sum);
            }
        }

        // Check required categories exist
        for (String required : REQUIRED_CATEGORIES) {
            int count = counts.getOrDefault(required, 0);
            if (count == 0) {
                findings.add("Missing required category: " + required);
            } else if (count < MIN_CASES_PER_CATEGORY) {
                findings.add("Category '%s' has %d cases (minimum %d for significance)"
                        .formatted(required, count, MIN_CASES_PER_CATEGORY));
            }
        }

        // Warn about unknown categories
        for (String cat : counts.keySet()) {
            if (!REQUIRED_CATEGORIES.contains(cat)) {
                // Not an error, just informational — custom categories are fine
            }
        }

        boolean valid = findings.stream().noneMatch(f -> f.startsWith("Missing"));
        return new ValidationResult(valid, findings, counts);
    }

    /**
     * Validates and returns a summary string suitable for reporting.
     */
    public static String summarize(List<String> caseCategories) {
        var result = validate(caseCategories);
        var sb = new StringBuilder();
        sb.append("Benchmark validation: ").append(result.valid() ? "PASS" : "FAIL").append('\n');
        sb.append("Categories: ").append(result.categoryCounts()).append('\n');
        if (!result.findings().isEmpty()) {
            sb.append("Findings:\n");
            for (var f : result.findings()) {
                sb.append("  - ").append(f).append('\n');
            }
        }
        return sb.toString();
    }
}
