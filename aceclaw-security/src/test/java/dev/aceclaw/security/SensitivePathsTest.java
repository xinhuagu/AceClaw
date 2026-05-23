package dev.aceclaw.security;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
}
