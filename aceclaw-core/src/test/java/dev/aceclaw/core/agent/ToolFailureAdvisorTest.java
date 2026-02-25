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
}

