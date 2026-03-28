package dev.aceclaw.daemon;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class WorkspaceAttachmentRegistryTest {

    private final Path workspace = Path.of("/tmp/test-project");

    @Test
    void acquireSucceedsWhenNoExistingAttachment() {
        var registry = new WorkspaceAttachmentRegistry();
        var result = registry.acquire(workspace, "session-1", "cli-default");

        assertInstanceOf(WorkspaceAttachmentRegistry.AcquireResult.Acquired.class, result);
        var acquired = (WorkspaceAttachmentRegistry.AcquireResult.Acquired) result;
        assertEquals("session-1", acquired.attachment().sessionId());
    }

    @Test
    void acquireConflictsWhenWorkspaceAlreadyAttached() {
        var registry = new WorkspaceAttachmentRegistry();
        registry.acquire(workspace, "session-1", "cli-default");

        var result = registry.acquire(workspace, "session-2", "cli-default");

        assertInstanceOf(WorkspaceAttachmentRegistry.AcquireResult.Conflict.class, result);
        var conflict = (WorkspaceAttachmentRegistry.AcquireResult.Conflict) result;
        assertEquals("session-1", conflict.existing().sessionId());
        assertEquals(workspace, conflict.workspace());
    }

    @Test
    void releaseAllowsNewAcquisition() {
        var registry = new WorkspaceAttachmentRegistry();
        registry.acquire(workspace, "session-1", "cli-default");

        assertTrue(registry.release(workspace, "session-1"));

        var result = registry.acquire(workspace, "session-2", "cli-default");
        assertInstanceOf(WorkspaceAttachmentRegistry.AcquireResult.Acquired.class, result);
    }

    @Test
    void releaseIgnoresMismatchedSession() {
        var registry = new WorkspaceAttachmentRegistry();
        registry.acquire(workspace, "session-1", "cli-default");

        assertFalse(registry.release(workspace, "session-wrong"));
        assertEquals(1, registry.activeCount());
    }

    @Test
    void heartbeatUpdatesLastSeen() {
        var registry = new WorkspaceAttachmentRegistry();
        registry.acquire(workspace, "session-1", "cli-default");

        assertTrue(registry.heartbeat(workspace, "session-1"));
        assertFalse(registry.heartbeat(workspace, "session-wrong"));
    }

    @Test
    void differentWorkspacesAreIndependent() {
        var registry = new WorkspaceAttachmentRegistry();
        var workspace2 = Path.of("/tmp/other-project");

        registry.acquire(workspace, "session-1", "cli-default");
        var result = registry.acquire(workspace2, "session-2", "cli-default");

        assertInstanceOf(WorkspaceAttachmentRegistry.AcquireResult.Acquired.class, result);
        assertEquals(2, registry.activeCount());
    }

    @Test
    void releaseAllClearsEverything() {
        var registry = new WorkspaceAttachmentRegistry();
        registry.acquire(workspace, "session-1", "cli-default");
        registry.acquire(Path.of("/tmp/other"), "session-2", "cli-default");

        registry.releaseAll();

        assertEquals(0, registry.activeCount());
        assertNull(registry.getAttachment(workspace));
    }
}
