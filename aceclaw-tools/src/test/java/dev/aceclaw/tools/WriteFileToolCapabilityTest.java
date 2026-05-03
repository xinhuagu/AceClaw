package dev.aceclaw.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.aceclaw.security.Capability;
import dev.aceclaw.security.WriteMode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Pins the {@link dev.aceclaw.security.CapabilityAware} migration of
 * {@link WriteFileTool} (#480 PR 2): the dispatcher in
 * {@code StreamingAgentHandler} calls {@code toCapability} before
 * {@code PermissionManager.check}, so this is the bridge that turns
 * LLM-supplied JSON args into a structured {@link Capability.FileWrite}
 * the policy and audit log can reason about.
 *
 * <p>If a future change accidentally drops the {@code CapabilityAware}
 * implementation or returns the wrong variant, this test fails — and
 * production silently falls back to the legacy {@code LegacyToolUse}
 * path without anyone noticing in code review.
 */
final class WriteFileToolCapabilityTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    Path workDir;

    @Test
    void toCapabilityForNonExistentPathProducesCreateNew() throws Exception {
        // Distinguishing CREATE_NEW from OVERWRITE at check time lets a
        // create-only policy refuse overwrites without re-stat'ing the
        // filesystem. Pin: a path that doesn't exist yet must come back
        // as CREATE_NEW.
        var tool = new WriteFileTool(workDir, ConcurrentHashMap.newKeySet());
        var args = MAPPER.readTree("{\"file_path\":\"new.txt\",\"content\":\"hi\"}");

        var cap = tool.toCapability(args);

        var fw = assertInstanceOf(Capability.FileWrite.class, cap,
                "WriteFileTool must produce FileWrite, not LegacyToolUse");
        assertEquals(workDir.resolve("new.txt").toAbsolutePath().normalize(),
                fw.path().toAbsolutePath().normalize(),
                "relative paths resolve against the tool's workingDir");
        assertEquals(WriteMode.CREATE_NEW, fw.mode(),
                "non-existent path -> CREATE_NEW so policies can refuse overwrites");
    }

    @Test
    void toCapabilityForExistingPathProducesOverwrite() throws Exception {
        // The other half of the pair: an existing file means the write
        // will overwrite, and the capability must say so.
        var existing = workDir.resolve("existing.txt");
        Files.writeString(existing, "old");
        var tool = new WriteFileTool(workDir, ConcurrentHashMap.newKeySet());
        var args = MAPPER.readTree("{\"file_path\":\"existing.txt\",\"content\":\"new\"}");

        var cap = tool.toCapability(args);

        var fw = assertInstanceOf(Capability.FileWrite.class, cap);
        assertEquals(WriteMode.OVERWRITE, fw.mode(),
                "existing file -> OVERWRITE");
    }

    @Test
    void toCapabilityRejectsMissingOrBlankFilePath() throws Exception {
        var tool = new WriteFileTool(workDir, ConcurrentHashMap.newKeySet());
        // The dispatcher catches IllegalArgumentException and falls back to
        // the legacy permission path, so the user still sees a meaningful
        // prompt rather than a half-formed FileWrite hitting the policy.
        var noPath = MAPPER.readTree("{\"content\":\"hi\"}");
        var blankPath = MAPPER.readTree("{\"file_path\":\"  \",\"content\":\"hi\"}");
        var nullArgs = (com.fasterxml.jackson.databind.JsonNode) null;

        assertThrows(IllegalArgumentException.class, () -> tool.toCapability(noPath));
        assertThrows(IllegalArgumentException.class, () -> tool.toCapability(blankPath));
        assertThrows(IllegalArgumentException.class, () -> tool.toCapability(nullArgs));
    }
}
