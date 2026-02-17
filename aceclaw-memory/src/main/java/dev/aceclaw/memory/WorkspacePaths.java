package dev.aceclaw.memory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Resolves workspace-scoped memory storage paths.
 *
 * <p>Each workspace (project directory) gets its own isolated memory directory:
 * {@code ~/.aceclaw/workspaces/{hash}/memory/}
 *
 * <p>The hash is a truncated SHA-256 of the absolute normalized workspace path,
 * providing collision-resistant directory names without exposing the full path.
 *
 * <p>On first access, this class also attempts to migrate old-format memory files
 * ({@code ~/.aceclaw/memory/project-{hash}.jsonl}) into the new workspace directory.
 */
public final class WorkspacePaths {

    private static final Logger log = LoggerFactory.getLogger(WorkspacePaths.class);

    private static final String WORKSPACES_DIR = "workspaces";
    private static final String MEMORY_DIR = "memory";
    private static final int HASH_PREFIX_LENGTH = 12; // 12 hex chars = 48 bits

    private WorkspacePaths() {}

    /**
     * Resolves the workspace memory directory for a given workspace path.
     * Creates the directory if it doesn't exist.
     *
     * @param aceclawHome the aceclaw home directory (e.g. ~/.aceclaw)
     * @param workspacePath the workspace/project directory
     * @return the workspace memory directory
     * @throws IOException if the directory cannot be created
     */
    public static Path resolve(Path aceclawHome, Path workspacePath) throws IOException {
        String hash = workspaceHash(workspacePath);
        Path workspaceDir = aceclawHome.resolve(WORKSPACES_DIR).resolve(hash);
        Path memoryDir = workspaceDir.resolve(MEMORY_DIR);
        Files.createDirectories(memoryDir);

        // Write a marker file with the original workspace path for human reference
        Path markerFile = workspaceDir.resolve("workspace-path.txt");
        if (!Files.exists(markerFile)) {
            Files.writeString(markerFile, workspacePath.toAbsolutePath().normalize().toString());
        }

        // Attempt migration from old format on first access
        migrateOldFormat(aceclawHome, workspacePath, memoryDir);

        return memoryDir;
    }

    /**
     * Computes the workspace hash for a given workspace path.
     * Uses SHA-256 truncated to {@value #HASH_PREFIX_LENGTH} hex characters.
     *
     * @param workspacePath the workspace directory
     * @return the truncated hex hash
     */
    public static String workspaceHash(Path workspacePath) {
        String abs = workspacePath.toAbsolutePath().normalize().toString();
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(abs.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash).substring(0, HASH_PREFIX_LENGTH);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is always available in Java
            throw new RuntimeException("SHA-256 not available", e);
        }
    }

    /**
     * Migrates old-format memory files to the new workspace directory.
     *
     * <p>Old format: {@code ~/.aceclaw/memory/project-{hashCode}.jsonl}
     * <p>New format: {@code ~/.aceclaw/workspaces/{sha256}/memory/project.jsonl}
     */
    private static void migrateOldFormat(Path aceclawHome, Path workspacePath, Path newMemoryDir) {
        try {
            Path oldMemoryDir = aceclawHome.resolve("memory");
            if (!Files.isDirectory(oldMemoryDir)) return;

            // Old format uses Integer.toHexString(hashCode()) for the filename
            String abs = workspacePath.toAbsolutePath().normalize().toString();
            int hashCode = abs.hashCode();
            String oldFileName = "project-" + Integer.toHexString(hashCode) + ".jsonl";
            Path oldFile = oldMemoryDir.resolve(oldFileName);

            if (!Files.isRegularFile(oldFile)) return;

            Path newFile = newMemoryDir.resolve("project.jsonl");
            if (Files.exists(newFile)) {
                // New file already exists, don't overwrite
                return;
            }

            Files.copy(oldFile, newFile);
            log.info("Migrated old memory file {} to {}", oldFile, newFile);
        } catch (IOException e) {
            log.warn("Failed to migrate old memory file: {}", e.getMessage());
        }
    }
}
