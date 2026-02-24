package dev.aceclaw.daemon;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.aceclaw.core.llm.StopReason;
import dev.aceclaw.memory.CandidateKind;
import dev.aceclaw.memory.CandidateState;
import dev.aceclaw.memory.CandidateStateMachine;
import dev.aceclaw.memory.CandidateStore;
import dev.aceclaw.memory.MemoryEntry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class StreamingAgentHandlerCandidateInjectionTest {

    @TempDir
    Path tempDir;

    @Test
    void runtimeKillSwitchDisablesInjectionWithoutRestart() throws Exception {
        var handler = new StreamingAgentHandler(
                null, null, null, null, new ObjectMapper());
        handler.setLlmConfig(null, "test-model", "BASE_PROMPT");
        handler.setCandidateInjectionConfig(10, 200);

        var smConfig = new CandidateStateMachine.Config(1, 0.3, 1.0, 10, Set.of());
        var store = new CandidateStore(tempDir, smConfig);
        store.load();
        var t0 = Instant.parse("2026-02-23T00:00:00Z");
        store.upsert(new CandidateStore.CandidateObservation(
                MemoryEntry.Category.ERROR_RECOVERY, CandidateKind.ERROR_RECOVERY,
                "retry transient timeout", "bash", List.of("bash", "timeout"),
                0.9, 1, 0, "src:a", t0));
        store.transition(store.all().getFirst().id(), dev.aceclaw.memory.CandidateState.PROMOTED, "test");
        handler.setCandidateStore(store);

        handler.setCandidateInjectionEnabled(true);
        String withInjection = handler.getSystemPromptForTest("s1");
        assertThat(withInjection).contains("Learned Strategies");

        handler.setCandidateInjectionEnabled(false);
        String withoutInjection = handler.getSystemPromptForTest("s1");
        assertThat(withoutInjection).isEqualTo("BASE_PROMPT");
    }

    @Test
    void plannedStyleOutcomeWritebackUpdatesInjectedCandidate() throws Exception {
        var handler = new StreamingAgentHandler(
                null, null, null, null, new ObjectMapper());
        handler.setLlmConfig(null, "test-model", "BASE_PROMPT");
        handler.setCandidateInjectionConfig(10, 200);

        var smConfig = new CandidateStateMachine.Config(1, 0.3, 1.0, 10, Set.of());
        var store = new CandidateStore(tempDir.resolve("planned"), smConfig);
        store.load();
        var t0 = Instant.parse("2026-02-23T00:00:00Z");
        store.upsert(new CandidateStore.CandidateObservation(
                MemoryEntry.Category.ERROR_RECOVERY, CandidateKind.ERROR_RECOVERY,
                "retry transient timeout", "bash", List.of("bash", "timeout"),
                0.9, 1, 0, "src:a", t0));
        var candidateId = store.all().getFirst().id();
        store.transition(candidateId, CandidateState.PROMOTED, "test");
        handler.setCandidateStore(store);

        handler.getSystemPromptForTest("plan-session");

        handler.recordInjectedCandidateOutcomesForTest("plan-session", false, false, StopReason.ERROR);

        var updated = store.byId(candidateId).orElseThrow();
        assertThat(updated.failureCount()).isGreaterThan(0);
        assertThat(updated.evidenceCount()).isGreaterThan(1);
    }
}
