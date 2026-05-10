package dev.aceclaw.security;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import dev.aceclaw.security.ids.MemoryKey;

import java.net.URI;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

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
 * always {@code READ}; {@link BashExec} is {@code EXECUTE} for benign
 * commands and self-escalates to {@code DANGEROUS} for known-destructive
 * patterns ({@code rm -rf}, {@code mkfs}, {@code git push --force}, etc.).
 * The escalation lives in the variant, not at the call site, so every
 * surface — audit log, dashboard label, user prompt — agrees about which
 * commands are dangerous without each one re-implementing the rule set.
 *
 * <h3>{@link LegacyToolUse}</h3>
 *
 * Exists to keep historical {@code CapabilityAuditEntry} v1 records readable
 * after the audit log migrates (#480 PR 3). Never produced by current code;
 * present so the legacy deserializer has a {@code Capability} variant to
 * land on.
 */
// Type discriminator property MUST NOT collide with any variant's record
// component name. Using "kind" (the obvious choice) collides with
// FileSearch.kind (SearchKind enum) — Jackson then writes both keys with
// the same JSON name, readTree() collapses duplicates last-wins, the
// discriminator becomes "GLOB"/"GREP"/"LIST" instead of "FileSearch", and
// every glob/grep/list_directory v2 audit entry fails to deserialize and
// gets dropped from readVerified() — silent loss of an entire entry class.
// "@type" is Jackson's idiomatic discriminator and unambiguous because
// "@" is not a legal Java identifier prefix, so no future variant field
// can collide.
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "@type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = Capability.FileRead.class, name = "FileRead"),
        @JsonSubTypes.Type(value = Capability.FileWrite.class, name = "FileWrite"),
        @JsonSubTypes.Type(value = Capability.FileDelete.class, name = "FileDelete"),
        @JsonSubTypes.Type(value = Capability.FileSearch.class, name = "FileSearch"),
        @JsonSubTypes.Type(value = Capability.BashExec.class, name = "BashExec"),
        @JsonSubTypes.Type(value = Capability.OsScript.class, name = "OsScript"),
        @JsonSubTypes.Type(value = Capability.BrowserAction.class, name = "BrowserAction"),
        @JsonSubTypes.Type(value = Capability.ScreenCapture.class, name = "ScreenCapture"),
        @JsonSubTypes.Type(value = Capability.SkillInvoke.class, name = "SkillInvoke"),
        @JsonSubTypes.Type(value = Capability.HttpFetch.class, name = "HttpFetch"),
        @JsonSubTypes.Type(value = Capability.McpInvoke.class, name = "McpInvoke"),
        @JsonSubTypes.Type(value = Capability.MemoryWrite.class, name = "MemoryWrite"),
        @JsonSubTypes.Type(value = Capability.MemoryRead.class, name = "MemoryRead"),
        @JsonSubTypes.Type(value = Capability.SubAgentSpawn.class, name = "SubAgentSpawn"),
        @JsonSubTypes.Type(value = Capability.LegacyToolUse.class, name = "LegacyToolUse"),
})
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

    // -- Filesystem search ----------------------------------------------

    /**
     * Search-the-filesystem capability used by {@code glob}, {@code grep},
     * and {@code list_directory}. Distinguishing them as a typed
     * {@link SearchKind} (not three separate variants) means policies can
     * write "deny all FileSearch in /etc" once instead of three times, while
     * still letting a tighter rule say "deny GREP specifically because it
     * reads file content."
     *
     * <p>Risk is {@code READ} for every kind: search semantically discloses
     * filesystem state to the agent, which is INGRESS. {@code GREP} reads
     * file bytes — heavier than {@code GLOB} or {@code LIST} which only see
     * names — but PolicyEngine (#465 Scope #2) is the place to encode that
     * difference, not the variant's flat risk class.
     */
    record FileSearch(Path root, String pattern, SearchKind kind) implements Capability {
        public FileSearch {
            Objects.requireNonNull(root, "root");
            Objects.requireNonNull(pattern, "pattern");
            Objects.requireNonNull(kind, "kind");
        }

        @Override public PermissionLevel risk() { return PermissionLevel.READ; }
        @Override public DataFlow dataFlow() { return DataFlow.INGRESS; }
        @Override public String displayLabel() {
            return switch (kind) {
                case GLOB -> "glob " + pattern + " in " + renderPath(root);
                case GREP -> "grep " + pattern + " in " + renderPath(root);
                case LIST -> "list " + renderPath(root);
            };
        }
    }

    // -- Process --------------------------------------------------------

    /**
     * Shell command execution. {@link #risk()} returns {@code EXECUTE} for
     * normal commands and self-escalates to {@code DANGEROUS} when the
     * command matches a known-destructive pattern (e.g. {@code rm -rf},
     * {@code dd}, {@code mkfs}, {@code :(){ :|:& };:}, fork bombs).
     *
     * <p>The escalation lives in the variant rather than in PolicyEngine
     * because the rule is structural ("any rm -rf is dangerous") rather
     * than policy ("our org's rule about rm-rf"); centralising it here
     * means audit-log readers, dashboard event labels, and the user prompt
     * all see {@code DANGEROUS} for the same set of commands without each
     * surface re-implementing the detection.
     *
     * <p>{@link #DESTRUCTIVE_PATTERN} is intentionally conservative — false
     * negatives (a destructive command not flagged) fall back to the
     * standard {@code EXECUTE} prompt, which is the same answer the user
     * would have got pre-#480. False positives ({@code DANGEROUS} on a
     * benign-looking command) just produce one extra prompt; no one loses
     * data.
     */
    record BashExec(String command, Path cwd) implements Capability {

        /**
         * Destructive command patterns that escalate this capability's
         * {@link #risk()} from {@code EXECUTE} to {@code DANGEROUS}. Order
         * matters only for readability — the matcher only checks for any
         * match. Patterns target the most common irrecoverable destruction
         * vectors:
         *
         * <ul>
         *   <li>{@code rm -rf} / {@code rm -fr} — recursive force delete.
         *       Single dash, double dash, and combined flags all match.</li>
         *   <li>{@code sudo rm} — privileged delete (even non-recursive
         *       sudo rm is irreversible at the privilege boundary).</li>
         *   <li>{@code dd} writing to a block device — wipes drives.</li>
         *   <li>{@code mkfs} / {@code mke2fs} — filesystem (re)creation.</li>
         *   <li>{@code shred} / {@code wipe} — secure-erase utilities.</li>
         *   <li>{@code chmod -R 000} / {@code chown -R} on root paths —
         *       lockout patterns.</li>
         *   <li>Fork bomb {@code :(){ :|:& };:} — DoS the host.</li>
         *   <li>{@code git push --force} / {@code git push -f} on a non-
         *       feature ref — destroys upstream history. The pattern
         *       intentionally matches both forms; PolicyEngine can later
         *       relax this for feature branches.</li>
         *   <li>{@code git reset --hard} — discards uncommitted work.</li>
         *   <li>{@code curl ... | sh} / {@code wget ... | sh} — pipe-to-shell
         *       installs run arbitrary remote code unprivilegedly. Flagged
         *       as destructive because the agent cannot verify what's on
         *       the other side of the URL.</li>
         * </ul>
         *
         * <p>Whitespace tolerance: the regex uses {@code \s+} so flag
         * combinations like {@code rm   -rf} or {@code rm\t-rf} match.
         * {@code (?i)} is intentionally NOT applied — Unix command names
         * are case-sensitive and {@code RM} is not a real binary; matching
         * case-insensitively would invite trivial bypass theatrics that
         * give a false sense of coverage.
         */
        private static final Pattern DESTRUCTIVE_PATTERN = Pattern.compile(
                "(?:^|[\\s;&|`(])(?:" +
                        // rm: command name is case-sensitive (RM is not a real binary), but
                        // flag letters can be either case — BSD's recursive flag is -R, GNU's
                        // is -r. We must match both or rm -Rf bypasses the rule.
                        //
                        // Matches the four common spellings of recursive-force rm:
                        //   1) combined short flags     rm -rf  rm -fr  rm -Rf  etc.
                        //   2) separated short flags    rm -r -f  rm -f -r  rm -R -f  etc.
                        //   3) long flags (both orders) rm --recursive --force / --force --recursive
                        //   4) mixed                    rm -r --force / rm --recursive -f
                        // Without (2)/(4) the pattern misses "rm -r -f /tmp/x" which is the
                        // most common interactive form (CodeRabbit review on #491).
                        "rm\\s+(?:" +
                                "-[a-zA-Z]*[rR][a-zA-Z]*[fF][a-zA-Z]*" +                       // -rf, -Rf, -rfv …
                                "|-[a-zA-Z]*[fF][a-zA-Z]*[rR][a-zA-Z]*" +                      // -fr, -fR …
                                "|-[a-zA-Z]*[rR][a-zA-Z]*\\s+(?:[^-\\s]\\S*\\s+)?-[a-zA-Z]*[fF][a-zA-Z]*" + // -r ... -f
                                "|-[a-zA-Z]*[fF][a-zA-Z]*\\s+(?:[^-\\s]\\S*\\s+)?-[a-zA-Z]*[rR][a-zA-Z]*" + // -f ... -r
                                "|--recursive\\s+(?:[^-\\s]\\S*\\s+)?--force" +
                                "|--force\\s+(?:[^-\\s]\\S*\\s+)?--recursive" +
                                "|--recursive\\s+(?:[^-\\s]\\S*\\s+)?-[a-zA-Z]*[fF][a-zA-Z]*" + // --recursive ... -f
                                "|--force\\s+(?:[^-\\s]\\S*\\s+)?-[a-zA-Z]*[rR][a-zA-Z]*" +    // --force ... -r
                                "|-[a-zA-Z]*[rR][a-zA-Z]*\\s+--force" +                         // -r --force
                                "|-[a-zA-Z]*[fF][a-zA-Z]*\\s+--recursive" +                     // -f --recursive
                        ")" +
                        "|sudo\\s+rm" +
                        "|dd\\s+[^|]*of=/dev/" +
                        "|mkfs(?:\\.[a-z0-9]+)?\\s" +
                        "|mke2fs\\s" +
                        "|shred\\s" +
                        "|\\bwipe\\s+-" +
                        "|chmod\\s+-R\\s+0?00\\s+/" +
                        "|chown\\s+-R\\s+[^/]+/(?:\\s|$)" +
                        "|:\\(\\)\\s*\\{\\s*:\\|:&\\s*\\}\\s*;:" +
                        // git push --force in any position of the push command line, so
                        // "git push origin --force" / "git push origin master -f" both
                        // match (the most common spellings in practice). Original regex
                        // required the flag immediately after "push", which missed
                        // every remote-named form (CodeRabbit review on #491).
                        "|git\\s+push\\b[^|;&\\n]*?\\s(?:-f\\b|--force\\b)" +
                        "|git\\s+reset\\s+--hard" +
                        "|(?:curl|wget)\\s[^|]*\\|\\s*(?:sudo\\s+)?(?:bash|sh|zsh)\\b" +
                        ")");

        public BashExec {
            Objects.requireNonNull(command, "command");
            Objects.requireNonNull(cwd, "cwd");
        }

        /**
         * Returns whether {@code command} matches a known-destructive pattern.
         * Exposed package-private so {@code BashExecToolCapabilityTest} can
         * exercise the rule set directly without round-tripping through a
         * full capability instance.
         */
        static boolean isDestructive(String command) {
            return command != null && DESTRUCTIVE_PATTERN.matcher(command).find();
        }

        @Override public PermissionLevel risk() {
            return isDestructive(command) ? PermissionLevel.DANGEROUS : PermissionLevel.EXECUTE;
        }
        @Override public DataFlow dataFlow() { return DataFlow.BOTH; }
        @Override public String displayLabel() {
            return (isDestructive(command) ? "exec[DANGEROUS]: " : "exec: ") + command;
        }
    }

    /**
     * Host-OS scripting capability for languages other than the system
     * shell — currently AppleScript on macOS. Modelled as its own variant
     * (not as {@link BashExec}) so policies can ban {@code applescript}
     * (which can drive arbitrary GUI apps and read clipboard / mail / etc.)
     * without also banning {@code bash}, and so the audit log records the
     * actual interpreter rather than wrapping everything in a fake bash
     * label. Risk is {@code EXECUTE}; data-flow is {@code BOTH}.
     */
    record OsScript(String language, String source) implements Capability {
        public OsScript {
            Objects.requireNonNull(language, "language");
            Objects.requireNonNull(source, "source");
            if (language.isBlank()) {
                throw new IllegalArgumentException("language must not be blank");
            }
        }

        @Override public PermissionLevel risk() { return PermissionLevel.EXECUTE; }
        @Override public DataFlow dataFlow() { return DataFlow.BOTH; }
        @Override public String displayLabel() {
            // First line of the script gives the operator a hint without
            // dumping a 200-line payload into the audit display.
            int newline = source.indexOf('\n');
            String firstLine = newline < 0 ? source : source.substring(0, newline);
            return language + ": " + (firstLine.length() > 80
                    ? firstLine.substring(0, 80) + "..."
                    : firstLine);
        }
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
            // ALL HTTP methods are EGRESS-capable, including GET/HEAD: URL
            // query strings, headers, cookies, and (in some clients) bodies
            // ship data outbound regardless of the verb. Classifying GET as
            // INGRESS-only would let a "block-egress" policy be bypassed by
            // exfiltrating via querystring — a real-world attack pattern.
            // Keeping a single answer (BOTH) avoids that whole class of
            // bypass; per-payload inspection belongs in PolicyEngine
            // (#465 Scope #2). (Codex review on #481, addressed in #482.)
            return DataFlow.BOTH;
        }
        @Override public String displayLabel() { return method + " " + url; }
    }

    // -- Browser --------------------------------------------------------

    /**
     * Browser automation capability (Playwright / driven Chromium): navigate,
     * click, type, screenshot, evaluate JS in the page. Risk is
     * {@code EXECUTE} because the action vocabulary includes JS evaluation
     * and arbitrary navigation, which can fetch and exfiltrate. {@code url}
     * is {@link Optional#empty()} for actions that don't target a URL
     * (e.g. {@code "click"}, {@code "screenshot"}); when present it lets
     * policies whitelist domains.
     *
     * <p>Action vocabulary is intentionally a free string rather than an
     * enum: the underlying browser library evolves, and the policy surface
     * shouldn't have to ship a new enum value every time. PolicyEngine can
     * pattern-match on {@code action.startsWith("evaluate")} etc.
     */
    record BrowserAction(String action, Optional<URI> url) implements Capability {
        public BrowserAction {
            Objects.requireNonNull(action, "action");
            Objects.requireNonNull(url, "url");
            if (action.isBlank()) {
                throw new IllegalArgumentException("action must not be blank");
            }
        }

        @Override public PermissionLevel risk() { return PermissionLevel.EXECUTE; }
        @Override public DataFlow dataFlow() { return DataFlow.BOTH; }
        @Override public String displayLabel() {
            return url.map(u -> "browser " + action + " " + u)
                      .orElseGet(() -> "browser " + action);
        }
    }

    // -- Screen ---------------------------------------------------------

    /**
     * Screen-capture capability. Modelled as its own variant rather than
     * folded into a generic "capture-from-host" so a privacy policy can
     * deny {@code ScreenCapture} without also denying {@link FileRead}:
     * screen contents include unrelated apps, notifications, and any
     * sensitive material the operator happens to have visible. Risk is
     * {@code READ} (data flows in to the agent), but the human display
     * label flags the privacy character so dashboard readers see at a
     * glance that this isn't a normal file read.
     */
    record ScreenCapture(String reason) implements Capability {
        public ScreenCapture {
            Objects.requireNonNull(reason, "reason");
        }

        @Override public PermissionLevel risk() { return PermissionLevel.READ; }
        @Override public DataFlow dataFlow() { return DataFlow.INGRESS; }
        @Override public String displayLabel() {
            return reason.isBlank() ? "screen capture" : "screen capture: " + reason;
        }
    }

    // -- Skill ----------------------------------------------------------

    /**
     * Invocation of a registered local skill (Claude Code's skill system).
     * Distinct from {@link McpInvoke}: skills are versioned, in-process
     * code units; MCP is an out-of-process server. A policy that wants to
     * disable third-party MCP servers should not also disable first-party
     * skills, and vice-versa.
     *
     * <p>Args are intentionally not stored — same reason as {@link McpInvoke}
     * (size, secrets). PolicyEngine sees {@code skillName} and decides
     * whether deeper inspection is required.
     */
    record SkillInvoke(String skillName) implements Capability {
        public SkillInvoke {
            Objects.requireNonNull(skillName, "skillName");
            if (skillName.isBlank()) {
                throw new IllegalArgumentException("skillName must not be blank");
            }
        }

        @Override public PermissionLevel risk() { return PermissionLevel.EXECUTE; }
        @Override public DataFlow dataFlow() { return DataFlow.BOTH; }
        @Override public String displayLabel() { return "skill " + skillName; }
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
     * captures the moment of delegation.
     *
     * <p><strong>Why no {@code parentDepth} field:</strong> the spawning
     * agent's depth is already authoritatively recorded by
     * {@link Provenance#subAgentDepth()} on the same audit entry. Carrying
     * a redundant copy on the variant is fine in principle, but only when
     * it can be populated correctly — and at {@code TaskTool.toCapability}
     * time we don't have a handle to the calling agent's depth, so the
     * field would always be {@code 0} and disagree with Provenance for
     * every nested spawn (CodeRabbit review on #491). One source of truth
     * (Provenance) is better than two that contradict.
     */
    record SubAgentSpawn(String role) implements Capability {
        public SubAgentSpawn {
            Objects.requireNonNull(role, "role");
        }

        @Override public PermissionLevel risk() { return PermissionLevel.EXECUTE; }
        @Override public DataFlow dataFlow() { return DataFlow.BOTH; }
        @Override public String displayLabel() {
            return "spawn sub-agent role=" + role;
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
