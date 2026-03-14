package dev.aceclaw.daemon;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LearningMaintenanceRecoveryStoreTest {

    @TempDir
    Path tempDir;

    @Test
    void markStartedAndFailedPersistRecoveryState() throws Exception {
        var store = new LearningMaintenanceRecoveryStore();
        var workspace = tempDir.resolve("workspace");
        Files.createDirectories(workspace);

        var started = store.markStarted(workspace, "ws-a", "scheduled");
        assertThat(started.status()).isEqualTo(LearningMaintenanceRecoveryStore.RecoveryStatus.RUNNING);
        assertThat(started.attempt()).isEqualTo(1);
        assertThat(store.needsRecovery(workspace, "ws-a")).isTrue();

        store.markFailed(workspace, "ws-a", "scheduled", new IllegalStateException("boom"));
        var failed = store.load(workspace).orElseThrow();

        assertThat(failed.status()).isEqualTo(LearningMaintenanceRecoveryStore.RecoveryStatus.FAILED);
        assertThat(failed.attempt()).isEqualTo(1);
        assertThat(failed.lastError()).contains("IllegalStateException");
        assertThat(store.needsRecovery(workspace, "ws-a")).isTrue();
    }

    @Test
    void clearRemovesRecoveryRequirement() throws Exception {
        var store = new LearningMaintenanceRecoveryStore();
        var workspace = tempDir.resolve("workspace");
        Files.createDirectories(workspace);

        store.markStarted(workspace, "ws-a", "session-count");
        assertThat(store.needsRecovery(workspace, "ws-a")).isTrue();

        store.clear(workspace);

        assertThat(store.load(workspace)).isEmpty();
        assertThat(store.needsRecovery(workspace, "ws-a")).isFalse();
    }

    @Test
    void unreadableExistingStateIsTreatedAsRecoveryNeeded() throws Exception {
        var store = new LearningMaintenanceRecoveryStore();
        var workspace = tempDir.resolve("workspace");
        Files.createDirectories(workspace);
        Files.createDirectories(store.stateFile(workspace).getParent());
        Files.writeString(store.stateFile(workspace), "{not-json");

        assertThatThrownBy(() -> store.load(workspace))
                .isInstanceOf(IOException.class);
        assertThat(store.needsRecovery(workspace, "ws-a")).isTrue();
    }
}
