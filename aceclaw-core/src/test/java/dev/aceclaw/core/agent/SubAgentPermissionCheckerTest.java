package dev.aceclaw.core.agent;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class SubAgentPermissionCheckerTest {

    private static final Set<String> READ_ONLY = Set.of("read_file", "glob", "grep", "memory");

    @Test
    void readOnlyToolsAreAllowed() {
        var checker = new SubAgentPermissionChecker(READ_ONLY, _ -> false);

        assertThat(checker.check("read_file", "{}").allowed()).isTrue();
        assertThat(checker.check("glob", "{\"pattern\":\"*.java\"}").allowed()).isTrue();
        assertThat(checker.check("grep", "{}").allowed()).isTrue();
        assertThat(checker.check("memory", "{}").allowed()).isTrue();
    }

    @Test
    void sessionApprovedToolsAreAllowed() {
        var approved = new HashSet<String>();
        approved.add("write_file");
        var checker = new SubAgentPermissionChecker(READ_ONLY, approved::contains);

        assertThat(checker.check("write_file", "{}").allowed()).isTrue();
    }

    @Test
    void unapprovedWriteToolsDenied() {
        var checker = new SubAgentPermissionChecker(READ_ONLY, _ -> false);

        var result = checker.check("write_file", "{}");
        assertThat(result.allowed()).isFalse();
        assertThat(result.reason()).contains("write_file");
        assertThat(result.reason()).contains("session approval");
    }

    @Test
    void unapprovedExecuteToolsDenied() {
        var checker = new SubAgentPermissionChecker(READ_ONLY, _ -> false);

        var result = checker.check("bash", "{\"command\":\"rm -rf /\"}");
        assertThat(result.allowed()).isFalse();
        assertThat(result.reason()).contains("bash");
    }
}
