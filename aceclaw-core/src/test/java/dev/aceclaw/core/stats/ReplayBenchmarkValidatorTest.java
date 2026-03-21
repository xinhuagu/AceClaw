package dev.aceclaw.core.stats;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ReplayBenchmarkValidatorTest {

    /** Helper: repeat each required category n times. */
    private static List<String> repeatCategories(int n) {
        var categories = new java.util.ArrayList<String>();
        for (int i = 0; i < n; i++) {
            categories.add("error_recovery");
            categories.add("user_correction");
            categories.add("workflow_reuse");
            categories.add("adversarial");
        }
        return categories;
    }

    @Test
    void validate_belowStructuralMinimum_fails() {
        // 1 case per category — below structural minimum of 3
        var categories = List.of(
                "error_recovery", "user_correction", "workflow_reuse", "adversarial");
        var result = ReplayBenchmarkValidator.validate(categories);
        assertThat(result.valid()).isFalse();
        assertThat(result.findings()).anyMatch(f -> f.contains("structural minimum"));
    }

    @Test
    void validate_atStructuralMinimum_passes() {
        // 3 cases per category — meets structural minimum
        var result = ReplayBenchmarkValidator.validate(repeatCategories(3));
        assertThat(result.valid()).isTrue();
        // But below significance threshold, so informational findings present
        assertThat(result.findings()).anyMatch(f -> f.contains("significance minimum"));
    }

    @Test
    void validate_atSignificanceThreshold_noFindings() {
        // 10 cases per category — meets significance threshold
        var result = ReplayBenchmarkValidator.validate(repeatCategories(10));
        assertThat(result.valid()).isTrue();
        assertThat(result.findings()).isEmpty();
    }

    @Test
    void validate_missingCategory_fails() {
        var categories = new java.util.ArrayList<String>();
        for (int i = 0; i < 3; i++) {
            categories.add("error_recovery");
            categories.add("user_correction");
            categories.add("workflow_reuse");
        }
        var result = ReplayBenchmarkValidator.validate(categories);
        assertThat(result.valid()).isFalse();
        assertThat(result.findings()).anyMatch(f -> f.contains("adversarial"));
    }

    @Test
    void validate_customCategoriesAllowed() {
        var categories = new java.util.ArrayList<>(repeatCategories(3));
        categories.add("custom_pack");
        var result = ReplayBenchmarkValidator.validate(categories);
        assertThat(result.valid()).isTrue();
        assertThat(result.categoryCounts()).containsKey("custom_pack");
    }

    @Test
    void validate_emptyCases_fails() {
        var result = ReplayBenchmarkValidator.validate(List.of());
        assertThat(result.valid()).isFalse();
        assertThat(result.findings()).hasSize(4); // all 4 required missing
    }

    @Test
    void validate_caseInsensitive() {
        var categories = new java.util.ArrayList<String>();
        for (int i = 0; i < 3; i++) {
            categories.add("Error_Recovery");
            categories.add("USER_CORRECTION");
            categories.add("Workflow_Reuse");
            categories.add("ADVERSARIAL");
        }
        var result = ReplayBenchmarkValidator.validate(categories);
        assertThat(result.valid()).isTrue();
    }

    @Test
    void summarize_producesReadableOutput() {
        var categories = List.of("error_recovery", "workflow_reuse");
        String summary = ReplayBenchmarkValidator.summarize(categories);
        assertThat(summary).contains("FAIL");
        assertThat(summary).contains("Missing");
    }
}
