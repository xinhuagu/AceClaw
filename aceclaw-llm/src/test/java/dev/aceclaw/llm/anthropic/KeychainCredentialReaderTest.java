package dev.aceclaw.llm.anthropic;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KeychainCredentialReaderTest {

    @TempDir
    Path tempDir;

    // -- Credential record tests --

    @Test
    void credential_requiresNonNullAccessToken() {
        assertThatThrownBy(() -> new KeychainCredentialReader.Credential(null, "rt", 0))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void credential_allowsNullRefreshToken() {
        var cred = new KeychainCredentialReader.Credential("at", null, 0);
        assertThat(cred.refreshToken()).isNull();
    }

    @Test
    void credential_isExpired_beforeGracePeriod() {
        long futureMs = System.currentTimeMillis() + 120_000L; // 2 min from now
        var cred = new KeychainCredentialReader.Credential("at", "rt", futureMs);
        assertThat(cred.isExpired()).isFalse();
    }

    @Test
    void credential_isExpired_withinGracePeriod() {
        long soonMs = System.currentTimeMillis() + 30_000L; // 30s from now (< 60s grace)
        var cred = new KeychainCredentialReader.Credential("at", "rt", soonMs);
        assertThat(cred.isExpired()).isTrue();
    }

    @Test
    void credential_isExpired_inPast() {
        var cred = new KeychainCredentialReader.Credential("at", "rt", 1000L);
        assertThat(cred.isExpired()).isTrue();
    }

    // -- readFromFile tests --

    @Test
    void readFromFile_validJson_returnsCredential() throws Exception {
        Path credFile = tempDir.resolve("creds.json");
        Files.writeString(credFile, """
                {
                  "claudeAiOauth": {
                    "accessToken": "sk-ant-oat01-test-access",
                    "refreshToken": "sk-ant-ort01-test-refresh",
                    "expiresAt": 9999999999999
                  }
                }
                """);

        var cred = KeychainCredentialReader.readFromFile(credFile);
        assertThat(cred).isNotNull();
        assertThat(cred.accessToken()).isEqualTo("sk-ant-oat01-test-access");
        assertThat(cred.refreshToken()).isEqualTo("sk-ant-ort01-test-refresh");
        assertThat(cred.expiresAt()).isEqualTo(9999999999999L);
    }

    @Test
    void readFromFile_missingOauthField_returnsNull() throws Exception {
        Path credFile = tempDir.resolve("creds.json");
        Files.writeString(credFile, """
                { "someOtherField": "value" }
                """);

        assertThat(KeychainCredentialReader.readFromFile(credFile)).isNull();
    }

    @Test
    void readFromFile_missingAccessToken_returnsNull() throws Exception {
        Path credFile = tempDir.resolve("creds.json");
        Files.writeString(credFile, """
                {
                  "claudeAiOauth": {
                    "refreshToken": "rt",
                    "expiresAt": 123
                  }
                }
                """);

        assertThat(KeychainCredentialReader.readFromFile(credFile)).isNull();
    }

    @Test
    void readFromFile_blankAccessToken_returnsNull() throws Exception {
        Path credFile = tempDir.resolve("creds.json");
        Files.writeString(credFile, """
                {
                  "claudeAiOauth": {
                    "accessToken": "   ",
                    "refreshToken": "rt",
                    "expiresAt": 123
                  }
                }
                """);

        assertThat(KeychainCredentialReader.readFromFile(credFile)).isNull();
    }

    @Test
    void readFromFile_noRefreshToken_returnsCredentialWithNullRefresh() throws Exception {
        Path credFile = tempDir.resolve("creds.json");
        Files.writeString(credFile, """
                {
                  "claudeAiOauth": {
                    "accessToken": "sk-ant-oat01-test",
                    "expiresAt": 9999999999999
                  }
                }
                """);

        var cred = KeychainCredentialReader.readFromFile(credFile);
        assertThat(cred).isNotNull();
        assertThat(cred.accessToken()).isEqualTo("sk-ant-oat01-test");
        assertThat(cred.refreshToken()).isNull();
    }

    @Test
    void readFromFile_invalidJson_returnsNull() throws Exception {
        Path credFile = tempDir.resolve("creds.json");
        Files.writeString(credFile, "not json at all");

        assertThat(KeychainCredentialReader.readFromFile(credFile)).isNull();
    }

    @Test
    void readFromFile_nonexistentFile_returnsNull() {
        assertThat(KeychainCredentialReader.readFromFile(tempDir.resolve("nope.json"))).isNull();
    }

    @Test
    void readFromFile_emptyFile_returnsNull() throws Exception {
        Path credFile = tempDir.resolve("creds.json");
        Files.writeString(credFile, "");

        assertThat(KeychainCredentialReader.readFromFile(credFile)).isNull();
    }

    // -- writeToFile tests --

    @Test
    void writeToFile_nullAccessToken_throwsNPE() {
        assertThatThrownBy(() ->
                KeychainCredentialReader.writeToFile(null, "rt", 123))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void writeToKeychain_nullAccessToken_throwsNPE() {
        assertThatThrownBy(() ->
                KeychainCredentialReader.writeToKeychain(null, "rt", 123))
                .isInstanceOf(NullPointerException.class);
    }
}
