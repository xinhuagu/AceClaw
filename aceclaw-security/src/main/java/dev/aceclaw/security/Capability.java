package dev.aceclaw.security;

import dev.aceclaw.security.ids.MemoryKey;

import java.net.URI;
import java.nio.file.Path;
import java.util.Objects;

/**
 * One concrete capability the agent loop wants to use, in a shape that makes
 * invalid combinations <em>unrepresentable</em> rather than runtime-checked
 * (#480, foundation for the runtime-governance epic #465).
 *
 * <p>Every variant carries exactly the fields its operation needs — and
 * nothing else. That means:
 *
 * <ul>
 *   <li>{@link FileRead} can only describe a path; you cannot accidentally
 *       set its {@link DataFlow} to {@code EGRESS} or its {@link PermissionLevel}
 *       to {@code DANGEROUS}, because those attributes are derived methods,
 *       not constructor fields.</li>
 *   <li>The cross-product nonsense states the older flat schema permitted
 *       (e.g. "Operation.Read on a Process", "HttpFetch with DataFlow.Ingress
 *       only") cannot be constructed at all.</li>
 *   <li>PolicyEngine (#465 Scope #2) consumes a {@code Capability} via
 *       exhaustive {@code switch}; the compiler enforces that adding a new
 *       variant updates every consumer.</li>
 * </ul>
 *
 * <h3>Adapter is intentionally absent</h3>
 *
 * Two different adapters (built-in {@code WriteFileTool} and an MCP server
 * that happens to also offer file write) producing the same
 * {@code FileWrite("/etc/hosts", OVERWRITE)} should get the <em>same</em>
 * policy answer — that is the whole point of capability-style governance.
 * "Who requested this" is recorded in {@code Provenance}, not in the
 * Capability itself, so policies cannot fork by adapter.
 *
 * <h3>{@link #risk()} is derived, not declared</h3>
 *
 * Callers cannot lie about a capability's risk class. {@link FileRead} is
 * always {@code READ}; {@link BashExec} is {@code EXECUTE} (PR-1 baseline);
 * future patch will let {@code BashExec} self-escalate to {@code DANGEROUS}
 * for known destructive patterns. Either way, the escalation lives in the
 * variant — not at the call site.
 *
 * <h3>{@link LegacyToolUse}</h3>
 *
 * Exists to keep historical {@code CapabilityAuditEntry} v1 records readable
 * after the audit log migrates (#480 PR 3). Never produced by current code;
 * present so the legacy deserializer has a {@code Capability} variant to
 * land on.
 */
public sealed interface Capability {

    /**
     * Risk classification for this capability. Derived from the variant —
     * never user-supplied. Existing {@link PermissionLevel} enum is reused.
     */
    PermissionLevel risk();

    /**
     * Direction of data flow for this capability. See {@link DataFlow}.
     */
    DataFlow dataFlow();

    /**
     * Single-line human-readable summary, suitable for permission prompts
     * and dashboard event labels. Example: {@code "write /tmp/x (OVERWRITE)"}.
     */
    String displayLabel();

    /**
     * Default allowlist key used by {@link PermissionManager#check(Capability, Provenance)}
     * — the convenience overload — when no explicit key is supplied. Returns
     * the variant's simple class name (e.g. {@code "FileRead"}); useful for
     * daemon-internal callers that have no originating tool name.
     *
     * <p><strong>Tool dispatchers must NOT rely on this default.</strong>
     * They should call
     * {@link PermissionManager#check(Capability, Provenance, String, String)}
     * with the originating tool's name as the allowlist key, so that user
     * approvals granted before #480 (keyed by tool name like
     * {@code "write_file"}) keep auto-approving the migrated structured
     * path. Two different tools producing the same capability variant
     * intentionally remain separate allowlist entries — "always allow this
     * tool" is a per-tool decision, while capability-level rules belong in
     * PolicyEngine (#465 Scope #2).
     *
     * <p>{@link LegacyToolUse} overrides this to return its recorded
     * {@code toolName} so the convenience overload also stays compatible
     * for that bridge variant.
     */
    default String allowlistKey() {
        return getClass().getSimpleName();
    }

