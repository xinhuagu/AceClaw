package dev.aceclaw.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ResumeCheckpointStoreTest {

    @TempDir
    Path tempDir;

    @Test
    void routesSessionBeforeClientInstanceAndWorkspace() {
        var store = new ResumeCheckpointStore(tempDir);
        store.recordTaskSubmitted("s1", "1", "w1", "cli", "cli-default", "goal-s1", true);
        store.recordTaskCompletion("s1", "1", ResumeCheckpointStore.Status.PAUSED,
                "paused-s1", "hint-s1", List.of());

        store.recordTaskSubmitted("s2", "1", "w1", "cli", "cli-default", "goal-s2", true);
        store.recordTaskCompletion("s2", "1", ResumeCheckpointStore.Status.PAUSED,
                "paused-s2", "hint-s2", List.of());

        var route = store.routeForContinue("s2", "w1", "cli-default");
        assertThat(route.checkpoint()).isNotNull();
        assertThat(route.route()).isEqualTo("session");
        assertThat(route.checkpoint().sessionId()).isEqualTo("s2");
    }

    @Test
    void doesNotRouteAcrossWorkspaceWhenNoMatch() {
        var store = new ResumeCheckpointStore(tempDir);
        store.recordTaskSubmitted("s1", "1", "workspace-a", "cli", "cli-default", "goal-a", true);
        store.recordTaskCompletion("s1", "1", ResumeCheckpointStore.Status.PAUSED,
                "paused-a", "hint-a", List.of());

        var route = store.routeForContinue("s2", "workspace-b", "cli-default");
        assertThat(route.checkpoint()).isNull();
        assertThat(route.route()).isEqualTo("fallback");
    }

    @Test
    void persistsCheckpointToSessionTaskPath() {
        var store = new ResumeCheckpointStore(tempDir);
        store.recordTaskSubmitted("session-x", "task-42", "w1", "cli", "cli-default", "goal-x", true);

        Path file = tempDir.resolve("sessions/session-x/tasks/task-42.checkpoint.json");
        assertThat(Files.isRegularFile(file)).isTrue();
    }

    @Test
    void buildResumePromptContainsStructuredBlock() {
        var checkpoint = new ResumeCheckpointStore.Checkpoint(
                "1",
                "s1",
                "2026-02-26T00:00:00Z",
                "2026-02-26T00:01:00Z",
                ResumeCheckpointStore.Status.PAUSED.name(),
                "w1",
                "cli",
                "cli-default",
                "finish docs",
                "editing section 2",
                List.of(),
                List.of(),
                List.of(),
                "parser failed on malformed JSON",
                true
        );
        String prompt = ResumeCheckpointStore.buildResumePrompt(checkpoint, "keep style concise");
        assertThat(prompt).contains("[RESUME_CONTEXT]");
        assertThat(prompt).contains("taskId: 1");
        assertThat(prompt).contains("goal: finish docs");
        assertThat(prompt).contains("Additional instruction:");
    }
}
