package dev.aceclaw.core.agent;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ToolFailureAdvisorTest {

    @Test
    void emitsAdviceAfterRepeatedSameCategoryFailures() {
        var advisor = new ToolFailureAdvisor();

        String first = advisor.maybeAdvice("bash", "Traceback: ModuleNotFoundError: No module named 'docx'");
        String second = advisor.maybeAdvice("bash", "ModuleNotFoundError: No module named 'docx'");

        assertThat(first).isNull();
        assertThat(second).contains("Repeated non-progressing failures detected");
        assertThat(second).contains("tool=bash");
    }

    @Test
    void classifiesCapabilityMismatch() {
        var category = ToolFailureAdvisor.classify("unsupported OLE encrypted format, cannot parse");
        assertThat(category).isEqualTo(ToolFailureAdvisor.FailureCategory.CAPABILITY_MISMATCH);
    }

    @Test
    void classifiesModuleAndCommandNotFoundAsDependencyEnv() {
        assertThat(ToolFailureAdvisor.classify("module not found"))
                .isEqualTo(ToolFailureAdvisor.FailureCategory.DEPENDENCY_OR_ENV);
        assertThat(ToolFailureAdvisor.classify("command not found"))
                .isEqualTo(ToolFailureAdvisor.FailureCategory.DEPENDENCY_OR_ENV);
    }

    @Test
    void blocksToolAfterRepeatedNonProgressingFailures() {
        var advisor = new ToolFailureAdvisor();
        String err = "ModuleNotFoundError: No module named 'docx'";

        advisor.onFailure("bash", err);
        advisor.onFailure("bash", err);
        var third = advisor.onFailure("bash", err);

        assertThat(third.blockTool()).isTrue();
        assertThat(advisor.preflightBlockMessage("bash")).contains("circuit-breaker active");
    }

    @Test
    void classifiesNoMatchCategory() {
        assertThat(ToolFailureAdvisor.classify("(exit code: 1 — no match found)"))
                .isEqualTo(ToolFailureAdvisor.FailureCategory.NO_MATCH);
        assertThat(ToolFailureAdvisor.classify("no match found"))
                .isEqualTo(ToolFailureAdvisor.FailureCategory.NO_MATCH);
        assertThat(ToolFailureAdvisor.classify("no lines matched"))
                .isEqualTo(ToolFailureAdvisor.FailureCategory.NO_MATCH);
        assertThat(ToolFailureAdvisor.classify("(exit code: 1 — files differ)"))
                .isEqualTo(ToolFailureAdvisor.FailureCategory.NO_MATCH);
    }

    @Test
    void doesNotBlockNoMatchEvenWhenRepeated() {
        var advisor = new ToolFailureAdvisor();
        String err = "\n\n(exit code: 1 — no match found)";

        advisor.onFailure("bash", err);
        advisor.onFailure("bash", err);
        var third = advisor.onFailure("bash", err);

        assertThat(third.blockTool()).isFalse();
        assertThat(third.category()).isEqualTo(ToolFailureAdvisor.FailureCategory.NO_MATCH);
        assertThat(advisor.preflightBlockMessage("bash")).isNull();
    }

    @Test
    void doesNotBlockTimeoutClassEvenWhenRepeated() {
        var advisor = new ToolFailureAdvisor();
        String err = "Command timed out after 120 seconds";

        advisor.onFailure("bash", err);
        advisor.onFailure("bash", err);
        var third = advisor.onFailure("bash", err);

        assertThat(third.blockTool()).isFalse();
        assertThat(advisor.preflightBlockMessage("bash")).isNull();
    }
}
