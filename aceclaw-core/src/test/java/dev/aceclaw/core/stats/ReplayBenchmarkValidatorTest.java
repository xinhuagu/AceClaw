package dev.aceclaw.core.stats;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ReplayBenchmarkValidatorTest {

    @Test
    void validate_allCategoriesPresent_passes() {
        var categories = List.of(
                "error_recovery", "user_correction", "workflow_reuse", "adversarial");
        var result = ReplayBenchmarkValidator.validate(categories);
        assertThat(result.valid()).isTrue();
    }

    @Test
    void validate_missingCategory_fails() {
        var categories = List.of("error_recovery", "user_correction", "workflow_reuse");
        var result = ReplayBenchmarkValidator.validate(categories);
        assertThat(result.valid()).isFalse();
        assertThat(result.findings()).anyMatch(f -> f.contains("adversarial"));
    }

    @Test
    void validate_insufficientSamples_warns() {
        var categories = List.of(
                "error_recovery", "user_correction", "workflow_reuse", "adversarial");
        var result = ReplayBenchmarkValidator.validate(categories);
        // Each has only 1 case, minimum is 10
        assertThat(result.findings()).anyMatch(f -> f.contains("minimum"));
    }

    @Test
    void validate_sufficientSamples_noWarnings() {
        var categories = new java.util.ArrayList<String>();
        for (int i = 0; i < 10; i++) {
            categories.add("error_recovery");
            categories.add("user_correction");
            categories.add("workflow_reuse");
            categories.add("adversarial");
        }
        var result = ReplayBenchmarkValidator.validate(categories);
        assertThat(result.valid()).isTrue();
        assertThat(result.findings()).isEmpty();
    }

    @Test
    void validate_customCategoriesAllowed() {
        var categories = List.of(
                "error_recovery", "user_correction", "workflow_reuse", "adversarial",
                "custom_pack");
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
        var categories = List.of(
                "Error_Recovery", "USER_CORRECTION", "Workflow_Reuse", "ADVERSARIAL");
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
