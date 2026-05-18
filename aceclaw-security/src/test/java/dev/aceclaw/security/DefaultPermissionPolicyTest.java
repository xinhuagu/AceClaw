package dev.aceclaw.security;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link DefaultPermissionPolicy} covering all four permission
 * modes and the structural hard-denial layer that overrides every mode.
 */
class DefaultPermissionPolicyTest {

    private static final Provenance PROV = Provenance.daemonInternal();

    private static Capability.FileRead read(String p) {
        return new Capability.FileRead(Path.of(p));
    }

    private static Capability.FileWrite write(String p) {
        return new Capability.FileWrite(Path.of(p), WriteMode.OVERWRITE);
    }

    private static Capability.FileDelete delete(String p) {
        return new Capability.FileDelete(Path.of(p));
    }

    private static Capability.BashExec bash(String command) {
        return new Capability.BashExec(command, Path.of("/tmp"));
    }

    private static PermissionDecision evaluate(DefaultPermissionPolicy policy, Capability cap, String desc) {
        return policy.evaluate(cap, PROV, desc);
    }

    // -- Mode: normal --------------------------------------------------------

    @Test
    void normalMode_readIsAutoApproved() {
        var policy = new DefaultPermissionPolicy("normal");
        assertInstanceOf(PermissionDecision.Approved.class,
                evaluate(policy, read("test.txt"), "Read test.txt"));
    }

    @Test
    void normalMode_writeNeedsApproval() {
        var policy = new DefaultPermissionPolicy("normal");
        var decision = evaluate(policy, write("/tmp/foo.txt"), "Write /tmp/foo.txt");
        assertInstanceOf(PermissionDecision.NeedsUserApproval.class, decision);
        assertTrue(((PermissionDecision.NeedsUserApproval) decision).prompt().contains("write to"));
    }

    @Test
    void normalMode_executeNeedsApproval() {
        var policy = new DefaultPermissionPolicy("normal");
        assertInstanceOf(PermissionDecision.NeedsUserApproval.class,
                evaluate(policy, bash("ls -la"), "Run ls -la"));
    }

    @Test
    void normalMode_dangerousNeedsApproval() {
        // BashExec self-escalates "rm -rf" to DANGEROUS via Capability.risk().
        var policy = new DefaultPermissionPolicy("normal");
        var decision = evaluate(policy, bash("rm -rf /tmp/junk"), "Run rm -rf /tmp/junk");
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
        assertInstanceOf(PermissionDecision.Approved.class,
                evaluate(policy, write("/tmp/foo.txt"), "Write /tmp/foo.txt"));
    }

    @Test
    void acceptEditsMode_executeStillNeedsApproval() {
        var policy = new DefaultPermissionPolicy("accept-edits");
        assertInstanceOf(PermissionDecision.NeedsUserApproval.class,
                evaluate(policy, bash("ls"), "Run ls"));
    }

    @Test
    void acceptEditsMode_readIsAutoApproved() {
        var policy = new DefaultPermissionPolicy("accept-edits");
        assertInstanceOf(PermissionDecision.Approved.class,
                evaluate(policy, read("file"), "Read file"));
    }

    // -- Mode: plan ----------------------------------------------------------

    @Test
    void planMode_readIsAutoApproved() {
        var policy = new DefaultPermissionPolicy("plan");
        assertInstanceOf(PermissionDecision.Approved.class,
                evaluate(policy, read("file"), "Read file"));
    }

    @Test
    void planMode_writeIsDenied() {
        var policy = new DefaultPermissionPolicy("plan");
        var decision = evaluate(policy, write("/tmp/foo.txt"), "Write file");
        assertInstanceOf(PermissionDecision.Denied.class, decision);
        assertTrue(((PermissionDecision.Denied) decision).reason().contains("plan mode is read-only"));
    }

    @Test
    void planMode_executeIsDenied() {
        var policy = new DefaultPermissionPolicy("plan");
        assertInstanceOf(PermissionDecision.Denied.class,
                evaluate(policy, bash("ls"), "Run command"));
    }

    @Test
    void planMode_dangerousIsDenied() {
        var policy = new DefaultPermissionPolicy("plan");
        assertInstanceOf(PermissionDecision.Denied.class,
                evaluate(policy, bash("rm -rf /"), "Dangerous op"));
    }

