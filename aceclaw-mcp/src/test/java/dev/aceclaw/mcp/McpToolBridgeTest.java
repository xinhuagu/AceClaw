package dev.aceclaw.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.aceclaw.security.Capability;
import dev.aceclaw.security.DefaultPermissionPolicy;
import dev.aceclaw.security.PermissionDecision;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class McpToolBridgeTest {

    @Mock
    McpSyncClient client;

    private McpSchema.Tool mcpTool(String name, String description) {
        var schema = new McpSchema.JsonSchema("object", Map.of(), List.of(), null, null, null);
        return new McpSchema.Tool(name, null, description, schema, null, null, null);
    }

    @Test
    void createNullServerNameThrows() {
        // CodeRabbit on #495: null params used in string concatenation /
        // method calls must fail fast at the factory boundary, not produce
        // garbage like "mcp__null__do_thing".
        assertThatThrownBy(() ->
                McpToolBridge.create(null, mcpTool("do_thing", "desc"), client))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("serverName");
    }

    @Test
    void createNullMcpToolThrows() {
        assertThatThrownBy(() ->
                McpToolBridge.create("server", null, client))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("mcpTool");
    }

    @Test
    void createNullClientThrows() {
        assertThatThrownBy(() ->
                McpToolBridge.create("server", mcpTool("t", "desc"), null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("client");
    }

    @Test
    void toolNameFollowsConvention() {
        var tool = McpToolBridge.create("my-server", mcpTool("do_thing", "Does a thing"), client);

        assertThat(tool.name()).isEqualTo("mcp__my-server__do_thing");
    }

    @Test
    void descriptionPassedThrough() {
        var tool = McpToolBridge.create("s", mcpTool("t", "My description"), client);

        assertThat(tool.description()).isEqualTo("My description");
    }

    @Test
    void executeDelegatesToCallTool() throws Exception {
        var tool = McpToolBridge.create("s", mcpTool("action", "desc"), client);

        when(client.callTool(any(McpSchema.CallToolRequest.class)))
                .thenReturn(new McpSchema.CallToolResult(
                        List.of(new McpSchema.TextContent("hello")), false));

        var result = tool.execute("{\"key\": \"value\"}");

        assertThat(result.output()).isEqualTo("hello");
        assertThat(result.isError()).isFalse();
        verify(client).callTool(any(McpSchema.CallToolRequest.class));
    }

    @Test
    void errorResultSetsIsError() throws Exception {
        var tool = McpToolBridge.create("s", mcpTool("fail", "desc"), client);

        when(client.callTool(any(McpSchema.CallToolRequest.class)))
                .thenReturn(new McpSchema.CallToolResult(
                        List.of(new McpSchema.TextContent("error msg")), true));

        var result = tool.execute("{}");

        assertThat(result.isError()).isTrue();
        assertThat(result.output()).isEqualTo("error msg");
    }

    @Test
    void emptyInputHandledGracefully() throws Exception {
        var tool = McpToolBridge.create("s", mcpTool("t", "desc"), client);

        when(client.callTool(any(McpSchema.CallToolRequest.class)))
                .thenReturn(new McpSchema.CallToolResult(List.of(), false));

        var result = tool.execute("");
        assertThat(result.output()).isEmpty();
        assertThat(result.isError()).isFalse();
    }

    @Test
    void nullInputHandledGracefully() throws Exception {
        var tool = McpToolBridge.create("s", mcpTool("t", "desc"), client);

        when(client.callTool(any(McpSchema.CallToolRequest.class)))
                .thenReturn(new McpSchema.CallToolResult(List.of(), false));

        var result = tool.execute(null);
        assertThat(result.output()).isEmpty();
        assertThat(result.isError()).isFalse();
    }

    @Test
    void inputSchemaExposed() {
        var tool = McpToolBridge.create("s", mcpTool("t", "desc"), client);

        assertThat(tool.inputSchema()).isNotNull();
        assertThat(tool.inputSchema().get("type").asText()).isEqualTo("object");
    }

    @Test
    void toCapabilityProducesMcpInvokeWithServerAndMethod() {
        // McpToolBridge is now CapabilityAware (#480 PR 4 / runtime-governance):
        // it produces a structured Capability.McpInvoke so the policy and audit
        // log see (server, method) directly instead of falling back to the
        // LegacyToolUse bridge.
        var tool = McpToolBridge.create("my-server", mcpTool("do_thing", "desc"), client);

        var capability = tool.toCapability(new ObjectMapper().createObjectNode());

        assertThat(capability).isInstanceOf(Capability.McpInvoke.class);
        var invoke = (Capability.McpInvoke) capability;
        assertThat(invoke.server()).isEqualTo("my-server");
        assertThat(invoke.method()).isEqualTo("do_thing");
    }

    @Test
    void toCapabilityIgnoresArgs() {
        // For methods that don't pattern-match as file ops, args are NOT
        // carried on McpInvoke (size + secret risk). Different args produce
        // the same capability variant.
        var tool = McpToolBridge.create("s", mcpTool("opaque_method", "desc"), client);

        var mapper = new ObjectMapper();
        var cap1 = tool.toCapability(mapper.createObjectNode().put("k", "v1"));
        var cap2 = tool.toCapability(mapper.createObjectNode().put("k", "v2"));

        assertThat(cap1).isEqualTo(cap2);
        assertThat(cap1).isInstanceOf(Capability.McpInvoke.class);
    }

    // -- Best-effort file-capability inference (Codex P1 follow-up on #495) --

    @Test
    void writeFileMethodWithPathInfersFileWrite() {
        var tool = McpToolBridge.create("fs", mcpTool("write_file", "desc"), client);

        var args = new ObjectMapper().createObjectNode().put("path", "/repo/.env");
        var cap = tool.toCapability(args);

        assertThat(cap).isInstanceOf(Capability.FileWrite.class);
        assertThat(((Capability.FileWrite) cap).path()).isEqualTo(Path.of("/repo/.env"));
    }

    @Test
    void createFileMethodWithFilePathInfersFileWrite() {
        var tool = McpToolBridge.create("fs", mcpTool("create_file", "desc"), client);

        var args = new ObjectMapper().createObjectNode().put("file_path", "/tmp/x.txt");
        var cap = tool.toCapability(args);

        assertThat(cap).isInstanceOf(Capability.FileWrite.class);
    }

    @Test
    void deleteFileMethodInfersFileDelete() {
        var tool = McpToolBridge.create("fs", mcpTool("delete_file", "desc"), client);

        var args = new ObjectMapper().createObjectNode().put("path", "/repo/.env");
        var cap = tool.toCapability(args);

        assertThat(cap).isInstanceOf(Capability.FileDelete.class);
    }

    @Test
    void removeMethodInfersFileDelete() {
        var tool = McpToolBridge.create("fs", mcpTool("remove", "desc"), client);

        var args = new ObjectMapper().createObjectNode().put("path", "/tmp/x");
        var cap = tool.toCapability(args);

        assertThat(cap).isInstanceOf(Capability.FileDelete.class);
    }

    @Test
    void writeMethodWithoutPathArgFallsBackToMcpInvoke() {
        // A method with a write-shaped name but no recognized path field is
        // genuinely opaque; produce McpInvoke and let the standard prompt
        // handle it.
        var tool = McpToolBridge.create("fs", mcpTool("write_file", "desc"), client);

        var args = new ObjectMapper().createObjectNode().put("destination_uri", "x");
        var cap = tool.toCapability(args);

        assertThat(cap).isInstanceOf(Capability.McpInvoke.class);
    }

    @Test
    void opaqueMethodNameWithPathArgFallsBackToMcpInvoke() {
        // Conservative on the false-positive side: if the method name has no
        // obvious write/delete verb, do NOT promote to FileWrite even when a
        // path arg is present.
        var tool = McpToolBridge.create("fs", mcpTool("get_metadata", "desc"), client);

        var args = new ObjectMapper().createObjectNode().put("path", "/etc/hosts");
        var cap = tool.toCapability(args);

        assertThat(cap).isInstanceOf(Capability.McpInvoke.class);
    }

    @Test
    void readFileMethodFallsBackToMcpInvoke() {
        // Reads aren't promoted to FileRead - the structural rules don't cover
        // reads, and McpInvoke keeps the prompt flow identical to a generic
        // MCP call.
        var tool = McpToolBridge.create("fs", mcpTool("read_file", "desc"), client);

        var args = new ObjectMapper().createObjectNode().put("path", "/etc/hosts");
        var cap = tool.toCapability(args);

        assertThat(cap).isInstanceOf(Capability.McpInvoke.class);
    }

    @Test
    void moveFileToSensitiveDestinationInfersFileWrite() {
        // Codex P1 follow-up #2 on #495: move/rename/copy with a destination
        // field must be classified as FileWrite of the destination so the
        // structural denial sees "writing to .env".
        var tool = McpToolBridge.create("fs", mcpTool("move_file", "desc"), client);

        var args = new ObjectMapper().createObjectNode()
                .put("source", "/tmp/safe.txt")
                .put("destination", "/repo/.env");
        var cap = tool.toCapability(args);

        assertThat(cap).isInstanceOf(Capability.FileWrite.class);
        assertThat(((Capability.FileWrite) cap).path()).isEqualTo(Path.of("/repo/.env"));
    }

    @Test
    void renameWithNewPathInfersFileWrite() {
        var tool = McpToolBridge.create("fs", mcpTool("rename", "desc"), client);

        var args = new ObjectMapper().createObjectNode()
                .put("old_path", "/tmp/safe.txt")
                .put("new_path", "/repo/.env");
        var cap = tool.toCapability(args);

        assertThat(cap).isInstanceOf(Capability.FileWrite.class);
    }

    @Test
    void copyWithTargetInfersFileWrite() {
        var tool = McpToolBridge.create("fs", mcpTool("copy_file", "desc"), client);

        var args = new ObjectMapper().createObjectNode()
                .put("source", "/tmp/x")
                .put("target", "/etc/hosts");
        var cap = tool.toCapability(args);

        assertThat(cap).isInstanceOf(Capability.FileWrite.class);
    }

    @Test
    void moveFromSensitiveSourceInfersFileDelete() {
        // When destination is missing/non-resolvable but source resolves and
        // the op is a move (not copy), the source is effectively being
        // deleted — emit FileDelete so the structural layer can catch
        // attempts to move .env away.
        var tool = McpToolBridge.create("fs", mcpTool("move_file", "desc"), client);

        var args = new ObjectMapper().createObjectNode()
                .put("source", "/repo/.env");
        // No destination field.
        var cap = tool.toCapability(args);

        assertThat(cap).isInstanceOf(Capability.FileDelete.class);
        assertThat(((Capability.FileDelete) cap).path()).isEqualTo(Path.of("/repo/.env"));
    }

    @Test
    void copyFromSensitiveSourceDoesNotInferDelete() {
        // Copies leave the source intact — no FileDelete inferred.
        var tool = McpToolBridge.create("fs", mcpTool("copy_file", "desc"), client);

        var args = new ObjectMapper().createObjectNode()
                .put("source", "/repo/.env");
        var cap = tool.toCapability(args);

        // No destination resolved; not a delete (copy doesn't delete source).
        // Falls through to McpInvoke.
        assertThat(cap).isInstanceOf(Capability.McpInvoke.class);
    }

    @Test
    void moveFromSensitiveSourceWithSafeDestinationInfersFileDelete() {
        // Codex P1 second follow-up on #495: when destination is safe but
        // source is sensitive, the "destination wins" rule used to emit
        // FileWrite(safe-dest) and the structural layer would never see the
        // sensitive source being deleted. Source-sensitivity is now probed
        // explicitly so the structural denial fires on the delete side.
        var tool = McpToolBridge.create("fs", mcpTool("move_file", "desc"), client);

        var args = new ObjectMapper().createObjectNode()
                .put("source", "/repo/.env")
                .put("destination", "/tmp/env-backup.txt");
        var cap = tool.toCapability(args);

        assertThat(cap)
                .as("safe-dst + sensitive-src move must produce FileDelete to trigger denial")
                .isInstanceOf(Capability.FileDelete.class);
        assertThat(((Capability.FileDelete) cap).path()).isEqualTo(Path.of("/repo/.env"));
    }

    @Test
    void copyFromSensitiveSourceWithSafeDestinationStaysAsFileWrite() {
        // Copies don't delete the source - the source-sensitivity probe only
        // kicks in for moves. A copy from .env to safe-dest still emits
        // FileWrite(safe-dest) (the destination side), gets the standard
        // prompt - no source-side denial because the source is left intact.
        var tool = McpToolBridge.create("fs", mcpTool("copy_file", "desc"), client);

        var args = new ObjectMapper().createObjectNode()
                .put("source", "/repo/.env")
                .put("destination", "/tmp/env-copy.txt");
        var cap = tool.toCapability(args);

        assertThat(cap).isInstanceOf(Capability.FileWrite.class);
        assertThat(((Capability.FileWrite) cap).path()).isEqualTo(Path.of("/tmp/env-copy.txt"));
    }

    @Test
    void benignMoveWithSafeBothSidesEmitsFileDeleteToPreserveDangerousRisk() {
        // Codex P2 on #495: a benign move emitted FileWrite(dst) (WRITE risk)
        // which auto-approves in accept-edits mode, silently removing the
        // source. Pre-#495 MCP moves prompted via EXECUTE risk. To match
        // that floor — and since the move semantics ARE a delete of the
        // source — emit FileDelete(src) instead. FileDelete is DANGEROUS,
        // so it prompts in every mode except auto-accept.
        var tool = McpToolBridge.create("fs", mcpTool("move_file", "desc"), client);

        var args = new ObjectMapper().createObjectNode()
                .put("source", "/tmp/a.txt")
                .put("destination", "/tmp/b.txt");
        var cap = tool.toCapability(args);

        assertThat(cap).isInstanceOf(Capability.FileDelete.class);
        assertThat(((Capability.FileDelete) cap).path()).isEqualTo(Path.of("/tmp/a.txt"));
        // Sanity: FileDelete is DANGEROUS, so accept-edits won't auto-approve.
        assertThat(cap.risk()).isEqualTo(dev.aceclaw.security.PermissionLevel.DANGEROUS);
    }

    @Test
    void benignCopyWithSafeBothSidesEmitsFileWrite() {
        // Copies don't delete the source, so the WRITE risk on the
        // destination is honest — accept-edits auto-approval is fine.
        var tool = McpToolBridge.create("fs", mcpTool("copy_file", "desc"), client);

        var args = new ObjectMapper().createObjectNode()
                .put("source", "/tmp/a.txt")
                .put("destination", "/tmp/b.txt");
        var cap = tool.toCapability(args);

        assertThat(cap).isInstanceOf(Capability.FileWrite.class);
        assertThat(((Capability.FileWrite) cap).path()).isEqualTo(Path.of("/tmp/b.txt"));
    }

    @Test
    void writeVerbSuffixShortFormMatches() {
        // CodeRabbit on #495: short-form suffixes (foo_put, foo_rm) were
        // missing from the suffix alternation; foo_put is a clear write,
        // it should be classified.
        var tool = McpToolBridge.create("fs", mcpTool("blob_put", "desc"), client);

        var args = new ObjectMapper().createObjectNode().put("path", "/tmp/x");
        var cap = tool.toCapability(args);

        assertThat(cap).isInstanceOf(Capability.FileWrite.class);
    }

    @Test
    void deleteVerbSuffixShortFormMatches() {
        var tool = McpToolBridge.create("fs", mcpTool("blob_rm", "desc"), client);

        var args = new ObjectMapper().createObjectNode().put("path", "/tmp/x");
        var cap = tool.toCapability(args);

        assertThat(cap).isInstanceOf(Capability.FileDelete.class);
    }

    @Test
    void moveVerbSuffixShortFormMatches() {
        var tool = McpToolBridge.create("fs", mcpTool("blob_mv", "desc"), client);

        var args = new ObjectMapper().createObjectNode()
                .put("source", "/tmp/a")
                .put("destination", "/tmp/b");
        var cap = tool.toCapability(args);

        // Move semantics → FileDelete(source) for the dangerous risk.
        assertThat(cap).isInstanceOf(Capability.FileDelete.class);
    }

    @Test
    void copyVerbSuffixShortFormMatches() {
        var tool = McpToolBridge.create("fs", mcpTool("blob_cp", "desc"), client);

        var args = new ObjectMapper().createObjectNode()
                .put("source", "/tmp/a")
                .put("destination", "/tmp/b");
        var cap = tool.toCapability(args);

        assertThat(cap).isInstanceOf(Capability.FileWrite.class);
    }

    @Test
    void camelCasePathFieldMatches() {
        // Codex P2 on #495: many MCP servers use camelCase JSON keys
        // (filePath rather than file_path). Field-name normalization
        // (lowercase + strip underscores) makes both match the same
        // canonical entry.
        var tool = McpToolBridge.create("fs", mcpTool("write_file", "desc"), client);

        var args = new ObjectMapper().createObjectNode().put("filePath", "/repo/.env");
        var cap = tool.toCapability(args);

        assertThat(cap).isInstanceOf(Capability.FileWrite.class);
        assertThat(((Capability.FileWrite) cap).path()).isEqualTo(Path.of("/repo/.env"));
    }

    @Test
    void camelCaseDestinationFieldMatches() {
        var tool = McpToolBridge.create("fs", mcpTool("move_file", "desc"), client);

        var args = new ObjectMapper().createObjectNode()
                .put("sourcePath", "/tmp/a")
                .put("newPath", "/repo/.env");
        var cap = tool.toCapability(args);

        assertThat(cap)
                .as("camelCase newPath should still trigger destination-sensitive denial")
                .isInstanceOf(Capability.FileWrite.class);
        assertThat(((Capability.FileWrite) cap).path()).isEqualTo(Path.of("/repo/.env"));
    }

    @Test
    void uppercaseFieldNameMatches() {
        // Defensive: a server using PASCALCASE or SCREAMING_SNAKE shouldn't
        // bypass the inference either.
        var tool = McpToolBridge.create("fs", mcpTool("write_file", "desc"), client);

        var args = new ObjectMapper().createObjectNode().put("FILEPATH", "/repo/.env");
        var cap = tool.toCapability(args);

        assertThat(cap).isInstanceOf(Capability.FileWrite.class);
    }

    @Test
    void writeToDestinationFieldStillInfersFileWrite() {
        // Self-review preemptive fix: a write-verb method that uses a
        // destination-style field (`write_to_destination(destination=...)`)
        // instead of `path` should still emit FileWrite. The WRITE_VERB
        // branch cascades to DESTINATION_FIELDS when PATH_FIELDS is empty.
        var tool = McpToolBridge.create("fs", mcpTool("write_to", "desc"), client);

        var args = new ObjectMapper().createObjectNode().put("destination", "/repo/.env");
        var cap = tool.toCapability(args);

        assertThat(cap).isInstanceOf(Capability.FileWrite.class);
        assertThat(((Capability.FileWrite) cap).path()).isEqualTo(Path.of("/repo/.env"));
    }

    @Test
    void mcpMoveToSensitiveDestinationIsStructurallyDenied() {
        // End-to-end: move to .env composes through to a structural denial.
        var tool = McpToolBridge.create("fs", mcpTool("move_file", "desc"), client);
        var args = new ObjectMapper().createObjectNode()
                .put("source", "/tmp/safe.txt")
                .put("destination", "/repo/.env");

        var cap = tool.toCapability(args);
        var decision = new DefaultPermissionPolicy("auto-accept").evaluateStructural(cap);

        assertThat(decision).isNotNull();
        assertThat(decision.reason()).contains("sensitive path");
    }

    @Test
    void mcpFileWriteToSensitivePathIsStructurallyDenied() {
        // End-to-end: an MCP server's write_file(path=".env") composes through
        // McpToolBridge.toCapability() into a Capability.FileWrite that the
        // structural-denial layer refuses. Pins that the wiring between the
        // MCP boundary and DefaultPermissionPolicy works for the bypass Codex
        // P1 flagged (#495).
        var tool = McpToolBridge.create("fs", mcpTool("write_file", "desc"), client);
        var args = new ObjectMapper().createObjectNode().put("path", "/repo/.env");

        var cap = tool.toCapability(args);
        var decision = new DefaultPermissionPolicy("auto-accept").evaluateStructural(cap);

        assertThat(decision)
                .as("MCP-driven write to .env must be structurally denied even in auto-accept")
                .isNotNull();
        assertThat(decision.reason()).contains("sensitive path");
    }
}
