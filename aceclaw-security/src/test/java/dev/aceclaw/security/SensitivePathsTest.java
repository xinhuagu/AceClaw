package dev.aceclaw.security;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Unit tests for the {@link SensitivePaths} utility. The same rules are
 * exercised transitively by {@link DefaultPermissionPolicyTest}; this file
 * keeps coverage local to the utility so a future change to the rule set
 * gets pinned at its source rather than only through policy integration.
 */
class SensitivePathsTest {

    private static boolean matches(String p) {
        return SensitivePaths.matches(Path.of(p));
    }

    // -- basename rules ------------------------------------------------------

    @Test void dotEnvMatches() { assertTrue(matches("/repo/.env")); }
    @Test void dotEnvLocalMatches() { assertTrue(matches("/repo/.env.local")); }
    @Test void dotEnvProductionMatches() { assertTrue(matches("/repo/.env.production")); }
    @Test void credentialsJsonMatches() { assertTrue(matches("/tmp/credentials.json")); }
    @Test void netrcMatches() { assertTrue(matches("/home/u/.netrc")); }
    @Test void idRsaMatches() { assertTrue(matches("/home/u/id_rsa")); }
    @Test void idEd25519Matches() { assertTrue(matches("/home/u/id_ed25519")); }
    @Test void idEcdsaMatches() { assertTrue(matches("/home/u/id_ecdsa")); }
    @Test void npmrcMatches() { assertTrue(matches("/repo/.npmrc")); }
    @Test void pypircMatches() { assertTrue(matches("/home/u/.pypirc")); }
    @Test void serviceAccountJsonMatches() { assertTrue(matches("/tmp/service-account.json")); }

    // -- case-insensitivity (macOS APFS, Windows NTFS) ----------------------

    @Test void uppercaseDotEnvMatches() { assertTrue(matches("/repo/.ENV")); }
    @Test void mixedCaseCredentialsJsonMatches() { assertTrue(matches("/tmp/Credentials.json")); }
    @Test void uppercaseSshSegmentMatches() { assertTrue(matches("/home/u/.SSH/config")); }

    // -- segment rules ------------------------------------------------------

    @Test void sshSegmentMatches() { assertTrue(matches("/home/u/.ssh/config")); }
    @Test void awsCredentialsMatches() { assertTrue(matches("/home/u/.aws/credentials")); }
    @Test void gnupgMatches() { assertTrue(matches("/home/u/.gnupg/secring.gpg")); }
    @Test void kubeConfigMatches() { assertTrue(matches("/home/u/.kube/config")); }
    @Test void dockerConfigMatches() { assertTrue(matches("/home/u/.docker/config.json")); }

    // -- .git/config special case -------------------------------------------

    @Test void gitConfigMatches() { assertTrue(matches("/repo/.git/config")); }
    @Test void gitHeadDoesNotMatch() { assertFalse(matches("/repo/.git/HEAD")); }
    @Test void gitPackedRefsDoesNotMatch() { assertFalse(matches("/repo/.git/packed-refs")); }

    // -- /etc/ absolute prefix ----------------------------------------------

    @Test void etcHostsMatches() { assertTrue(matches("/etc/hosts")); }
    @Test void relativeEtcLikePathDoesNotMatch() { assertFalse(matches("docs/etc/notes.md")); }

    // -- traversal safety ---------------------------------------------------

    @Test void pathTraversalToEtcIsNormalized() {
        // /tmp/../etc/hosts normalizes to /etc/hosts -> matches.
        assertTrue(matches("/tmp/../etc/hosts"));
    }

    // -- false-positive guards ----------------------------------------------

    @Test void dotenvNotesDoesNotMatch() { assertFalse(matches("/repo/dotenv-notes.md")); }
    @Test void notesAboutEnvDoesNotMatch() { assertFalse(matches("/repo/notes-on-env.md")); }
    @Test void plainSafeFileDoesNotMatch() { assertFalse(matches("/tmp/foo.txt")); }

    // -- defensive ----------------------------------------------------------

    @Test void nullPathDoesNotMatch() { assertFalse(SensitivePaths.matches(null)); }

    // -- symlink resolution (#18 / #21) -------------------------------------
    // Real symlinks under @TempDir. Skipped on filesystems that refuse to
    // create symlinks (Windows without dev-mode, locked-down sandboxes) —
    // assumeTrue() turns the failure into a skip instead of a flake.

    /**
     * The original bypass: a directory symlink named innocuously points at a
     * credentials dir, and a write through the alias hits the real target.
     * Lexical match on the alias path doesn't fire; symlink resolution must
     * promote it to a sensitive write.
     */
    @Test
    void symlinkedDirectoryToDotSshIsSensitive(@TempDir Path tmp) throws IOException {
        Path realSshDir = Files.createDirectory(tmp.resolve(".ssh"));
        Path alias;
        try {
            alias = Files.createSymbolicLink(tmp.resolve("safe-dir"), realSshDir);
        } catch (UnsupportedOperationException | IOException e) {
            assumeTrue(false, "Filesystem refuses symlink creation: " + e.getMessage());
            return;
        }

        // Writing through the alias targets the real sensitive dir.
        assertTrue(SensitivePaths.matches(alias.resolve("id_rsa")),
                "symlink-fronted path should resolve to sensitive target");
        // Sanity: the real path is still recognised.
        assertTrue(SensitivePaths.matches(realSshDir.resolve("id_rsa")));
    }

