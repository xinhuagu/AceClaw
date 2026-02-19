package dev.aceclaw.security;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link DefaultPermissionPolicy} covering all four permission modes.
 */
class DefaultPermissionPolicyTest {

    // -- Mode: normal --------------------------------------------------------

    @Test
    void normalMode_readIsAutoApproved() {
        var policy = new DefaultPermissionPolicy("normal");
        var request = new PermissionRequest("read_file", "Read test.txt", PermissionLevel.READ);
        assertInstanceOf(PermissionDecision.Approved.class, policy.evaluate(request));
    }

    @Test
    void normalMode_writeNeedsApproval() {
        var policy = new DefaultPermissionPolicy("normal");
        var request = new PermissionRequest("write_file", "Write test.txt", PermissionLevel.WRITE);
        var decision = policy.evaluate(request);
        assertInstanceOf(PermissionDecision.NeedsUserApproval.class, decision);
        assertTrue(((PermissionDecision.NeedsUserApproval) decision).prompt().contains("write to"));
    }

    @Test
    void normalMode_executeNeedsApproval() {
        var policy = new DefaultPermissionPolicy("normal");
        var request = new PermissionRequest("bash", "Run ls -la", PermissionLevel.EXECUTE);
        assertInstanceOf(PermissionDecision.NeedsUserApproval.class, policy.evaluate(request));
    }

    @Test
    void normalMode_dangerousNeedsApproval() {
        var policy = new DefaultPermissionPolicy("normal");
        var request = new PermissionRequest("bash", "rm -rf /", PermissionLevel.DANGEROUS);
        var decision = policy.evaluate(request);
        assertInstanceOf(PermissionDecision.NeedsUserApproval.class, decision);
        assertTrue(((PermissionDecision.NeedsUserApproval) decision).prompt().contains("destructive"));
    }

    @Test
    void normalMode_isDefaultConstructor() {
        var policy = new DefaultPermissionPolicy();
        assertEquals("normal", policy.mode());
    }

    // -- Mode: accept-edits --------------------------------------------------

    @Test
    void acceptEditsMode_writeIsAutoApproved() {
        var policy = new DefaultPermissionPolicy("accept-edits");
        var request = new PermissionRequest("write_file", "Write test.txt", PermissionLevel.WRITE);
        assertInstanceOf(PermissionDecision.Approved.class, policy.evaluate(request));
    }

    @Test
    void acceptEditsMode_executeStillNeedsApproval() {
        var policy = new DefaultPermissionPolicy("accept-edits");
        var request = new PermissionRequest("bash", "Run ls", PermissionLevel.EXECUTE);
        assertInstanceOf(PermissionDecision.NeedsUserApproval.class, policy.evaluate(request));
    }

    @Test
    void acceptEditsMode_readIsAutoApproved() {
        var policy = new DefaultPermissionPolicy("accept-edits");
        var request = new PermissionRequest("read_file", "Read file", PermissionLevel.READ);
        assertInstanceOf(PermissionDecision.Approved.class, policy.evaluate(request));
    }

    // -- Mode: plan ----------------------------------------------------------

    @Test
    void planMode_readIsAutoApproved() {
        var policy = new DefaultPermissionPolicy("plan");
        var request = new PermissionRequest("read_file", "Read file", PermissionLevel.READ);
        assertInstanceOf(PermissionDecision.Approved.class, policy.evaluate(request));
    }

    @Test
    void planMode_writeIsDenied() {
        var policy = new DefaultPermissionPolicy("plan");
        var request = new PermissionRequest("write_file", "Write file", PermissionLevel.WRITE);
        var decision = policy.evaluate(request);
        assertInstanceOf(PermissionDecision.Denied.class, decision);
        assertTrue(((PermissionDecision.Denied) decision).reason().contains("plan mode is read-only"));
    }

    @Test
    void planMode_executeIsDenied() {
        var policy = new DefaultPermissionPolicy("plan");
        var request = new PermissionRequest("bash", "Run command", PermissionLevel.EXECUTE);
        assertInstanceOf(PermissionDecision.Denied.class, policy.evaluate(request));
    }

    @Test
    void planMode_dangerousIsDenied() {
        var policy = new DefaultPermissionPolicy("plan");
        var request = new PermissionRequest("bash", "Dangerous op", PermissionLevel.DANGEROUS);
        assertInstanceOf(PermissionDecision.Denied.class, policy.evaluate(request));
    }

    // -- Mode: auto-accept ---------------------------------------------------

    @ParameterizedTest
    @EnumSource(PermissionLevel.class)
    void autoAcceptMode_allLevelsApproved(PermissionLevel level) {
        var policy = new DefaultPermissionPolicy("auto-accept");
        var request = new PermissionRequest("any_tool", "Any operation", level);
        assertInstanceOf(PermissionDecision.Approved.class, policy.evaluate(request));
    }

    // -- Legacy boolean constructor ------------------------------------------

    @SuppressWarnings("deprecation")
    @Test
    void legacyTrue_isAutoAccept() {
        var policy = new DefaultPermissionPolicy(true);
        assertEquals("auto-accept", policy.mode());
    }

    @SuppressWarnings("deprecation")
    @Test
    void legacyFalse_isNormal() {
        var policy = new DefaultPermissionPolicy(false);
        assertEquals("normal", policy.mode());
    }

    // -- Invalid mode --------------------------------------------------------

    @Test
    void invalidMode_throwsIllegalArgument() {
        assertThrows(IllegalArgumentException.class,
                () -> new DefaultPermissionPolicy("bogus"));
    }

    // -- PermissionDecision.isApproved convenience ---------------------------

    @Test
    void isApproved_trueForApproved() {
        assertTrue(new PermissionDecision.Approved().isApproved());
    }

    @Test
    void isApproved_falseForDenied() {
        assertFalse(new PermissionDecision.Denied("reason").isApproved());
    }

    @Test
    void isApproved_falseForNeedsUserApproval() {
        assertFalse(new PermissionDecision.NeedsUserApproval("prompt").isApproved());
    }
}
