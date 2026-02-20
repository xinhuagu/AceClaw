package dev.aceclaw.memory;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RecoveryRecipeTest {

    private static Insight.RecoveryStep step(String desc, String tool, String hint) {
        return new Insight.RecoveryStep(desc, tool, hint);
    }

    @Test
    void constructionWithValidFields() {
        var steps = List.of(
                step("check path exists", "glob", "*.java"),
                step("read file content", "read_file", null)
        );
        var recipe = new Insight.RecoveryRecipe("file not found", steps, "read_file", 0.85);

        assertThat(recipe.triggerPattern()).isEqualTo("file not found");
        assertThat(recipe.steps()).hasSize(2);
        assertThat(recipe.toolName()).isEqualTo("read_file");
        assertThat(recipe.confidence()).isEqualTo(0.85);
    }

    @Test
    void descriptionFormat() {
        var steps = List.of(
                step("check path", "glob", null),
                step("retry read", "read_file", null)
        );
        var recipe = new Insight.RecoveryRecipe("file not found", steps, "read_file", 0.8);

        assertThat(recipe.description())
                .isEqualTo("Recovery recipe for 'file not found': check path -> retry read");
    }

    @Test
    void tagsIncludeToolNameAndStepTools() {
        var steps = List.of(
                step("search", "glob", null),
                step("read", "read_file", null),
                step("fix", "edit_file", null)
        );
        var recipe = new Insight.RecoveryRecipe("error pattern", steps, "bash", 0.9);

        assertThat(recipe.tags())
                .containsExactly("bash", "recovery-recipe", "glob", "read_file", "edit_file");
    }

    @Test
    void tagsDeduplicateStepToolMatchingPrimaryTool() {
        var steps = List.of(
                step("retry", "bash", "--verbose"),
                step("verify", "read_file", null)
        );
        var recipe = new Insight.RecoveryRecipe("command failed", steps, "bash", 0.7);

        // "bash" appears as primary tool and step tool; should only appear once
        assertThat(recipe.tags())
                .containsExactly("bash", "recovery-recipe", "read_file");
    }

    @Test
    void targetCategoryIsRecoveryRecipe() {
        var recipe = new Insight.RecoveryRecipe("err", List.of(step("fix", "bash", null)), "bash", 0.5);

        assertThat(recipe.targetCategory()).isEqualTo(MemoryEntry.Category.RECOVERY_RECIPE);
    }

    @Test
    void confidenceBoundsValidation() {
        assertThatThrownBy(() -> new Insight.RecoveryRecipe(
                "err", List.of(step("fix", "bash", null)), "bash", -0.1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("confidence");

        assertThatThrownBy(() -> new Insight.RecoveryRecipe(
                "err", List.of(step("fix", "bash", null)), "bash", 1.1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("confidence");

        // Boundary values should be accepted
        var zero = new Insight.RecoveryRecipe("err", List.of(step("fix", "bash", null)), "bash", 0.0);
        assertThat(zero.confidence()).isEqualTo(0.0);

        var one = new Insight.RecoveryRecipe("err", List.of(step("fix", "bash", null)), "bash", 1.0);
        assertThat(one.confidence()).isEqualTo(1.0);
    }

    @Test
    void stepsDefensiveCopy() {
        var mutable = new ArrayList<>(List.of(
                step("step1", "bash", null),
                step("step2", "read_file", null)
        ));
        var recipe = new Insight.RecoveryRecipe("err", mutable, "bash", 0.8);

        mutable.add(step("step3", "write_file", null));
        assertThat(recipe.steps()).hasSize(2);

        assertThatThrownBy(() -> recipe.steps().add(step("step4", "edit_file", null)))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void emptyStepsRejected() {
        assertThatThrownBy(() -> new Insight.RecoveryRecipe("err", List.of(), "bash", 0.5))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("steps");
    }

    @Test
    void nullFieldsRejected() {
        var steps = List.of(step("fix", "bash", null));

        assertThatThrownBy(() -> new Insight.RecoveryRecipe(null, steps, "bash", 0.5))
                .isInstanceOf(NullPointerException.class);

        assertThatThrownBy(() -> new Insight.RecoveryRecipe("err", null, "bash", 0.5))
                .isInstanceOf(NullPointerException.class);

        assertThatThrownBy(() -> new Insight.RecoveryRecipe("err", steps, null, 0.5))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void recoveryStepNullDescriptionRejected() {
        assertThatThrownBy(() -> new Insight.RecoveryStep(null, "bash", null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void recoveryStepNullToolNameAllowed() {
        var step = new Insight.RecoveryStep("manual check", null, null);
        assertThat(step.description()).isEqualTo("manual check");
        assertThat(step.toolName()).isNull();
        assertThat(step.parameterHint()).isNull();
    }
}
