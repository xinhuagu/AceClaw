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

    // -- Move/rename/copy: now emit Capability.FileMove ---------------------
    // Round-16 follow-up to #495: previously these emitted FileWrite or
    // FileDelete to coerce the structural-denial layer into checking the
    // right side. Now the structured FileMove(src, dst, deletesSource)
    // carries both endpoints, the policy checks both, and the audit log
    // accurately reflects "this was a move/copy" instead of pretending
    // it was a delete.

    @Test
    void moveFileBothEndpointsInferFileMove() {
        var tool = McpToolBridge.create("fs", mcpTool("move_file", "desc"), client);

        var args = new ObjectMapper().createObjectNode()
                .put("source", "/tmp/safe.txt")
                .put("destination", "/repo/.env");
        var cap = tool.toCapability(args);

        assertThat(cap).isInstanceOf(Capability.FileMove.class);
        var fm = (Capability.FileMove) cap;
        assertThat(fm.source()).isEqualTo(Path.of("/tmp/safe.txt"));
        assertThat(fm.destination()).isEqualTo(Path.of("/repo/.env"));
        assertThat(fm.deletesSource())
                .as("move ops delete the source")
                .isTrue();
    }

    @Test
    void renameInfersFileMoveWithDeletesSource() {
        var tool = McpToolBridge.create("fs", mcpTool("rename", "desc"), client);

        var args = new ObjectMapper().createObjectNode()
                .put("old_path", "/tmp/safe.txt")
                .put("new_path", "/repo/.env");
        var cap = tool.toCapability(args);

        assertThat(cap).isInstanceOf(Capability.FileMove.class);
        assertThat(((Capability.FileMove) cap).deletesSource()).isTrue();
    }

    @Test
    void copyInfersFileMoveWithoutDeletesSource() {
        var tool = McpToolBridge.create("fs", mcpTool("copy_file", "desc"), client);

        var args = new ObjectMapper().createObjectNode()
                .put("source", "/tmp/x")
                .put("target", "/etc/hosts");
        var cap = tool.toCapability(args);

        assertThat(cap).isInstanceOf(Capability.FileMove.class);
        assertThat(((Capability.FileMove) cap).deletesSource())
                .as("copies do not delete the source")
                .isFalse();
    }

    @Test
    void renameWithPathAsSourceInfersFileMove() {
        // Codex P1 follow-up to #495: some MCP filesystem schemas use the
        // single `path` field for the existing file on a rename/move (with
        // the new name in `new_path`/`destination`). Without falling back to
        // PATH_FIELDS as a source alias on moves, `src` resolves to null and
        // the call degrades to FileWrite(dst) — bypassing the sensitive-
        // source structural check.
        var tool = McpToolBridge.create("fs", mcpTool("rename", "desc"), client);

        var args = new ObjectMapper().createObjectNode()
                .put("path", "/repo/.env")
                .put("new_path", "/tmp/env.bak");
        var cap = tool.toCapability(args);

        assertThat(cap).isInstanceOf(Capability.FileMove.class);
        var fm = (Capability.FileMove) cap;
        assertThat(fm.source()).isEqualTo(Path.of("/repo/.env"));
        assertThat(fm.destination()).isEqualTo(Path.of("/tmp/env.bak"));
        assertThat(fm.deletesSource()).isTrue();
    }

    @Test
    void explicitSourceWinsOverPathOnMove() {
        // PATH_FIELDS is only a fallback. If both `source` and `path` are
        // present (unusual but possible with a permissive MCP schema), the
        // explicit `source` should be honored — otherwise a server could
        // hide the real source under `path` and mask it from the audit log.
        var tool = McpToolBridge.create("fs", mcpTool("move_file", "desc"), client);

        var args = new ObjectMapper().createObjectNode()
                .put("source", "/tmp/explicit-src")
                .put("path", "/repo/.env")
                .put("destination", "/tmp/dst");
        var cap = tool.toCapability(args);

        assertThat(cap).isInstanceOf(Capability.FileMove.class);
        assertThat(((Capability.FileMove) cap).source())
                .isEqualTo(Path.of("/tmp/explicit-src"));
    }

    @Test
    void moveSourceOnlyDegradesToFileDelete() {
        // When destination is missing (malformed args), a move still tells
        // us the source is being removed. Emit FileDelete so the structural
        // layer can catch attempts to move .env away.
        var tool = McpToolBridge.create("fs", mcpTool("move_file", "desc"), client);

        var args = new ObjectMapper().createObjectNode()
                .put("source", "/repo/.env");
        var cap = tool.toCapability(args);

        assertThat(cap).isInstanceOf(Capability.FileDelete.class);
        assertThat(((Capability.FileDelete) cap).path()).isEqualTo(Path.of("/repo/.env"));
    }

    @Test
    void copySourceOnlyFallsBackToMcpInvoke() {
        // A copy with no destination is a malformed call (nothing is being
        // created). FileRead wouldn't trigger structural rules anyway, so
        // fall back to McpInvoke for the standard prompt.
        var tool = McpToolBridge.create("fs", mcpTool("copy_file", "desc"), client);

        var args = new ObjectMapper().createObjectNode()
                .put("source", "/repo/.env");
        var cap = tool.toCapability(args);

        assertThat(cap).isInstanceOf(Capability.McpInvoke.class);
    }

    @Test
    void moveDestinationOnlyDegradesToFileWrite() {
        // Destination resolves but source doesn't — emit FileWrite of the
        // destination since that's the visible side of the operation.
        var tool = McpToolBridge.create("fs", mcpTool("move_file", "desc"), client);

        var args = new ObjectMapper().createObjectNode()
                .put("destination", "/repo/.env");
        var cap = tool.toCapability(args);

        assertThat(cap).isInstanceOf(Capability.FileWrite.class);
        assertThat(((Capability.FileWrite) cap).path()).isEqualTo(Path.of("/repo/.env"));
    }

    @Test
    void fileMoveRiskReflectsDeletesSource() {
        // Sanity: FileMove(deletesSource=true) is DANGEROUS so accept-edits
        // mode prompts. FileMove(deletesSource=false) is WRITE — copies don't
        // remove the source so the lower risk is honest.
        var tool = McpToolBridge.create("fs", mcpTool("move_file", "desc"), client);
        var moveArgs = new ObjectMapper().createObjectNode()
                .put("source", "/tmp/a")
                .put("destination", "/tmp/b");
        assertThat(tool.toCapability(moveArgs).risk())
                .isEqualTo(dev.aceclaw.security.PermissionLevel.DANGEROUS);

        var copyTool = McpToolBridge.create("fs", mcpTool("copy_file", "desc"), client);
        var copyArgs = new ObjectMapper().createObjectNode()
                .put("source", "/tmp/a")
                .put("destination", "/tmp/b");
        assertThat(copyTool.toCapability(copyArgs).risk())
                .isEqualTo(dev.aceclaw.security.PermissionLevel.WRITE);
    }

    @Test
    void moveToSensitiveDestinationIsStructurallyDenied() {
        // End-to-end: FileMove with sensitive dst → structural denial fires
        // on the destination side.
        var tool = McpToolBridge.create("fs", mcpTool("move_file", "desc"), client);
        var args = new ObjectMapper().createObjectNode()
                .put("source", "/tmp/safe.txt")
                .put("destination", "/repo/.env");

        var cap = tool.toCapability(args);
        var decision = new DefaultPermissionPolicy("auto-accept", /* denySensitivePaths */ true).evaluateStructural(cap);

        assertThat(decision).isNotNull();
    }

    @Test
    void moveFromSensitiveSourceIsStructurallyDenied() {
        // End-to-end: FileMove with sensitive src + safe dst → structural
        // denial fires on the source side because the move removes the
        // sensitive file.
        var tool = McpToolBridge.create("fs", mcpTool("move_file", "desc"), client);
        var args = new ObjectMapper().createObjectNode()
                .put("source", "/repo/.env")
                .put("destination", "/tmp/env-backup.txt");

        var cap = tool.toCapability(args);
        var decision = new DefaultPermissionPolicy("auto-accept", /* denySensitivePaths */ true).evaluateStructural(cap);

        assertThat(decision)
                .as("removing sensitive source via move must be denied")
                .isNotNull();
    }

    @Test
    void copyFromSensitiveSourceToSafeDestinationIsStructurallyDeniedAsExfil() {
        // Credential exfiltration: copy_file(.env, /tmp/x) duplicates a
        // sensitive file into a non-sensitive location. FileMove carries
        // both endpoints so the policy can refuse the read side.
        var tool = McpToolBridge.create("fs", mcpTool("copy_file", "desc"), client);
        var args = new ObjectMapper().createObjectNode()
                .put("source", "/repo/.env")
                .put("destination", "/tmp/env-copy.txt");

        var cap = tool.toCapability(args);
        assertThat(cap).isInstanceOf(Capability.FileMove.class);
        assertThat(((Capability.FileMove) cap).deletesSource())
                .as("copy semantics preserved -- this is FileMove(deletesSource=false)")
                .isFalse();

        var decision = new DefaultPermissionPolicy("auto-accept", /* denySensitivePaths */ true).evaluateStructural(cap);
        assertThat(decision)
                .as("copy from sensitive source must be denied even in auto-accept")
                .isNotNull();
    }

    @Test
    void benignMoveBothSidesEmitsFileMoveNotDenied() {
        // No sensitive paths on either side — structural denial returns null,
        // FileMove(deletesSource=true) bubbles up to the mode-based check.
        // Audit log accurately records @type=FileMove rather than the old
        // FileDelete coercion.
        var tool = McpToolBridge.create("fs", mcpTool("move_file", "desc"), client);
        var args = new ObjectMapper().createObjectNode()
                .put("source", "/tmp/a.txt")
                .put("destination", "/tmp/b.txt");

        var cap = tool.toCapability(args);

        assertThat(cap).isInstanceOf(Capability.FileMove.class);
        // Layer enabled here so the "returns null on benign paths" assertion
        // actually tests the rule, not the toggle's off-state.
        var decision = new DefaultPermissionPolicy("normal", /* denySensitivePaths */ true)
                .evaluateStructural(cap);
        assertThat(decision)
                .as("no sensitive paths -> structural denial returns null")
                .isNull();
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

        assertThat(cap).isInstanceOf(Capability.FileMove.class);
        assertThat(((Capability.FileMove) cap).deletesSource()).isTrue();
    }

    @Test
    void copyVerbSuffixShortFormMatches() {
        var tool = McpToolBridge.create("fs", mcpTool("blob_cp", "desc"), client);

        var args = new ObjectMapper().createObjectNode()
                .put("source", "/tmp/a")
                .put("destination", "/tmp/b");
        var cap = tool.toCapability(args);

        assertThat(cap).isInstanceOf(Capability.FileMove.class);
        assertThat(((Capability.FileMove) cap).deletesSource()).isFalse();
    }

    @Test
    void camelCaseMethodNameMatchesWriteVerb() {
        // Codex P1 on #495 (round-11): MCP servers using camelCase tool
        // names (writeFile) or kebab-case (write-file) bypassed the
        // snake_case-only verb regex. Method-name normalization now folds
        // both forms to snake_case before matching.
        var tool = McpToolBridge.create("fs", mcpTool("writeFile", "desc"), client);

        var args = new ObjectMapper().createObjectNode().put("path", "/repo/.env");
        var cap = tool.toCapability(args);

        assertThat(cap).isInstanceOf(Capability.FileWrite.class);
    }

    @Test
    void kebabCaseMethodNameMatchesWriteVerb() {
        var tool = McpToolBridge.create("fs", mcpTool("write-file", "desc"), client);

        var args = new ObjectMapper().createObjectNode().put("path", "/repo/.env");
        var cap = tool.toCapability(args);

        assertThat(cap).isInstanceOf(Capability.FileWrite.class);
    }

    @Test
    void camelCaseDeleteMethodMatches() {
        var tool = McpToolBridge.create("fs", mcpTool("deleteFile", "desc"), client);

        var args = new ObjectMapper().createObjectNode().put("path", "/repo/.env");
        var cap = tool.toCapability(args);

        assertThat(cap).isInstanceOf(Capability.FileDelete.class);
    }

    @Test
    void camelCaseMoveMethodMatches() {
        var tool = McpToolBridge.create("fs", mcpTool("moveFile", "desc"), client);

        var args = new ObjectMapper().createObjectNode()
                .put("source", "/tmp/a")
                .put("destination", "/repo/.env");
        var cap = tool.toCapability(args);

        assertThat(cap).isInstanceOf(Capability.FileMove.class);
        assertThat(((Capability.FileMove) cap).destination()).isEqualTo(Path.of("/repo/.env"));
    }

    @Test
    void pascalCaseMethodMatches() {
        // PascalCase like "WriteFile" — also covered by the camelCase split.
        var tool = McpToolBridge.create("fs", mcpTool("WriteFile", "desc"), client);

        var args = new ObjectMapper().createObjectNode().put("path", "/repo/.env");
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
    void mcpFileWriteToSensitivePathIsStructurallyDenied() {
        // End-to-end: an MCP server's write_file(path=".env") composes through
        // McpToolBridge.toCapability() into a Capability.FileWrite that the
        // structural-denial layer refuses. Pins that the wiring between the
        // MCP boundary and DefaultPermissionPolicy works for the bypass Codex
        // P1 flagged (#495).
        var tool = McpToolBridge.create("fs", mcpTool("write_file", "desc"), client);
        var args = new ObjectMapper().createObjectNode().put("path", "/repo/.env");

        var cap = tool.toCapability(args);
        var decision = new DefaultPermissionPolicy("auto-accept", /* denySensitivePaths */ true).evaluateStructural(cap);

        assertThat(decision)
                .as("MCP-driven write to .env must be structurally denied even in auto-accept")
                .isNotNull();
        assertThat(decision.reason()).contains("sensitive path");
    }
}
