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

    @Test
    void routesClientInstanceBeforeWorkspace() {
        var store = new ResumeCheckpointStore(tempDir);
        store.recordTaskSubmitted("s1", "1", "w1", "cli", "cli-a", "goal-a", true);
        store.recordTaskCompletion("s1", "1", ResumeCheckpointStore.Status.PAUSED,
                "paused-a", "hint-a", List.of());

        store.recordTaskSubmitted("s2", "2", "w1", "cli", "cli-b", "goal-b", true);
        store.recordTaskCompletion("s2", "2", ResumeCheckpointStore.Status.PAUSED,
                "paused-b", "hint-b", List.of());

        var route = store.routeForContinue("s3", "w1", "cli-b");
        assertThat(route.checkpoint()).isNotNull();
        assertThat(route.route()).isEqualTo("client-instance");
        assertThat(route.checkpoint().taskId()).isEqualTo("2");
    }

    @Test
    void workspaceRouteMarkedAmbiguousWhenTopCandidatesTie() throws Exception {
        var store = new ResumeCheckpointStore(tempDir);
        writeCheckpoint("s1", "task-1", "w1", "cli-a", "2026-02-26T12:00:00Z", true);
        writeCheckpoint("s2", "task-2", "w1", "cli-b", "2026-02-26T12:00:00Z", true);

        var route = store.routeForContinue("s3", "w1", "cli-default");
        assertThat(route.checkpoint()).isNotNull();
        assertThat(route.route()).isEqualTo("workspace");
        assertThat(route.ambiguous()).isTrue();
    }

    @Test
    void workspaceRoutePrefersForegroundTask() throws Exception {
        var store = new ResumeCheckpointStore(tempDir);
        writeCheckpoint("s1", "task-bg", "w2", "cli-a", "2026-02-26T12:00:00Z", false);
        writeCheckpoint("s2", "task-fg", "w2", "cli-b", "2026-02-26T12:00:00Z", true);

        var route = store.routeForContinue("s3", "w2", "cli-default");
        assertThat(route.checkpoint()).isNotNull();
        assertThat(route.checkpoint().taskId()).isEqualTo("task-fg");
        assertThat(route.ambiguous()).isFalse();
    }

    @Test
    void checkpointConstructorGuardsNullLists() {
        var checkpoint = new ResumeCheckpointStore.Checkpoint(
                "1",
                "s1",
                "2026-02-26T00:00:00Z",
                "2026-02-26T00:01:00Z",
                ResumeCheckpointStore.Status.PAUSED.name(),
                "w1",
                "cli",
                "cli-default",
                "goal",
                "step",
                null,
                null,
                null,
                "hint",
                false
        );
        assertThat(checkpoint.planSteps()).isEmpty();
        assertThat(checkpoint.artifacts()).isEmpty();
        assertThat(checkpoint.lastToolEvents()).isEmpty();
    }

    @Test
    void routesRunningStatusCheckpoint() {
        var store = new ResumeCheckpointStore(tempDir);
        store.recordTaskSubmitted("s1", "1", "w1", "cli", "cli-default", "goal", true);

        var route = store.routeForContinue("s1", "w1", "cli-default");
        assertThat(route.checkpoint()).isNotNull();
        assertThat(route.route()).isEqualTo("session");
    }

    private void writeCheckpoint(
            String sessionId,
            String taskId,
            String workspaceHash,
            String clientInstanceId,
            String updatedAt,
            boolean foreground) throws Exception {
        Path file = tempDir.resolve("sessions").resolve(sessionId).resolve("tasks")
                .resolve(taskId + ".checkpoint.json");
        Files.createDirectories(file.getParent());
        Files.writeString(file, """
                {
                  "taskId": "%s",
                  "sessionId": "%s",
                  "createdAt": "2026-02-26T11:59:00Z",
                  "updatedAt": "%s",
                  "status": "PAUSED",
                  "workspaceHash": "%s",
                  "clientType": "cli",
                  "clientInstanceId": "%s",
                  "userGoal": "goal",
                  "currentStep": "step",
                  "planSteps": [],
                  "artifacts": [],
                  "lastToolEvents": [],
                  "resumeHint": "hint",
                  "foreground": %s
                }
                """.formatted(taskId, sessionId, updatedAt, workspaceHash, clientInstanceId, foreground));
    }
}
