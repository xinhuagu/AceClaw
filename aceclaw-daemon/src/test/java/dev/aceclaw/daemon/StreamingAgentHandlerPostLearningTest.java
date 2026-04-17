package dev.aceclaw.daemon;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.aceclaw.core.agent.Turn;
import dev.aceclaw.core.llm.Message;
import dev.aceclaw.core.llm.StopReason;
import dev.aceclaw.core.llm.Usage;
import dev.aceclaw.memory.AutoMemoryStore;
import dev.aceclaw.memory.CandidateStateMachine;
import dev.aceclaw.memory.CandidateStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end guard for the caller-side contract fixed in #411: {@code StreamingAgentHandler}
 * must invoke {@code SelfImprovementEngine.persist(...)} — and therefore fire the draft
 * re-evaluation trigger — on every post-request cycle, including when the analyzer returns no
 * insights. The engine-side analogue lives in
 * {@code CandidatePipelineIntegrationTest.triggerFiresEvenWithEmptyInsights}; this test
 * prevents a regression where the caller-side condition is put back and the engine-side test
 * would still pass.
 */
class StreamingAgentHandlerPostLearningTest {

    @TempDir
    Path tempDir;

    @Test
    void noInsightTurnStillFiresDraftReevaluationTrigger() throws Exception {
        var memoryStore = new AutoMemoryStore(tempDir);
        memoryStore.load(tempDir);

        var smConfig = new CandidateStateMachine.Config(1, 0.3, 1.0, 10, Set.of());
        var candidateStore = new CandidateStore(tempDir, smConfig);
        candidateStore.load();

        var triggerFired = new AtomicBoolean(false);
        var engine = new SelfImprovementEngine(
                new ErrorDetector(memoryStore),
                new PatternDetector(memoryStore),
                new FailureSignalDetector(),
                memoryStore,
                null,
                candidateStore,
                true,
                projectPath -> triggerFired.set(true));

        var handler = new StreamingAgentHandler(null, null, null, null, new ObjectMapper());
        handler.setSelfImprovementEngine(engine);

        // A turn with only a plain assistant reply produces no insights — analyze() returns [].
        // Before #411 this silently skipped persist(), which carries the trigger. After the
        // fix persist() runs anyway and the trigger must fire.
        var turn = new Turn(
                List.of(Message.assistant("Hi! How can I help?")),
                StopReason.END_TURN,
                new Usage(3, 42));

        handler.runPostRequestLearning(
                "test-session",
                tempDir,
                turn,
                List.of(),
                Map.of(),
                Set.of());

        assertThat(triggerFired.get())
                .as("Draft re-evaluation trigger must fire even when the turn produced no insights; "
                        + "otherwise snapshot refresh stalls on trivial turns (see #411).")
                .isTrue();
    }
}