    // -- Mode: auto-accept ---------------------------------------------------

    @Test
    void autoAcceptMode_readApproved() {
        var policy = new DefaultPermissionPolicy("auto-accept");
        assertInstanceOf(PermissionDecision.Approved.class,
                evaluate(policy, read("any.txt"), "any"));
    }

    @Test
    void autoAcceptMode_writeApproved() {
        var policy = new DefaultPermissionPolicy("auto-accept");
        assertInstanceOf(PermissionDecision.Approved.class,
                evaluate(policy, write("/tmp/any.txt"), "any"));
    }

    @Test
    void autoAcceptMode_executeApproved() {
        var policy = new DefaultPermissionPolicy("auto-accept");
        assertInstanceOf(PermissionDecision.Approved.class,
                evaluate(policy, bash("any command"), "any"));
    }

    @Test
    void autoAcceptMode_dangerousApproved() {
        var policy = new DefaultPermissionPolicy("auto-accept");
        assertInstanceOf(PermissionDecision.Approved.class,
                evaluate(policy, bash("rm -rf /tmp/junk"), "destructive bash"));
    }

    // -- Structural hard-denial: sensitive paths -----------------------------
    // These rules live on evaluateStructural(), which PermissionManager runs
    // BEFORE the session-blanket lookup so an "always allow X" approval can't
    // route past them.

    private static final DefaultPermissionPolicy STRUCTURAL = new DefaultPermissionPolicy("normal");

    @Test
    void writingDotEnvIsStructurallyDenied() {
        var decision = STRUCTURAL.evaluateStructural(write("/repo/.env"));
        assertNotNull(decision);
        assertTrue(decision.reason().contains("sensitive path"));
    }

    @Test
    void writingDotEnvLocalIsStructurallyDenied() {
        assertNotNull(STRUCTURAL.evaluateStructural(write("/repo/.env.local")));
    }

    @Test
    void writingDotEnvProductionIsStructurallyDenied() {
        assertNotNull(STRUCTURAL.evaluateStructural(write("/repo/.env.production")));
    }

    @Test
    void writingDotenvNotesIsNotDenied() {
        // Defense against substring-style false positives — segment matching
        // should NOT trip on files whose name merely contains "env".
        assertNull(STRUCTURAL.evaluateStructural(write("/repo/dotenv-notes.md")));
    }

    @Test
    void writingNotesAboutEnvIsNotDenied() {
        // Filename: "notes-on-env.md". Should NOT match (.env-prefix rule
        // only catches basenames that literally start with .env).
        assertNull(STRUCTURAL.evaluateStructural(write("/repo/notes-on-env.md")));
    }

    @Test
    void writingInsideSshIsStructurallyDenied() {
        assertNotNull(STRUCTURAL.evaluateStructural(write("/home/u/.ssh/config")));
    }

    @Test
    void writingInsideAwsIsStructurallyDenied() {
        assertNotNull(STRUCTURAL.evaluateStructural(write("/home/u/.aws/credentials")));
    }

    @Test
    void writingCredentialsJsonIsStructurallyDenied() {
        assertNotNull(STRUCTURAL.evaluateStructural(write("/tmp/credentials.json")));
    }

    @Test
    void writingGitConfigIsStructurallyDenied() {
        assertNotNull(STRUCTURAL.evaluateStructural(write("/repo/.git/config")));
    }

    @Test
    void writingOtherGitInternalsIsNotDenied() {
        // Only .git/config is sensitive. .git/HEAD, .git/packed-refs, etc.
        // are touched by legitimate git plugins during normal operation.
        assertNull(STRUCTURAL.evaluateStructural(write("/repo/.git/HEAD")));
    }

    @Test
    void writingUnderEtcIsStructurallyDenied() {
        assertNotNull(STRUCTURAL.evaluateStructural(write("/etc/hosts")));
    }

    @Test
    void writingRelativeEtcLikePathIsNotDenied() {
        // Defense against root-level /etc rule false-tripping on relative
        // paths that happen to contain "etc" as a segment but aren't system
        // config (e.g. "docs/etc/notes.md").
        assertNull(STRUCTURAL.evaluateStructural(write("docs/etc/notes.md")));
    }

