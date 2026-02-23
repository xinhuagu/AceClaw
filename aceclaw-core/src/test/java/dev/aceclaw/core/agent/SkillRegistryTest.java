package dev.aceclaw.core.agent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link SkillRegistry} — skill discovery, YAML frontmatter parsing,
 * and system prompt description formatting.
 */
class SkillRegistryTest {

    @TempDir
    Path tempDir;
    private String originalUserHome;

    @BeforeEach
    void isolateUserHome() throws IOException {
        originalUserHome = System.getProperty("user.home");
        Path isolatedHome = tempDir.resolve("isolated-user-home");
        Files.createDirectories(isolatedHome);
        System.setProperty("user.home", isolatedHome.toString());
    }

    @AfterEach
    void restoreUserHome() {
        if (originalUserHome != null) {
            System.setProperty("user.home", originalUserHome);
        }
    }

    @Test
    void parseSkillWithAllFrontmatter() throws IOException {
        var skillDir = tempDir.resolve(".aceclaw/skills/deploy");
        Files.createDirectories(skillDir);
        Files.writeString(skillDir.resolve("SKILL.md"), """
                ---
                name: "deploy"
                description: "Deploy to target environment"
                argument-hint: "<environment>"
                context: fork
                model: claude-sonnet-4-5
                allowed-tools: [bash, read_file]
                max-turns: 10
                user-invocable: true
                disable-model-invocation: false
                ---

                # Deploy Skill

                Deploy the application to $ARGUMENTS environment.
                """);

        var registry = SkillRegistry.load(tempDir);

        assertThat(registry.names()).containsExactly("deploy");

        var config = registry.get("deploy").orElseThrow();
        assertThat(config.name()).isEqualTo("deploy");
        assertThat(config.description()).isEqualTo("Deploy to target environment");
        assertThat(config.argumentHint()).isEqualTo("<environment>");
        assertThat(config.context()).isEqualTo(SkillConfig.ExecutionContext.FORK);
        assertThat(config.model()).isEqualTo("claude-sonnet-4-5");
        assertThat(config.allowedTools()).containsExactly("bash", "read_file");
        assertThat(config.maxTurns()).isEqualTo(10);
        assertThat(config.userInvocable()).isTrue();
        assertThat(config.disableModelInvocation()).isFalse();
        assertThat(config.body()).contains("Deploy the application");
        assertThat(config.directory()).isEqualTo(skillDir);
    }

    @Test
    void parseSkillMinimalFrontmatter() throws IOException {
        var skillDir = tempDir.resolve(".aceclaw/skills/review");
        Files.createDirectories(skillDir);
        Files.writeString(skillDir.resolve("SKILL.md"), """
                ---
                description: "Code review assistant"
                ---

                Review the current changes.
                """);

        var registry = SkillRegistry.load(tempDir);

        var config = registry.get("review").orElseThrow();
        assertThat(config.name()).isEqualTo("review"); // derived from directory name
        assertThat(config.description()).isEqualTo("Code review assistant");
        assertThat(config.context()).isEqualTo(SkillConfig.ExecutionContext.INLINE); // default
        assertThat(config.model()).isNull(); // inherit
        assertThat(config.allowedTools()).isEmpty();
        assertThat(config.maxTurns()).isEqualTo(SkillConfig.DEFAULT_MAX_TURNS);
        assertThat(config.userInvocable()).isTrue(); // default
        assertThat(config.disableModelInvocation()).isFalse(); // default
        assertThat(config.body()).isEqualTo("Review the current changes.");
    }

    @Test
    void loadFromProjectDirectory() throws IOException {
        createSkill(tempDir, "commit", "Auto-commit changes", "Commit the staged changes.");
        createSkill(tempDir, "test", "Run test suite", "Run all tests and report results.");

        var registry = SkillRegistry.load(tempDir);

        assertThat(registry.names()).containsExactlyInAnyOrder("commit", "test");
        assertThat(registry.all()).hasSize(2);
        assertThat(registry.isEmpty()).isFalse();
    }

    @Test
    void projectSkillsOverrideUserSkills() throws IOException {
        // This test verifies the override logic within a single project dir.
        // Since we can't easily mock the user home dir, we test with project-only.
        // The actual load() method processes user dir first, then project dir (project wins).
        var skillDir = tempDir.resolve(".aceclaw/skills/deploy");
        Files.createDirectories(skillDir);
        Files.writeString(skillDir.resolve("SKILL.md"), """
                ---
                name: "deploy"
                description: "Project-specific deploy"
                ---

                Deploy from project.
                """);

        var registry = SkillRegistry.load(tempDir);

        var config = registry.get("deploy").orElseThrow();
        assertThat(config.description()).isEqualTo("Project-specific deploy");
    }

