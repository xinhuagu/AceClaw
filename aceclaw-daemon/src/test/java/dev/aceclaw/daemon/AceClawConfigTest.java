package dev.aceclaw.daemon;

import org.junit.jupiter.api.Test;

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
}
