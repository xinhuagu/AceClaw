package dev.aceclaw.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Field;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ForegroundOutputSinkTest {

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
}