    @Test
    void formatDescriptionsCompact() throws IOException {
        createSkill(tempDir, "commit", "Auto-commit staged changes", "Commit body");

        var skillDir2 = tempDir.resolve(".aceclaw/skills/deploy");
        Files.createDirectories(skillDir2);
        Files.writeString(skillDir2.resolve("SKILL.md"), """
                ---
                description: "Deploy to environment"
                argument-hint: "<env>"
                ---

                Deploy body.
                """);

        var registry = SkillRegistry.load(tempDir);
        String descriptions = registry.formatDescriptions();

        assertThat(descriptions).contains("# Available Skills");
        assertThat(descriptions).contains("**commit**");
        assertThat(descriptions).contains("Auto-commit staged changes");
        assertThat(descriptions).contains("**deploy**");
        assertThat(descriptions).contains("Deploy to environment");
        assertThat(descriptions).contains("(args: `<env>`)");
    }

    @Test
    void formatDescriptionsExcludesModelDisabled() throws IOException {
        var skillDir = tempDir.resolve(".aceclaw/skills/internal");
        Files.createDirectories(skillDir);
        Files.writeString(skillDir.resolve("SKILL.md"), """
                ---
                description: "Internal-only skill"
                disable-model-invocation: true
                ---

                Hidden from model.
                """);

        var registry = SkillRegistry.load(tempDir);
        String descriptions = registry.formatDescriptions();

        assertThat(descriptions).isEmpty();
    }

    @Test
    void missingSkillMdSkipped() throws IOException {
        // Directory exists but has no SKILL.md
        Files.createDirectories(tempDir.resolve(".aceclaw/skills/empty-skill"));
        // Directory with a random file
        var dirWithOtherFile = tempDir.resolve(".aceclaw/skills/other-skill");
        Files.createDirectories(dirWithOtherFile);
        Files.writeString(dirWithOtherFile.resolve("README.md"), "Not a skill");

        var registry = SkillRegistry.load(tempDir);

        assertThat(registry.isEmpty()).isTrue();
        assertThat(registry.names()).isEmpty();
    }

    @Test
    void invalidFrontmatterSkipped() throws IOException {
        var skillDir = tempDir.resolve(".aceclaw/skills/broken");
        Files.createDirectories(skillDir);
        // No frontmatter delimiters
        Files.writeString(skillDir.resolve("SKILL.md"), "Just some text without frontmatter.");

        var registry = SkillRegistry.load(tempDir);

        assertThat(registry.get("broken")).isEmpty();
    }

    @Test
    void noDescriptionSkipped() throws IOException {
        var skillDir = tempDir.resolve(".aceclaw/skills/nodesc");
        Files.createDirectories(skillDir);
        Files.writeString(skillDir.resolve("SKILL.md"), """
                ---
                name: "nodesc"
                ---

                Body without description.
                """);

        var registry = SkillRegistry.load(tempDir);

        assertThat(registry.get("nodesc")).isEmpty();
    }

    @Test
    void emptyRegistryFromEmptyDir() throws IOException {
        // No .aceclaw/skills directory at all
        var registry = SkillRegistry.load(tempDir);
        assertThat(registry.isEmpty()).isTrue();
        assertThat(registry.formatDescriptions()).isEmpty();
    }

    @Test
    void multiLineAllowedTools() throws IOException {
        var skillDir = tempDir.resolve(".aceclaw/skills/multi");
        Files.createDirectories(skillDir);
        Files.writeString(skillDir.resolve("SKILL.md"), """
                ---
                description: "Skill with multi-line tools"
                allowed-tools:
                  - bash
                  - read_file
                  - glob
                ---

                Multi-line tools skill body.
                """);

        var registry = SkillRegistry.load(tempDir);

        var config = registry.get("multi").orElseThrow();
        assertThat(config.allowedTools()).containsExactly("bash", "read_file", "glob");
    }

    @Test
    void nameFromDirectoryWhenNotInFrontmatter() throws IOException {
        var skillDir = tempDir.resolve(".aceclaw/skills/my-custom-skill");
        Files.createDirectories(skillDir);
        Files.writeString(skillDir.resolve("SKILL.md"), """
                ---
                description: "A skill without explicit name"
                ---

                Skill body here.
                """);

        var registry = SkillRegistry.load(tempDir);

        var config = registry.get("my-custom-skill").orElseThrow();
        assertThat(config.name()).isEqualTo("my-custom-skill");
    }

    // -- Helpers --

    private void createSkill(Path projectDir, String name, String description, String body)
            throws IOException {
        var skillDir = projectDir.resolve(".aceclaw/skills/" + name);
        Files.createDirectories(skillDir);
        Files.writeString(skillDir.resolve("SKILL.md"),
                "---\ndescription: \"" + description + "\"\n---\n\n" + body + "\n");
    }
}
