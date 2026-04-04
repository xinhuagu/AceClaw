package dev.aceclaw.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Field;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ANSI escape codes split tokens in output, so strip them for content assertions.
 */

class ForegroundOutputSinkTest {

    private static String stripAnsi(String text) {
        return text.replaceAll("\u001B\\[[0-9;]*m", "");
    }

    @Test
    void toolLifecycle_rendersStatusWithSummaryAndCompletion() {
        var buffer = new StringWriter();
        var sink = new ForegroundOutputSink(new PrintWriter(buffer), new TerminalMarkdownRenderer());

        sink.onToolUse("toolu_1", "bash", "git diff --stat");
        sink.onToolCompleted("toolu_1", "bash", 820, false, "");

        String output = buffer.toString();
        assertThat(output).contains("[tool:start] bash - git diff --stat");
        assertThat(output).contains("[tool:done] bash (0.8s)");
    }

    @Test
    void subAgentAndPlanStepStatus_areVisible() {
        var mapper = new ObjectMapper();
        var buffer = new StringWriter();
        var sink = new ForegroundOutputSink(new PrintWriter(buffer), new TerminalMarkdownRenderer());

        var subStart = mapper.createObjectNode();
        subStart.put("agentType", "research");
        subStart.put("prompt", "analyzing deps");
        sink.onSubAgentStart(subStart);

        var planStart = mapper.createObjectNode();
        planStart.put("stepId", "step-2");
        planStart.put("stepIndex", 2);
        planStart.put("totalSteps", 4);
        planStart.put("stepName", "Run tests");
        sink.onPlanStepStarted(planStart);

        String output = buffer.toString();
        assertThat(output).contains("Sub-agent");
        assertThat(output).contains("step 2/4");
        assertThat(output).contains("Run tests");
    }

    @Test
    void planCreated_printsBoxedSummaryWithSteps() {
        var mapper = new ObjectMapper();
        var buffer = new StringWriter();
        var sink = new ForegroundOutputSink(new PrintWriter(buffer), new TerminalMarkdownRenderer());

        var params = mapper.createObjectNode();
        params.put("planId", "plan-1");
        params.put("stepCount", 3);
        params.put("goal", "Refactor auth module");
        var steps = mapper.createArrayNode();
        for (int i = 1; i <= 3; i++) {
            var step = mapper.createObjectNode();
            step.put("index", i);
            step.put("name", "Step " + i);
            step.put("description", "Description " + i);
            steps.add(step);
        }
        params.set("steps", steps);

        sink.onPlanCreated(params);

        String output = stripAnsi(buffer.toString());
        assertThat(output).contains("Plan (3 steps)");
        assertThat(output).contains("Refactor auth module");
        assertThat(output).contains("1. Step 1");
        assertThat(output).contains("2. Step 2");
        assertThat(output).contains("3. Step 3");
    }

    @Test
    void planCreated_resumedPlan_showsResumedLabel() {
        var mapper = new ObjectMapper();
        var buffer = new StringWriter();
        var sink = new ForegroundOutputSink(new PrintWriter(buffer), new TerminalMarkdownRenderer());

        var params = mapper.createObjectNode();
        params.put("planId", "plan-2");
        params.put("stepCount", 2);
        params.put("goal", "Continue refactoring");
        params.put("resumed", true);
        params.put("resumedFromStep", 3);
        params.set("steps", mapper.createArrayNode());

        sink.onPlanCreated(params);

        String output = stripAnsi(buffer.toString());
        assertThat(output).contains("resumed from step 3");
        assertThat(output).contains("2 remaining");
    }

    @Test
    void planCreated_nullParams_noOutput() {
        var buffer = new StringWriter();
        var sink = new ForegroundOutputSink(new PrintWriter(buffer), new TerminalMarkdownRenderer());

        sink.onPlanCreated(null);

        assertThat(buffer.toString()).isEmpty();
    }

    @Test
    void planStepCompleted_printsPersistentLine() {
        var mapper = new ObjectMapper();
        var buffer = new StringWriter();
        var sink = new ForegroundOutputSink(new PrintWriter(buffer), new TerminalMarkdownRenderer());

        var params = mapper.createObjectNode();
        params.put("stepId", "s1");
        params.put("stepIndex", 1);
        params.put("stepName", "Read files");
        params.put("success", true);
        params.put("durationMs", 2300);

        sink.onPlanStepCompleted(params);

        String output = stripAnsi(buffer.toString());
        assertThat(output).contains("step 1");
        assertThat(output).contains("done");
        assertThat(output).contains("2.3s");
        assertThat(output).contains("Read files");
    }

