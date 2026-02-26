package dev.aceclaw.daemon;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class AntiPatternPreExecutionGateTest {

    @Test
    void blocksWhenMatchingBlockRule() {
        var gate = new AntiPatternPreExecutionGate(() -> List.of(
                new AntiPatternPreExecutionGate.Rule(
                        "r1", "bash", "Avoid python-docx for encrypted OLE docs",
                        "src", "Use AppleScript path", AntiPatternPreExecutionGate.Action.BLOCK,
                        Set.of("python", "docx", "encrypted", "ole"))));

        var decision = gate.evaluate(
                "bash",
                "{\"command\":\"python3 script.py\"}",
                "Execute: python-docx on encrypted OLE document");

        assertThat(decision.action()).isEqualTo(AntiPatternPreExecutionGate.Action.BLOCK);
        assertThat(decision.ruleId()).isEqualTo("r1");
    }

    @Test
    void penalizesWhenMatchingPenalizeRule() {
        var gate = new AntiPatternPreExecutionGate(() -> List.of(
                new AntiPatternPreExecutionGate.Rule(
                        "r2", "bash", "Avoid broad retries without constraint checks",
                        "src", "Inspect constraints first", AntiPatternPreExecutionGate.Action.PENALIZE,
                        Set.of("retries", "constraint", "checks"))));

        var decision = gate.evaluate(
                "bash",
                "{\"command\":\"retry same command\"}",
                "retry broad retries without constraint checks");

        assertThat(decision.action()).isEqualTo(AntiPatternPreExecutionGate.Action.PENALIZE);
        assertThat(decision.ruleId()).isEqualTo("r2");
    }

    @Test
    void allowsWhenToolDoesNotMatchRuleScope() {
        var gate = new AntiPatternPreExecutionGate(() -> List.of(
                new AntiPatternPreExecutionGate.Rule(
                        "r3", "applescript", "Avoid this flow",
                        "src", "fallback", AntiPatternPreExecutionGate.Action.BLOCK,
                        Set.of("flow"))));

        var decision = gate.evaluate(
                "bash",
                "{\"command\":\"echo hello\"}",
                "Execute: echo hello");

        assertThat(decision.action()).isEqualTo(AntiPatternPreExecutionGate.Action.ALLOW);
    }
}

