package dev.aceclaw.memory;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class WorkspacePathsTest {

    @TempDir
    Path tempDir;

    @Test
    void resolveCreatesDirectoryStructure() throws IOException {
        Path workspace = tempDir.resolve("my-project");
        Files.createDirectories(workspace);

        Path memDir = WorkspacePaths.resolve(tempDir, workspace);

        assertThat(Files.isDirectory(memDir)).isTrue();
        assertThat(memDir.getParent().getParent().getFileName().toString()).isEqualTo("workspaces");
    }

    @Test
    void resolveCreatesMarkerFile() throws IOException {
        Path workspace = tempDir.resolve("project-alpha");
        Files.createDirectories(workspace);

        Path memDir = WorkspacePaths.resolve(tempDir, workspace);

        Path markerFile = memDir.getParent().resolve("workspace-path.txt");
        assertThat(Files.exists(markerFile)).isTrue();
        String content = Files.readString(markerFile);
        assertThat(content).contains("project-alpha");
    }

    @Test
    void hashIsDeterministic() {
        Path workspace = tempDir.resolve("stable-project");
        String hash1 = WorkspacePaths.workspaceHash(workspace);
        String hash2 = WorkspacePaths.workspaceHash(workspace);

        assertThat(hash1).isEqualTo(hash2);
    }

    @Test
    void differentPathsProduceDifferentHashes() {
        String hash1 = WorkspacePaths.workspaceHash(tempDir.resolve("project-a"));
        String hash2 = WorkspacePaths.workspaceHash(tempDir.resolve("project-b"));

        assertThat(hash1).isNotEqualTo(hash2);
    }

    @Test
    void hashLength() {
        String hash = WorkspacePaths.workspaceHash(tempDir.resolve("any-project"));
        assertThat(hash).hasSize(12); // 12 hex chars
    }

    @Test
    void migrateOldFormatOnFirstAccess() throws IOException {
        Path workspace = tempDir.resolve("legacy-project");
        Files.createDirectories(workspace);

        // Create old-format memory file
        Path oldMemDir = tempDir.resolve("memory");
        Files.createDirectories(oldMemDir);
        String abs = workspace.toAbsolutePath().normalize().toString();
        int hashCode = abs.hashCode();
        String oldFileName = "project-" + Integer.toHexString(hashCode) + ".jsonl";
        Files.writeString(oldMemDir.resolve(oldFileName), "{\"id\":\"old-entry\"}\n");

        // Resolve should trigger migration
        Path memDir = WorkspacePaths.resolve(tempDir, workspace);

        Path migratedFile = memDir.resolve("project.jsonl");
        assertThat(Files.exists(migratedFile)).isTrue();
        assertThat(Files.readString(migratedFile)).contains("old-entry");
    }

    @Test
    void doNotOverwriteExistingOnMigration() throws IOException {
        Path workspace = tempDir.resolve("existing-project");
        Files.createDirectories(workspace);

        // Create old-format memory file
        Path oldMemDir = tempDir.resolve("memory");
        Files.createDirectories(oldMemDir);
        String abs = workspace.toAbsolutePath().normalize().toString();
        int hashCode = abs.hashCode();
        String oldFileName = "project-" + Integer.toHexString(hashCode) + ".jsonl";
        Files.writeString(oldMemDir.resolve(oldFileName), "{\"id\":\"old\"}\n");

        // Pre-create new-format file
        Path memDir = WorkspacePaths.resolve(tempDir, workspace);
        Path newFile = memDir.resolve("project.jsonl");
        Files.writeString(newFile, "{\"id\":\"new\"}\n");

        // Re-resolve should NOT overwrite
        WorkspacePaths.resolve(tempDir, workspace);
        assertThat(Files.readString(newFile)).contains("new");
        assertThat(Files.readString(newFile)).doesNotContain("old");
    }

    @Test
    void resolveIsIdempotent() throws IOException {
        Path workspace = tempDir.resolve("idempotent-project");
        Files.createDirectories(workspace);

        Path first = WorkspacePaths.resolve(tempDir, workspace);
        Path second = WorkspacePaths.resolve(tempDir, workspace);

        assertThat(first).isEqualTo(second);
    }
}