    /**
     * Sensitive-source symlink: the file itself is a symlink to a credentials
     * file. The alias name is innocuous; only resolution shows the real target.
     */
    @Test
    void symlinkedFileToCredentialsIsSensitive(@TempDir Path tmp) throws IOException {
        Path real = Files.writeString(tmp.resolve("credentials.json"), "{}");
        Path alias;
        try {
            alias = Files.createSymbolicLink(tmp.resolve("config.txt"), real);
        } catch (UnsupportedOperationException | IOException e) {
            assumeTrue(false, "Filesystem refuses symlink creation: " + e.getMessage());
            return;
        }
        assertTrue(SensitivePaths.matches(alias),
                "alias whose target basename is sensitive must be denied");
    }

    /**
     * New-file-write case: target file doesn't exist yet, but its parent is
     * a symlink to a sensitive dir. The resolver should walk up to the parent
     * (which exists), resolve, then re-append the missing tail.
     */
    @Test
    void nonExistentFileUnderSymlinkedParentIsSensitive(@TempDir Path tmp) throws IOException {
        Path realSshDir = Files.createDirectory(tmp.resolve(".ssh"));
        Path alias;
        try {
            alias = Files.createSymbolicLink(tmp.resolve("safe-dir"), realSshDir);
        } catch (UnsupportedOperationException | IOException e) {
            assumeTrue(false, "Filesystem refuses symlink creation: " + e.getMessage());
            return;
        }
        // The file doesn't exist — this is the typical "write a new key" flow.
        Path newFileThroughAlias = alias.resolve("new_key");
        assertFalse(Files.exists(newFileThroughAlias));
        assertTrue(SensitivePaths.matches(newFileThroughAlias),
                "non-existent file under symlinked sensitive parent must still match");
    }

    /**
     * Lexical sensitivity wins even when resolution would move the path away
     * from the rule set. {@code /etc/hosts} on macOS canonicalises to
     * {@code /private/etc/hosts}, which doesn't lexically match the
     * {@code /etc/} prefix rule — but the original path does. Lexical-first
     * makes sure we don't lose denials to OS-specific filesystem layout.
     */
    @Test
    void lexicalMatchTakesPrecedenceOverResolution() {
        // /etc/hosts exists on macOS/Linux. Even though toRealPath may turn
        // it into /private/etc/hosts (macOS), the lexical check on the
        // ORIGINAL path catches it. This is a behaviour pin, not a setup
        // requiring filesystem manipulation.
        assertTrue(SensitivePaths.matches(Path.of("/etc/hosts")));
    }

    /**
     * Non-existent path that doesn't resolve and isn't lexically sensitive.
     * The resolver returns null; the matcher returns false. Should not throw.
     */
    @Test
    void nonExistentSafePathReturnsFalse(@TempDir Path tmp) {
        Path bogus = tmp.resolve("does-not-exist").resolve("definitely-not-here.txt");
        assertFalse(Files.exists(bogus));
        assertFalse(SensitivePaths.matches(bogus));
    }

    /**
     * Symlink to a safe target stays safe. Catches the easy false-positive
     * where any symlink would be treated as suspicious — only the resolved
     * target's sensitivity matters.
     */
    @Test
    void symlinkToSafeTargetStaysSafe(@TempDir Path tmp) throws IOException {
        Path realSafeFile = Files.writeString(tmp.resolve("notes.md"), "hi");
        Path alias;
        try {
            alias = Files.createSymbolicLink(tmp.resolve("aliased.txt"), realSafeFile);
        } catch (UnsupportedOperationException | IOException e) {
            assumeTrue(false, "Filesystem refuses symlink creation: " + e.getMessage());
            return;
        }
        assertFalse(SensitivePaths.matches(alias));
    }

    // -- lexical-only API (matchesLexical) ---------------------------------

    /**
     * {@link SensitivePaths#matchesLexical} is the IO-free version exposed
     * for callers that need the rule set without filesystem side effects
     * (audit dry-runs, symbolic analysis). Verify it returns the same
     * decision as {@code matches} for paths where symlinks don't change the
     * answer.
     */
    @Test
    void matchesLexicalAgreesOnPlainPaths() {
        assertTrue(SensitivePaths.matchesLexical(Path.of("/repo/.env")));
        assertTrue(SensitivePaths.matchesLexical(Path.of("/etc/hosts")));
        assertFalse(SensitivePaths.matchesLexical(Path.of("/tmp/safe.txt")));
        assertFalse(SensitivePaths.matchesLexical(null));
    }
}