    /**
     * Renders a {@link Path} with forward slashes regardless of the host OS.
     * Audit logs and dashboard labels are observed across platforms (a Linux
     * operator viewing a Windows daemon's events shouldn't see backslashes
     * that confuse readers and break grep). Native path semantics are
     * preserved at the API boundary; this function only affects the
     * <em>display</em> form.
     */
    private static String renderPath(Path path) {
        return path.toString().replace('\\', '/');
    }

    // -- Filesystem -----------------------------------------------------

    record FileRead(Path path) implements Capability {
        public FileRead {
            Objects.requireNonNull(path, "path");
        }

        @Override public PermissionLevel risk() { return PermissionLevel.READ; }
        @Override public DataFlow dataFlow() { return DataFlow.INGRESS; }
        @Override public String displayLabel() { return "read " + renderPath(path); }
    }

    record FileWrite(Path path, WriteMode mode) implements Capability {
        public FileWrite {
            Objects.requireNonNull(path, "path");
            Objects.requireNonNull(mode, "mode");
        }

        @Override public PermissionLevel risk() { return PermissionLevel.WRITE; }
        @Override public DataFlow dataFlow() { return DataFlow.EGRESS; }
        @Override public String displayLabel() { return "write " + renderPath(path) + " (" + mode + ")"; }
    }

    /**
     * Deletion is classified as {@link PermissionLevel#DANGEROUS} rather than
     * {@code WRITE} so that {@code accept-edits} mode (which auto-approves
     * {@code WRITE}) does NOT silently delete files — operators expect that
     * mode to remove the prompt for "edit a file", not "rm -rf the project".
     * Only {@code DANGEROUS} requires explicit approval in every mode except
     * {@code yolo}, matching the existing {@link DefaultPermissionPolicy}
     * behaviour for destructive ops.
     */
    record FileDelete(Path path) implements Capability {
        public FileDelete {
            Objects.requireNonNull(path, "path");
        }

        @Override public PermissionLevel risk() { return PermissionLevel.DANGEROUS; }
        @Override public DataFlow dataFlow() { return DataFlow.EGRESS; }
        @Override public String displayLabel() { return "delete " + renderPath(path); }
    }

    // -- Process --------------------------------------------------------

    /**
     * Shell command execution. {@link #risk()} returns {@code EXECUTE} in
     * this PR; #465 Scope #2 (PolicyEngine) is where DANGEROUS escalation
     * for destructive command patterns will live, so the rule set is in
     * one place rather than scattered across capability variants.
     */
    record BashExec(String command, Path cwd) implements Capability {
        public BashExec {
            Objects.requireNonNull(command, "command");
            Objects.requireNonNull(cwd, "cwd");
        }

        @Override public PermissionLevel risk() { return PermissionLevel.EXECUTE; }
        @Override public DataFlow dataFlow() { return DataFlow.BOTH; }
        @Override public String displayLabel() { return "exec: " + command; }
    }

    // -- Network --------------------------------------------------------

    record HttpFetch(URI url, String method) implements Capability {
        public HttpFetch {
            Objects.requireNonNull(url, "url");
            Objects.requireNonNull(method, "method");
            if (method.isBlank()) {
                throw new IllegalArgumentException("method must not be blank");
            }
        }

        @Override public PermissionLevel risk() { return PermissionLevel.EXECUTE; }
        @Override public DataFlow dataFlow() {
            // GET/HEAD are read-only request bodies; everything else can
            // ship payload, so DataFlow.BOTH is the safe default.
            return ("GET".equalsIgnoreCase(method) || "HEAD".equalsIgnoreCase(method))
                    ? DataFlow.INGRESS
                    : DataFlow.BOTH;
        }
        @Override public String displayLabel() { return method + " " + url; }
    }

    // -- MCP ------------------------------------------------------------

    /**
     * Invocation of an MCP server's named method. The args payload is
     * intentionally <em>not</em> stored here — args can be huge, can carry
     * secrets, and live with the audit entry only when the policy chose
     * to retain them. PolicyEngine sees {@code (server, method)} and decides
     * whether further inspection is warranted.
     */
    record McpInvoke(String server, String method) implements Capability {
        public McpInvoke {
            Objects.requireNonNull(server, "server");
            Objects.requireNonNull(method, "method");
        }

