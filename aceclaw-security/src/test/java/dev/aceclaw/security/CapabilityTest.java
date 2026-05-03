package dev.aceclaw.security;

import dev.aceclaw.security.ids.MemoryKey;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pins the derived attributes ({@code risk}, {@code dataFlow},
 * {@code displayLabel}) for every {@link Capability} variant — so a refactor
 * that accidentally changes the risk class of, say, {@code FileWrite} from
 * {@code WRITE} to {@code READ} fails this test instead of silently turning
 * off auto-prompting in production.
 *
 * <p>The {@link #exhaustivenessSentinel} switch has no {@code default} branch:
 * if a new {@link Capability} variant is added without updating the sentinel,
 * the build fails before this test runs. That's the compile-time half of
 * "every consumer must handle every variant" — runtime half is each consumer's
 * own switch.
 */
final class CapabilityTest {

    @Test
    void fileReadDerivesReadIngress() {
        var cap = new Capability.FileRead(Path.of("/etc/hosts"));

        assertThat(cap.risk()).isEqualTo(PermissionLevel.READ);
        assertThat(cap.dataFlow()).isEqualTo(DataFlow.INGRESS);
        assertThat(cap.displayLabel()).isEqualTo("read /etc/hosts");
    }

    @Test
    void fileWriteDerivesWriteEgressIncludingMode() {
        var cap = new Capability.FileWrite(Path.of("/tmp/x"), WriteMode.OVERWRITE);

        assertThat(cap.risk()).isEqualTo(PermissionLevel.WRITE);
        assertThat(cap.dataFlow()).isEqualTo(DataFlow.EGRESS);
        assertThat(cap.displayLabel()).contains("/tmp/x").contains("OVERWRITE");
    }

    @Test
    void fileDeleteDerivesWriteEgress() {
        var cap = new Capability.FileDelete(Path.of("/tmp/x"));

        assertThat(cap.risk()).isEqualTo(PermissionLevel.WRITE);
        assertThat(cap.dataFlow()).isEqualTo(DataFlow.EGRESS);
    }

    @Test
    void bashExecDerivesExecuteBoth() {
        var cap = new Capability.BashExec("ls -la", Path.of("/tmp"));

        assertThat(cap.risk()).isEqualTo(PermissionLevel.EXECUTE);
        assertThat(cap.dataFlow()).isEqualTo(DataFlow.BOTH);
        assertThat(cap.displayLabel()).contains("ls -la");
    }

    @Test
    void httpFetchGetIsIngressOnly() {
        var cap = new Capability.HttpFetch(URI.create("https://example.com"), "GET");

        assertThat(cap.dataFlow())
                .as("safe HTTP methods upload no body")
                .isEqualTo(DataFlow.INGRESS);
    }

    @Test
    void httpFetchPostIsBidirectional() {
        var cap = new Capability.HttpFetch(URI.create("https://example.com"), "POST");

        assertThat(cap.dataFlow()).isEqualTo(DataFlow.BOTH);
    }

    @Test
    void mcpInvokeDerivesExecute() {
        var cap = new Capability.McpInvoke("filesystem", "read_file");

        assertThat(cap.risk()).isEqualTo(PermissionLevel.EXECUTE);
        assertThat(cap.displayLabel()).isEqualTo("mcp filesystem.read_file");
    }

    @Test
    void memoryWriteDerivesWriteEgress() {
        var cap = new Capability.MemoryWrite(new MemoryKey("user/preferences"), "user");

        assertThat(cap.risk()).isEqualTo(PermissionLevel.WRITE);
        assertThat(cap.dataFlow()).isEqualTo(DataFlow.EGRESS);
        assertThat(cap.displayLabel())
                .as("label includes both tier and key for at-a-glance readability")
                .contains("user").contains("user/preferences");
    }

    @Test
    void memoryReadDerivesReadIngress() {
        var cap = new Capability.MemoryRead(new MemoryKey("user/preferences"));

        assertThat(cap.risk()).isEqualTo(PermissionLevel.READ);
        assertThat(cap.dataFlow()).isEqualTo(DataFlow.INGRESS);
    }

    @Test
    void subAgentSpawnRecordsParentDepth() {
        var cap = new Capability.SubAgentSpawn("planner", 2);

        assertThat(cap.risk()).isEqualTo(PermissionLevel.EXECUTE);
        assertThat(cap.displayLabel())
                .contains("role=planner")
                .contains("depth=3"); // parent depth + 1
    }

    @Test
    void subAgentSpawnRejectsNegativeDepth() {
        // Negative parentDepth is impossible in real code — guard catches
        // bugs that would otherwise silently produce nonsense audit entries.
        assertThatThrownBy(() -> new Capability.SubAgentSpawn("planner", -1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("parentDepth");
    }

    @Test
    void legacyToolUseInheritsDeclaredLevel() {
        var cap = new Capability.LegacyToolUse("write_file", PermissionLevel.WRITE);

        assertThat(cap.risk())
                .as("legacy entries can't re-derive risk; they replay what was recorded")
                .isEqualTo(PermissionLevel.WRITE);
        assertThat(cap.dataFlow()).isEqualTo(DataFlow.EGRESS);
        assertThat(cap.displayLabel()).startsWith("legacy:");
    }

    @Test
    void allVariantsRejectNullFields() {
        // Sample one field per variant — the canonical pattern is the same
        // (Objects.requireNonNull in the canonical constructor) so spot-check
        // is enough; pinning every field × variant is just typing.
        assertThatThrownBy(() -> new Capability.FileRead(null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new Capability.FileWrite(null, WriteMode.OVERWRITE))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new Capability.FileWrite(Path.of("/x"), null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new Capability.BashExec(null, Path.of("/")))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new Capability.HttpFetch(null, "GET"))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new Capability.MemoryWrite(null, "user"))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void exhaustivenessSentinelHandlesEveryVariant() {
        // The sentinel switch below has NO default branch. Adding a new
        // Capability variant without updating it fails compilation before
        // this test runs — that's the foundation guarantee. The test merely
        // exercises one path so the method stays live.
        Capability c = new Capability.FileRead(Path.of("/x"));
        assertThat(exhaustivenessSentinel(c)).isNotBlank();
    }

    private static String exhaustivenessSentinel(Capability c) {
        return switch (c) {
            case Capability.FileRead r -> r.displayLabel();
            case Capability.FileWrite w -> w.displayLabel();
            case Capability.FileDelete d -> d.displayLabel();
            case Capability.BashExec b -> b.displayLabel();
            case Capability.HttpFetch h -> h.displayLabel();
            case Capability.McpInvoke m -> m.displayLabel();
            case Capability.MemoryWrite mw -> mw.displayLabel();
            case Capability.MemoryRead mr -> mr.displayLabel();
            case Capability.SubAgentSpawn s -> s.displayLabel();
            case Capability.LegacyToolUse l -> l.displayLabel();
        };
    }
}
