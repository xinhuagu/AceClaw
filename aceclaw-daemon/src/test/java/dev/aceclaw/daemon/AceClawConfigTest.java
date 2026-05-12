package dev.aceclaw.daemon;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.aceclaw.llm.anthropic.KeychainCredentialReader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class AceClawConfigTest {

    @Test
    void loadAppliesProviderOverrideWhenSupplied() {
        var config = AceClawConfig.load(null, "copilot");

        assertThat(config.provider()).isEqualTo("copilot");
    }

    @Test
    void loadNormalizesProviderOverrideToLowerCase() {
        var config = AceClawConfig.load(null, "OpenAI-Codex");

        assertThat(config.provider()).isEqualTo("openai-codex");
    }

    // -- WebSocket loopback gating (#446) --

    @Test
    void webSocketDefaultsOnForLoopbackHost(@TempDir Path tempDir) throws Exception {
        // Default config (no webSocket section) → loopback host → enabled=true.
        // Pin the new default-on behavior for the bundled-dashboard UX.
        var projectConfig = tempDir.resolve(".aceclaw").resolve("config.json");
        Files.createDirectories(projectConfig.getParent());
        Files.writeString(projectConfig, "{}");

        var config = AceClawConfig.load(tempDir, null);

        assertThat(config.webSocketHost()).isEqualTo("localhost");
        assertThat(config.webSocketEnabled()).isTrue();
    }

    @Test
    void webSocketGatedOffForNonLoopbackHostWithoutExplicitEnable(@TempDir Path tempDir) throws Exception {
        // Pre-existing config that set host=0.0.0.0 but never set enabled
        // relied on the OLD default (false). Flipping the default to true
        // would silently expose that user's daemon to LAN. The loopback gate
        // catches this — we must NOT default-on for non-loopback hosts.
        var projectConfig = tempDir.resolve(".aceclaw").resolve("config.json");
        Files.createDirectories(projectConfig.getParent());
        Files.writeString(projectConfig,
                "{\"webSocket\":{\"host\":\"0.0.0.0\"}}");

        var config = AceClawConfig.load(tempDir, null);

        assertThat(config.webSocketHost()).isEqualTo("0.0.0.0");
        assertThat(config.webSocketEnabled())
                .as("non-loopback host without explicit enabled must stay disabled")
                .isFalse();
    }

    @Test
    void webSocketRespectsExplicitEnableEvenOnNonLoopbackHost(@TempDir Path tempDir) throws Exception {
        // If the user opts in explicitly with enabled=true and a non-loopback
        // host, we honor that — the gate is a safety net for IMPLICIT
        // exposure, not a policy override.
        var projectConfig = tempDir.resolve(".aceclaw").resolve("config.json");
        Files.createDirectories(projectConfig.getParent());
        Files.writeString(projectConfig,
                "{\"webSocket\":{\"enabled\":true,\"host\":\"0.0.0.0\"}}");

        var config = AceClawConfig.load(tempDir, null);

        assertThat(config.webSocketEnabled()).isTrue();
    }

    // -- applyKeychainCredential tests --

    /**
     * Returns a truly blank config — bypasses {@code ~/.aceclaw/config.json}
     * and env vars so the developer's machine state can't leak into assertions.
     */
    private static AceClawConfig blankConfig() {
        return AceClawConfig.blankForTesting();
    }

    private static KeychainCredentialReader.Credential freshCredential(
            String accessToken, String refreshToken) {
        // expiresAt far in the future → not expired
        return new KeychainCredentialReader.Credential(
                accessToken, refreshToken, System.currentTimeMillis() + 3_600_000L);
    }

    private static KeychainCredentialReader.Credential expiredCredential(
            String accessToken, String refreshToken) {
        // expiresAt in the past → expired
        return new KeychainCredentialReader.Credential(accessToken, refreshToken, 1_000L);
    }

    @Test
    void applyKeychainCredential_freshToken_setsApiKey() {
        var config = blankConfig();
        var cred = freshCredential("sk-ant-oat01-fresh", "sk-ant-ort01-refresh");

        config.applyKeychainCredential(cred);

        assertThat(config.apiKey()).isEqualTo("sk-ant-oat01-fresh");
        assertThat(config.refreshToken()).isEqualTo("sk-ant-ort01-refresh");
    }

    @Test
    void applyKeychainCredential_expiredToken_stillSetsApiKey() {
        var config = blankConfig();
        var cred = expiredCredential("sk-ant-oat01-expired", "sk-ant-ort01-refresh");

        config.applyKeychainCredential(cred);

        // This is the key regression test: apiKey must be set even when expired,
        // so AnthropicClient can be constructed and will refresh before first request.
        assertThat(config.apiKey()).isEqualTo("sk-ant-oat01-expired");
        assertThat(config.refreshToken()).isEqualTo("sk-ant-ort01-refresh");
    }

    @Test
    void applyKeychainCredential_noRefreshToken_onlySetsApiKey() {
        var config = blankConfig();
        var cred = freshCredential("sk-ant-oat01-token", null);

        config.applyKeychainCredential(cred);

        assertThat(config.apiKey()).isEqualTo("sk-ant-oat01-token");
        assertThat(config.refreshToken()).isNull();
    }

    @Test
    void applyKeychainCredential_configHasNonOAuthApiKey_doesNotOverwrite() {
        var config = blankConfig();
        // Simulate config.json already having a regular API key
        config.applyKeychainCredential(freshCredential("sk-ant-api03-existing", null));
        String existingKey = config.apiKey();

        // Apply a Keychain credential — any profile-supplied apiKey pins the
        // account, so neither the key nor the refresh token should be replaced
        // from Claude CLI's shared store.
        var keychainCred = freshCredential("sk-ant-oat01-keychain", "sk-ant-ort01-refresh");
        config.applyKeychainCredential(keychainCred);

        assertThat(config.apiKey()).isEqualTo(existingKey);
        assertThat(config.refreshToken()).isNull();
    }

    // -- persistProfileCredentials --

    @Test
    void persistProfileCredentials_updatesApiKeyAndRefreshToken(@TempDir Path tmp) throws Exception {
        Path configFile = tmp.resolve("config.json");
        Files.writeString(configFile, """
                {"profiles":{"my-profile":{"apiKey":"old-key","refreshToken":"old-rt","model":"claude-sonnet"}}}
                """);

        AceClawConfig.persistProfileCredentials("my-profile", "new-access", "new-rt", configFile);

        var mapper = new ObjectMapper();
        var root = mapper.readTree(configFile.toFile());
        assertThat(root.path("profiles").path("my-profile").path("apiKey").asText()).isEqualTo("new-access");
        assertThat(root.path("profiles").path("my-profile").path("refreshToken").asText()).isEqualTo("new-rt");
        assertThat(root.path("profiles").path("my-profile").path("model").asText())
                .isEqualTo("claude-sonnet"); // other fields preserved
    }

    @Test
    void persistProfileCredentials_profileNotFound_noOp(@TempDir Path tmp) throws Exception {
        Path configFile = tmp.resolve("config.json");
        String original = """
                {"profiles":{"other":{"apiKey":"untouched"}}}
                """;
        Files.writeString(configFile, original);

        AceClawConfig.persistProfileCredentials("missing-profile", "new-key", "new-rt", configFile);

        // File should be unchanged
        var mapper = new ObjectMapper();
        var root = mapper.readTree(configFile.toFile());
        assertThat(root.path("profiles").path("other").path("apiKey").asText()).isEqualTo("untouched");
    }

    @Test
    void persistProfileCredentials_fileNotFound_noOp(@TempDir Path tmp) {
        Path configFile = tmp.resolve("nonexistent.json");
        // Should not throw even when the file does not exist
        AceClawConfig.persistProfileCredentials("my-profile", "new-key", "new-rt", configFile);
    }

    @Test
    void applyKeychainCredential_configHasOAuthToken_doesNotOverwrite() {
        // Regression guard: a profile-supplied OAuth token (even an expired one)
        // must pin the account. Previously applyKeychainCredential overwrote
        // OAuth tokens from Keychain, so a company profile would silently start
        // using the personal Claude CLI login after the first Keychain read.
        var config = blankConfig();
        config.applyKeychainCredential(expiredCredential("sk-ant-oat01-from-profile", "sk-ant-ort01-from-profile"));

        var keychainCred = freshCredential("sk-ant-oat01-personal-claude-cli", "sk-ant-ort01-personal");
        config.applyKeychainCredential(keychainCred);

        assertThat(config.apiKey()).isEqualTo("sk-ant-oat01-from-profile");
        assertThat(config.refreshToken()).isEqualTo("sk-ant-ort01-from-profile");
    }

    // -- Plan watchdog budget defaults --

    @Test
    void planBudgetDefaultsAreThirtyMinutesPerStepAndOneHourTotal(@TempDir Path tempDir) throws Exception {
        // Pins the per-step (1800s = 30 min) and plan-total (3600s = 1 h)
        // watchdog defaults the SequentialPlanExecutor reads via
        // setPlanBudgetConfig at daemon startup. The previous per-step
        // default (300s) was cancelling multi-step research plans the
        // moment step 1 finished, so a regression to a smaller value
        // here would be a user-visible reliability hit.
        var projectConfig = tempDir.resolve(".aceclaw").resolve("config.json");
        Files.createDirectories(projectConfig.getParent());
        Files.writeString(projectConfig, "{}");

        var config = AceClawConfig.load(tempDir, null);

        assertThat(config.maxPlanStepWallTimeSec())
                .as("per-step plan budget default")
                .isEqualTo(1800);
        assertThat(config.maxPlanTotalWallTimeSec())
                .as("total plan budget default")
                .isEqualTo(3600);
    }
}