        @Override public PermissionLevel risk() { return PermissionLevel.EXECUTE; }
        @Override public DataFlow dataFlow() { return DataFlow.BOTH; }
        @Override public String displayLabel() { return "mcp " + server + "." + method; }
    }

    // -- Memory ---------------------------------------------------------

    /**
     * {@code tier} is a string rather than a {@code MemoryTier} reference
     * to avoid a cross-module dependency from {@code aceclaw-security} to
     * {@code aceclaw-memory}. The memory subsystem maps it back when
     * applying the write.
     */
    record MemoryWrite(MemoryKey key, String tier) implements Capability {
        public MemoryWrite {
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(tier, "tier");
        }

        @Override public PermissionLevel risk() { return PermissionLevel.WRITE; }
        @Override public DataFlow dataFlow() { return DataFlow.EGRESS; }
        @Override public String displayLabel() { return "memory.write " + tier + ":" + key; }
    }

    record MemoryRead(MemoryKey key) implements Capability {
        public MemoryRead {
            Objects.requireNonNull(key, "key");
        }

        @Override public PermissionLevel risk() { return PermissionLevel.READ; }
        @Override public DataFlow dataFlow() { return DataFlow.INGRESS; }
        @Override public String displayLabel() { return "memory.read " + key; }
    }

    // -- Sub-agent ------------------------------------------------------

    /**
     * Spawning a sub-agent is itself a capability, so the audit chain
     * captures the moment of delegation. {@code parentDepth} is the
     * spawning agent's depth — the new sub-agent runs at
     * {@code parentDepth + 1}. Policies can refuse spawns past a depth.
     */
    record SubAgentSpawn(String role, int parentDepth) implements Capability {
        public SubAgentSpawn {
            Objects.requireNonNull(role, "role");
            if (parentDepth < 0) {
                throw new IllegalArgumentException("parentDepth must be non-negative; got " + parentDepth);
            }
        }

        @Override public PermissionLevel risk() { return PermissionLevel.EXECUTE; }
        @Override public DataFlow dataFlow() { return DataFlow.BOTH; }
        @Override public String displayLabel() {
            return "spawn sub-agent role=" + role + " depth=" + (parentDepth + 1);
        }
    }

    // -- Legacy ---------------------------------------------------------

    /**
     * Bridge variant for reading historical {@link audit.CapabilityAuditEntry}
     * v1 records, which carried only {@code (toolName, level)}. Never produced
     * by current code; lives here so the legacy deserializer (#480 PR 3) has
     * a typed landing pad.
     */
    record LegacyToolUse(String toolName, PermissionLevel declaredLevel) implements Capability {
        public LegacyToolUse {
            Objects.requireNonNull(toolName, "toolName");
            Objects.requireNonNull(declaredLevel, "declaredLevel");
        }

        @Override public PermissionLevel risk() { return declaredLevel; }
        @Override public DataFlow dataFlow() {
            // Best-effort mapping from the v1 flat-record level. EXECUTE and
            // DANGEROUS go to BOTH (matching {@link BashExec}, the canonical
            // EXECUTE-level capability) — those operations typically read and
            // write at the same time. Pure WRITE → EGRESS; READ → INGRESS.
            // Anything finer-grained is unknowable from v1 fields.
            return switch (declaredLevel) {
                case READ -> DataFlow.INGRESS;
                case WRITE -> DataFlow.EGRESS;
                case EXECUTE, DANGEROUS -> DataFlow.BOTH;
            };
        }
        @Override public String displayLabel() { return "legacy:" + toolName; }

        /**
         * Preserves the existing tool-name keyed allowlist semantics: a user
         * who clicked "always allow {@code write_file}" before #480 must
         * keep being auto-approved even after their tool dispatch goes
         * through the {@link LegacyToolUse} bridge. Returning the recorded
         * {@code toolName} (instead of the default {@code "LegacyToolUse"})
         * makes that work without touching the allowlist storage format.
         */
        @Override public String allowlistKey() { return toolName; }
    }
}
