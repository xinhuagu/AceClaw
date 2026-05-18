package dev.aceclaw.core.agent;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;
import java.util.function.BiPredicate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link SubAgentPermissionChecker}.
 *
 * <p>Pinned behaviour:
 *
 * <ul>
 *   <li>read-only tools auto-approve regardless of session;</li>
 *   <li>session-approved tools auto-approve only for THE SAME session
 *       (#457): an approval recorded in session A must not flip to
 *       allowed when checked under session B;</li>
 *   <li>unapproved write/execute tools deny;</li>
 *   <li>{@code null} sessionId fails closed for non-read-only tools
 *       (daemon-internal callers without a session can't borrow another
 *       session's approval).</li>
 * </ul>
 */
class SubAgentPermissionCheckerTest {

    private static final Set<String> READ_ONLY = Set.of("read_file", "glob", "grep", "memory");

    /** Convenience: a checker with no session approvals at all. */
    private static SubAgentPermissionChecker denyAll() {
        return new SubAgentPermissionChecker(READ_ONLY, (sid, tool) -> false, SubAgentStructuralCheck.NONE);
    }

    @Test
    void readOnlyToolsAreAllowedRegardlessOfSession() {
        var checker = denyAll();

        // Read-only allow-list is session-agnostic; pass any sessionId
        // (or null) and the answer is the same.
        assertThat(checker.check("read_file", "{}", "any-session").allowed()).isTrue();
        assertThat(checker.check("glob", "{\"pattern\":\"*.java\"}", null).allowed()).isTrue();
        assertThat(checker.check("grep", "{}", "sess-Z").allowed()).isTrue();
        assertThat(checker.check("memory", "{}", "sess-Z").allowed()).isTrue();
    }

    @Test
    void sessionApprovedToolAllowedOnlyForSameSession() {
        // Approval matrix: session "A" approved write_file; session "B" did not.
        BiPredicate<String, String> isApproved = (sid, tool) ->
                "A".equals(sid) && "write_file".equals(tool);
        var checker = new SubAgentPermissionChecker(READ_ONLY, isApproved, SubAgentStructuralCheck.NONE);

        assertThat(checker.check("write_file", "{}", "A").allowed())
                .as("session A approved write_file → allowed under session A")
                .isTrue();

        // The whole point of #457: B did NOT approve write_file, so a
        // sub-agent running under session B must not silently inherit A's
        // approval.
        var resultB = checker.check("write_file", "{}", "B");
        assertThat(resultB.allowed())
                .as("session B has no write_file approval → must deny, not borrow A's")
                .isFalse();
        assertThat(resultB.reason()).contains("session approval");
    }

    @Test
    void nullSessionIdFailsClosedForNonReadOnlyTools() {
        // A daemon-internal caller with no session in scope cannot match
        // any session-scoped approval — fail-closed by construction.
        var alwaysApprove = new SubAgentPermissionChecker(READ_ONLY, (sid, tool) -> true, SubAgentStructuralCheck.NONE);

        var result = alwaysApprove.check("write_file", "{}", null);
        assertThat(result.allowed())
                .as("null sessionId must NOT skip the session-scope check; deny by default")
                .isFalse();
    }

    @Test
    void unapprovedWriteToolsDenied() {
        var checker = denyAll();

        var result = checker.check("write_file", "{}", "any");
        assertThat(result.allowed()).isFalse();
        assertThat(result.reason()).contains("write_file");
        assertThat(result.reason()).contains("session approval");
    }

    @Test
    void unapprovedExecuteToolsDenied() {
        var checker = denyAll();

        var result = checker.check("bash", "{\"command\":\"rm -rf /\"}", "any");
        assertThat(result.allowed()).isFalse();
        assertThat(result.reason()).contains("bash");
    }

    @Test
    void approvalScopeStaysConsistentAcrossManySessions() {
        // Multi-session sanity: each session has its own allow-list, and
        // a write_file approval in session A must remain invisible to
        // sessions B and C.
        var sessionApprovals = new HashSet<String>();
        sessionApprovals.add("A:write_file");
        BiPredicate<String, String> isApproved =
                (sid, tool) -> sessionApprovals.contains(sid + ":" + tool);
        var checker = new SubAgentPermissionChecker(READ_ONLY, isApproved, SubAgentStructuralCheck.NONE);

        assertThat(checker.check("write_file", "{}", "A").allowed()).isTrue();
        assertThat(checker.check("write_file", "{}", "B").allowed()).isFalse();
        assertThat(checker.check("write_file", "{}", "C").allowed()).isFalse();
    }

    @Test
    void structuralDenialOverridesSessionApproval() {
        // Codex P1 on #495: a sub-agent could call write_file(".env") if
        // the parent session blanket-approved write_file. The structural
        // probe (wired to the policy's evaluateStructural in the daemon)
        // must fire BEFORE the allow-list shortcut so the hard-denial
        // invariant holds for sub-agent dispatch too.
        BiPredicate<String, String> approveEverything = (sid, tool) -> true;
        SubAgentStructuralCheck refuseEnvWrites = (toolName, inputJson) -> {
            if ("write_file".equals(toolName) && inputJson != null && inputJson.contains(".env")) {
                return "Refusing to write sensitive .env";
            }
            return null;
        };
        var checker = new SubAgentPermissionChecker(
                READ_ONLY, approveEverything, refuseEnvWrites);

        // A non-sensitive write still gets the blanket approval.
        var safeResult = checker.check("write_file", "{\"path\":\"/tmp/safe\"}", "A");
        assertThat(safeResult.allowed())
                .as("safe write follows the blanket approval")
                .isTrue();

        // Sensitive write -- structural denial fires before the blanket check.
        var sensitiveResult = checker.check("write_file", "{\"path\":\"/repo/.env\"}", "A");
        assertThat(sensitiveResult.allowed())
                .as("sensitive write must be denied even with blanket approval")
                .isFalse();
        assertThat(sensitiveResult.reason()).contains("sensitive");
    }

    @Test
    void structuralDenialOverridesReadOnlyAllowlist() {
        // Read-only allowlist normally short-circuits. If structural rules
        // ever apply to a "read-only" tool (defensive -- they don't today
        // since structural rules only cover writes/deletes), they must
        // still take precedence.
        BiPredicate<String, String> denyAll = (sid, tool) -> false;
        SubAgentStructuralCheck refuseReadOnly = (toolName, inputJson) -> "blocked";
        var checker = new SubAgentPermissionChecker(
                READ_ONLY, denyAll, refuseReadOnly);

        var result = checker.check("read_file", "{}", "A");
        assertThat(result.allowed()).isFalse();
        assertThat(result.reason()).isEqualTo("blocked");
    }
}
