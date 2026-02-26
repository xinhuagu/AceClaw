package dev.aceclaw.daemon;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class AntiPatternPreExecutionGateTest {

    @TempDir
    Path tempDir;

    @Test
    void blocksWhenMatchingBlockRule() {
        var gate = new AntiPatternPreExecutionGate(() -> List.of(
                new AntiPatternPreExecutionGate.Rule(
                        "r1", "bash", "Avoid python-docx for encrypted OLE docs",
                        "src", "Use AppleScript path", AntiPatternPreExecutionGate.Action.BLOCK,
                        Set.of("python", "docx", "encrypted", "ole"), Set.of())));

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
                        Set.of("retries", "constraint", "checks"), Set.of())));

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
                        Set.of("flow"), Set.of())));

        var decision = gate.evaluate(
                "bash",
                "{\"command\":\"echo hello\"}",
                "Execute: echo hello");

        assertThat(decision.action()).isEqualTo(AntiPatternPreExecutionGate.Action.ALLOW);
    }

    @Test
    void structuredFailureTypeMatchCanTriggerPenalize() {
        var gate = new AntiPatternPreExecutionGate(() -> List.of(
                new AntiPatternPreExecutionGate.Rule(
                        "r4", "bash", "Avoid encrypted OLE with python-docx",
                        "src", "use applescript", AntiPatternPreExecutionGate.Action.PENALIZE,
                        Set.of(), Set.of(dev.aceclaw.memory.FailureType.CAPABILITY_MISMATCH))));

        var decision = gate.evaluate(
                "bash",
                "{\"command\":\"python3 parse_doc.py\"}",
                "Execute: parse encrypted OLE/IRM document");

        assertThat(decision.action()).isEqualTo(AntiPatternPreExecutionGate.Action.PENALIZE);
        assertThat(decision.ruleId()).isEqualTo("r4");
    }

    @Test
    void downgradeBlockWhenFalsePositiveRateTooHigh() {
        var store = new AntiPatternGateFeedbackStore(tempDir);
        var gate = new AntiPatternPreExecutionGate(
                () -> List.of(new AntiPatternPreExecutionGate.Rule(
                        "candidate:c1", "bash", "Avoid path",
                        "src", "fallback", AntiPatternPreExecutionGate.Action.BLOCK,
                        Set.of("ole"), Set.of())),
                store);

        store.recordBlocked("candidate:c1");
        store.recordBlocked("candidate:c1");
        store.recordBlocked("candidate:c1");
        store.recordFalsePositive("candidate:c1");
        store.recordFalsePositive("candidate:c1");

        var decision = gate.evaluate("bash", "{\"command\":\"run\"}", "encrypted ole doc");
        assertThat(decision.action()).isEqualTo(AntiPatternPreExecutionGate.Action.PENALIZE);
    }
}