    @Test
    void planStepCompleted_failure_printsFailedLine() {
        var mapper = new ObjectMapper();
        var buffer = new StringWriter();
        var sink = new ForegroundOutputSink(new PrintWriter(buffer), new TerminalMarkdownRenderer());

        var params = mapper.createObjectNode();
        params.put("stepId", "s2");
        params.put("stepIndex", 2);
        params.put("stepName", "Write output");
        params.put("success", false);
        params.put("durationMs", 500);

        sink.onPlanStepCompleted(params);

        String output = stripAnsi(buffer.toString());
        assertThat(output).contains("step 2");
        assertThat(output).contains("failed");
    }

    @Test
    void planCompleted_success_printsCompleteLine() {
        var mapper = new ObjectMapper();
        var buffer = new StringWriter();
        var sink = new ForegroundOutputSink(new PrintWriter(buffer), new TerminalMarkdownRenderer());

        var params = mapper.createObjectNode();
        params.put("success", true);

        sink.onPlanCompleted(params);

        assertThat(stripAnsi(buffer.toString())).contains("[plan] complete");
    }

    @Test
    void planCompleted_failure_printsFailedLine() {
        var mapper = new ObjectMapper();
        var buffer = new StringWriter();
        var sink = new ForegroundOutputSink(new PrintWriter(buffer), new TerminalMarkdownRenderer());

        var params = mapper.createObjectNode();
        params.put("success", false);

        sink.onPlanCompleted(params);

        assertThat(stripAnsi(buffer.toString())).contains("[plan] failed");
    }

    @Test
    void planCompleted_nullParams_noOutput() {
        var buffer = new StringWriter();
        var sink = new ForegroundOutputSink(new PrintWriter(buffer), new TerminalMarkdownRenderer());

        sink.onPlanCompleted(null);

        assertThat(buffer.toString()).isEmpty();
    }

    @Test
    void planCreated_truncatesLongStepNames() {
        var mapper = new ObjectMapper();
        var buffer = new StringWriter();
        var sink = new ForegroundOutputSink(new PrintWriter(buffer), new TerminalMarkdownRenderer());

        var params = mapper.createObjectNode();
        params.put("planId", "plan-3");
        params.put("stepCount", 1);
        params.put("goal", "test");
        var steps = mapper.createArrayNode();
        var step = mapper.createObjectNode();
        step.put("index", 1);
        step.put("name", "A".repeat(200));
        steps.add(step);
        params.set("steps", steps);

        sink.onPlanCreated(params);

        String output = stripAnsi(buffer.toString());
        assertThat(output).contains("...");
        // Should be truncated, not contain the full 200-char name
        assertThat(output).doesNotContain("A".repeat(200));
    }

    @Test
    void repeatedSameTool_traceModeDoesNotCreatePanelEntries() throws Exception {
        var buffer = new StringWriter();
        var sink = new ForegroundOutputSink(new PrintWriter(buffer), new TerminalMarkdownRenderer());

        sink.onToolUse("toolu_1", "web_search", "q1");
        sink.onToolCompleted("toolu_1", "web_search", 900, false, "");
        sink.onToolUse("toolu_2", "web_search", "q2");
        sink.onToolCompleted("toolu_2", "web_search", 1000, false, "");

        Field rendererField = ForegroundOutputSink.class.getDeclaredField("statusRenderer");
        rendererField.setAccessible(true);
        Object renderer = rendererField.get(sink);
        Field entriesField = renderer.getClass().getDeclaredField("entries");
        entriesField.setAccessible(true);

        @SuppressWarnings("unchecked")
        Map<String, Object> entries = (Map<String, Object>) entriesField.get(renderer);
        assertThat(entries).isEmpty();
    }

    @Test
    void compactionEvent_updatesContextMonitor() {
        var buffer = new StringWriter();
        var monitor = new ContextMonitor(200_000);
        var sink = new ForegroundOutputSink(new PrintWriter(buffer), new TerminalMarkdownRenderer(),
                null, monitor, null);

        ObjectNode params = new ObjectMapper().createObjectNode();
        params.put("originalTokens", 150_000);
        params.put("compactedTokens", 55_000);
        params.put("phase", "SUMMARIZED");

        sink.onCompaction(params);

        assertThat(monitor.currentContextTokens()).isEqualTo(55_000);
        assertThat(monitor.compactionCount()).isEqualTo(1);
        assertThat(monitor.lastCompactionPhase()).isEqualTo("SUMMARIZED");
    }
}