    @Test
    void pathTraversalBypassIsBlocked() {
        // The /etc/ rule must not be bypassable by lexical traversal —
        // /tmp/../etc/hosts effectively writes /etc/hosts. Path.normalize()
        // collapses .. lexically (no filesystem I/O) so the prefix check
        // still fires.
        assertNotNull(STRUCTURAL.evaluateStructural(write("/tmp/../etc/hosts")));
    }

    @Test
    void deletingDotEnvIsStructurallyDenied() {
        assertNotNull(STRUCTURAL.evaluateStructural(delete("/repo/.env")));
    }

    @Test
    void writingKubeConfigIsStructurallyDenied() {
        // .kube/config — Kubernetes auth tokens.
        assertNotNull(STRUCTURAL.evaluateStructural(write("/home/u/.kube/config")));
    }

    @Test
    void writingDockerConfigIsStructurallyDenied() {
        // .docker/config.json — registry credentials.
        assertNotNull(STRUCTURAL.evaluateStructural(write("/home/u/.docker/config.json")));
    }

    @Test
    void writingNpmrcIsStructurallyDenied() {
        // .npmrc — npm registry auth tokens.
        assertNotNull(STRUCTURAL.evaluateStructural(write("/repo/.npmrc")));
    }

    @Test
    void writingPypircIsStructurallyDenied() {
        // .pypirc — PyPI upload credentials.
        assertNotNull(STRUCTURAL.evaluateStructural(write("/home/u/.pypirc")));
    }

    @Test
    void writingIdEcdsaIsStructurallyDenied() {
        // SSH ECDSA private key (separate from RSA / ED25519).
        assertNotNull(STRUCTURAL.evaluateStructural(write("/home/u/id_ecdsa")));
    }

    @Test
    void writingServiceAccountJsonIsStructurallyDenied() {
        // GCP service account keys are commonly named this; named here so
        // the rule fires even outside the .ssh/.aws/.gnupg segments.
        assertNotNull(STRUCTURAL.evaluateStructural(write("/tmp/service-account.json")));
    }

    @Test
    void nonFileCapabilityIsNotStructurallyDenied() {
        // BashExec, HttpFetch, etc. fall through structural checks today —
        // the layer only covers FileWrite/FileDelete.
        assertNull(STRUCTURAL.evaluateStructural(bash("rm -rf /tmp/junk")));
    }

    // -- Case-insensitive matching (Codex P2 on #495) -----------------------
    // On case-insensitive filesystems (default macOS APFS, Windows NTFS),
    // `.ENV` and `.env` resolve to the same underlying file, so the
    // structural rule has to match case-insensitively.

    @Test
    void uppercaseDotEnvIsStructurallyDenied() {
        assertNotNull(STRUCTURAL.evaluateStructural(write("/repo/.ENV")));
    }

    @Test
    void mixedCaseDotEnvLocalIsStructurallyDenied() {
        assertNotNull(STRUCTURAL.evaluateStructural(write("/repo/.Env.local")));
    }

    @Test
    void capitalizedCredentialsJsonIsStructurallyDenied() {
        assertNotNull(STRUCTURAL.evaluateStructural(write("/tmp/Credentials.json")));
    }

    @Test
    void uppercaseSshSegmentIsStructurallyDenied() {
        assertNotNull(STRUCTURAL.evaluateStructural(write("/home/u/.SSH/config")));
    }

    @Test
    void uppercaseGitConfigIsStructurallyDenied() {
        assertNotNull(STRUCTURAL.evaluateStructural(write("/repo/.GIT/config")));
    }

    @Test
    void uppercaseEtcPrefixIsStructurallyDenied() {
        assertNotNull(STRUCTURAL.evaluateStructural(write("/ETC/hosts")));
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

    // -- Description propagation to prompt -----------------------------------

    @Test
    void richDescriptionIsSurfacedInPrompt() {
        // Pin that the dispatcher's rich description (not the variant's
        // synthetic displayLabel) makes it into the user-facing prompt.
        var policy = new DefaultPermissionPolicy("normal");
        var decision = evaluate(policy, write("/tmp/foo.txt"),
                "Write /tmp/foo.txt (123 chars of new content)");
        var prompt = ((PermissionDecision.NeedsUserApproval) decision).prompt();
        assertTrue(prompt.contains("123 chars of new content"),
                "rich description should be surfaced verbatim in the prompt; got: " + prompt);
    }
}
