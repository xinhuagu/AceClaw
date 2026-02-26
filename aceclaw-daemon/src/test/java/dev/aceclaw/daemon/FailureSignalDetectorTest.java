package dev.aceclaw.daemon;

import dev.aceclaw.core.agent.Turn;
import dev.aceclaw.core.llm.ContentBlock;
import dev.aceclaw.core.llm.Message;
import dev.aceclaw.core.llm.StopReason;
import dev.aceclaw.core.llm.Usage;
import dev.aceclaw.memory.FailureType;
import dev.aceclaw.memory.Insight;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FailureSignalDetectorTest {

    private final FailureSignalDetector detector = new FailureSignalDetector();

    @Test
    void mapsPermissionDenied() {
        var turn = turnWithResult("t1", "bash", "Permission denied by user", true);

        var insights = detector.analyze(turn);

        assertThat(insights).hasSize(1);
        var insight = insights.getFirst();
        assertThat(insight.type()).isEqualTo(FailureType.PERMISSION_DENIED);
        assertThat(insight.source()).isEqualTo("permission-gate");
        assertThat(insight.toolOrAgent()).isEqualTo("bash");
        assertThat(insight.retryable()).isFalse();
    }

    @Test
    void mapsPermissionPendingTimeout() {
        var turn = turnWithResult("t1", "bash",
                "Permission pending timeout: no response from client within 120s", true);

        var insights = detector.analyze(turn);

        assertThat(insights).hasSize(1);
        assertThat(insights.getFirst().type()).isEqualTo(FailureType.PERMISSION_PENDING_TIMEOUT);
    }

    @Test
    void mapsTimeout() {
        var turn = turnWithResult("t1", "bash",
                "Command timed out after 120 seconds", true);

        var insights = detector.analyze(turn);

        assertThat(insights).hasSize(1);
        assertThat(insights.getFirst().type()).isEqualTo(FailureType.TIMEOUT);
        assertThat(insights.getFirst().retryable()).isTrue();
    }

    @Test
    void mapsDependencyMissing() {
        var turn = turnWithResult("t1", "bash",
                "Traceback: ModuleNotFoundError: No module named 'docx'", true);

        var insights = detector.analyze(turn);

        assertThat(insights).hasSize(1);
        assertThat(insights.getFirst().type()).isEqualTo(FailureType.DEPENDENCY_MISSING);
        assertThat(insights.getFirst().retryable()).isTrue();
    }

    @Test
    void mapsCapabilityMismatch() {
        var turn = turnWithResult("t1", "bash",
                "File is encrypted / unsupported OLE format and cannot parse", true);

        var insights = detector.analyze(turn);

        assertThat(insights).hasSize(1);
        assertThat(insights.getFirst().type()).isEqualTo(FailureType.CAPABILITY_MISMATCH);
        assertThat(insights.getFirst().retryable()).isTrue();
    }

    @Test
    void doesNotMisclassifyEmbeddedIrmSubstring() {
        var turn = turnWithResult("t1", "bash",
                "This text contains airmass keyword but no format/capability issue", true);

        var insights = detector.analyze(turn);

        assertThat(insights).isEmpty();
    }

    @Test
    void mapsBackgroundTaskBroken() {
        var turn = turnWithResult("t1", "task_output",
                "Status: FAILED\nTask ID: abc\nError: worker crashed", true);

        var insights = detector.analyze(turn);

        assertThat(insights).hasSize(1);
        var insight = insights.getFirst();
        assertThat(insight.type()).isEqualTo(FailureType.BROKEN);
        assertThat(insight.source()).isEqualTo("background-task");
    }

    @Test
    void mapsCancelled() {
        var turn = turnWithResult("t1", "task",
                "Sub-agent error: cancelled by parent request", true);

        var insights = detector.analyze(turn);

        assertThat(insights).hasSize(1);
        assertThat(insights.getFirst().type()).isEqualTo(FailureType.CANCELLED);
    }

    @Test
    void ignoresNonErrorToolResults() {
        var messages = List.<Message>of(
                new Message.AssistantMessage(List.of(new ContentBlock.ToolUse("t1", "bash", "{}"))),
                Message.toolResult("t1", "ok", false)
        );
        var turn = new Turn(messages, StopReason.END_TURN, new Usage(0, 0));

        List<Insight.FailureInsight> insights = detector.analyze(turn);
        assertThat(insights).isEmpty();
    }

    private static Turn turnWithResult(String id, String tool, String output, boolean isError) {
        var messages = List.<Message>of(
                new Message.AssistantMessage(List.of(new ContentBlock.ToolUse(id, tool, "{}"))),
                Message.toolResult(id, output, isError)
        );
        return new Turn(messages, StopReason.END_TURN, new Usage(0, 0));
    }
}
