package dev.aceclaw.daemon;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class AceClawConfigPersistenceTest {

    @TempDir
    Path tempDir;

    @Test
    void persistCandidateInjectionSettingsWritesProjectConfig() throws Exception {
        var written = AceClawConfig.persistCandidateInjectionSettings(
                tempDir, false, 77, "project");
        assertThat(written).isEqualTo(tempDir.resolve(".aceclaw").resolve("config.json"));
        assertThat(written).exists();

        var mapper = new ObjectMapper();
        var root = mapper.readTree(Files.readString(written));
        assertThat(root.get("candidateInjectionEnabled").asBoolean()).isFalse();
        assertThat(root.get("candidateInjectionMaxTokens").asInt()).isEqualTo(77);
    }
}

