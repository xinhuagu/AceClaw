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
 * <p>Two-layer threshold model:
 * <ul>
 *   <li><b>Structural minimum ({@link #MIN_CASES_FOR_SUITE_VALIDATION} = 3)</b>:
 *       "can run" — the suite has enough cases per category to be structurally valid.
 *       This is the pass/fail gate for suite validation.</li>
 *   <li><b>Statistical significance ({@link #MIN_CASES_PER_CATEGORY} = 10)</b>:
 *       "can trust" — the suite has enough cases for benchmark verdicts to be meaningful.
 *       Below this, {@link BenchmarkScorecard} reports {@code INSUFFICIENT_DATA}
 *       but the suite still passes structural validation.</li>
 * </ul>
 *
 * <p>Required benchmark categories:
 * {@code error_recovery}, {@code user_correction}, {@code workflow_reuse}, {@code adversarial}.
 */
public final class ReplayBenchmarkValidator {

    /**
     * Structural minimum: cases per category for suite validation ("can run").
     * Matches {@code validate-replay-suite.sh}, Gradle {@code replaySuiteMinPerCategory},
     * and CI default. Below this, the suite fails validation.
     */
    public static final int MIN_CASES_FOR_SUITE_VALIDATION = 3;

    /**
     * Statistical significance: cases per category for trustworthy verdicts ("can trust").
     * Below this, {@link BenchmarkScorecard} reports {@code INSUFFICIENT_DATA}.
     * This is an interpretation rule, not a suite pass/fail gate.
     */
    public static final int MIN_CASES_PER_CATEGORY = 10;

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

        // Check required categories against structural minimum (pass/fail gate)
        for (String required : REQUIRED_CATEGORIES) {
            int count = counts.getOrDefault(required, 0);
            if (count == 0) {
                findings.add("Missing required category: " + required);
            } else if (count < MIN_CASES_FOR_SUITE_VALIDATION) {
                findings.add("Category '%s' has %d cases (structural minimum %d)"
                        .formatted(required, count, MIN_CASES_FOR_SUITE_VALIDATION));
            } else if (count < MIN_CASES_PER_CATEGORY) {
                // Informational: suite is valid but verdicts will be INSUFFICIENT_DATA
                findings.add("Category '%s' has %d cases (significance minimum %d — verdicts will be INSUFFICIENT_DATA)"
                        .formatted(required, count, MIN_CASES_PER_CATEGORY));
            }
        }

        // Suite passes if all categories meet the structural minimum
        boolean valid = findings.stream()
                .noneMatch(f -> f.startsWith("Missing") || f.contains("structural minimum"));
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
