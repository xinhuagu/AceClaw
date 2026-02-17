# AceClaw Security Architecture & Threat Model

## Executive Summary

AceClaw is a Java-based AI coding agent that executes arbitrary tools (bash commands, file operations, web requests, MCP server calls) on behalf of users. This document defines the comprehensive security architecture, threat model, permission system, and mitigation strategies for AceClaw. Security is a first-class design concern: every component is built with defense-in-depth, least privilege, and fail-closed principles.

**Lessons from the field**: OpenClaw (157K+ GitHub stars, TypeScript-based AI agent) suffered critical security failures within 48 hours of going viral in January 2026 -- hundreds of publicly accessible installations leaking API keys, prompt injection attacks, malicious skills containing credential stealers, and unrestricted shell command execution. Claude Code addressed similar concerns through OS-level sandboxing (Seatbelt on macOS, bubblewrap on Linux), reducing permission prompts by 84% while maintaining security. AceClaw must learn from both.

Java provides significant security advantages over TypeScript/Node.js implementations (OpenClaw, Claude Code): strong static typing, sealed class hierarchies for exhaustive permission modeling, the Java Platform Module System (JPMS) for encapsulation, and mature ecosystem libraries for cryptography, keyring integration, and sandboxing.

---

## 1. Threat Model

### 1.1 Threat Actors

| Actor | Capability | Motivation |
|-------|-----------|------------|
| **Malicious File Content** | Injects instructions via source files, READMEs, git diffs | Prompt injection to exfiltrate data or execute commands |
| **Malicious MCP Server** | Returns crafted tool descriptions, poisoned responses | Tool poisoning, credential theft, privilege escalation |
| **Compromised Dependency** | Executes code during build or runtime | Supply chain attack, backdoor installation |
| **Malicious Repository** | Contains crafted content in issues, PRs, commit messages | Indirect prompt injection via development artifacts |
| **Local Attacker** | Has access to the same machine or network | Session hijacking, credential theft, DNS rebinding |
| **The Agent Itself** | LLM acts beyond intended scope due to prompt manipulation | Unauthorized operations, data exfiltration |

### 1.2 Attack Vectors

#### 1.2.1 Prompt Injection Attacks

**Direct Prompt Injection**: Attacker provides instructions directly to the agent input.

**Indirect Prompt Injection**: Malicious instructions embedded in:
- Source code files, comments, or documentation (READMEs, CONTRIBUTING.md)
- Git commit messages, PR descriptions, issue content
- MCP server tool descriptions or response payloads
- Web page content fetched by the agent
- Error messages from executed commands
- Environment variable values

**Mitigation Strategy**:
- Context separation: User instructions, system prompts, and tool outputs are processed in distinct typed message segments
- Input sanitization layer that strips common injection patterns from tool outputs
- Tool output length limits to prevent context flooding
- Anomaly detection on tool responses (unexpected instruction-like patterns)
- Java advantage: Strong typing ensures message segments cannot be confused; sealed interfaces enforce message type exhaustiveness

```java
public sealed interface MessageSegment permits
    SystemPrompt,
    UserInstruction,
    ToolOutput,
    AgentReasoning {

    String content();
    MessageOrigin origin();
    TrustLevel trustLevel();
}

public enum TrustLevel {
    SYSTEM,      // System prompts, internal
    USER,        // Direct user input
    TOOL_TRUSTED,// Trusted tool output (e.g., file read from allowed path)
    TOOL_UNTRUSTED, // Untrusted tool output (e.g., web fetch, MCP response)
    EXTERNAL     // External content (fetched URLs, git content)
}
```

#### 1.2.2 Malicious MCP Servers

**Tool Poisoning**: MCP server provides tool descriptions containing hidden instructions that manipulate the agent into performing unintended actions.

**Response Manipulation**: MCP server returns crafted responses designed to trigger prompt injection or exfiltrate data through subsequent tool calls.

**Credential Theft**: MCP server exploits OAuth flows or token passthrough vulnerabilities to steal credentials.

**SSRF via MCP**: Malicious MCP server provides internal URLs in OAuth metadata, causing the client to probe internal network resources.

**Mitigation Strategy**:
- MCP server allowlist with cryptographic identity verification
- Tool description validation and sanitization before presenting to LLM
- Response size limits and content filtering for MCP responses
- Strict OAuth implementation: no token passthrough, per-client consent, PKCE
- SSRF protection: block private IP ranges, enforce HTTPS, validate redirect targets
- MCP server capability auditing and review before installation

#### 1.2.3 Unauthorized File System Access

**Path Traversal**: Agent attempts to read or write files outside allowed directories.

**Sensitive File Access**: Agent reads credentials, SSH keys, or other sensitive files.

**System File Modification**: Agent modifies shell configs (.bashrc, .zshrc), cron jobs, or system binaries.

**Mitigation Strategy**:
- Path canonicalization and validation before every file operation
- Configurable allow/deny lists for read and write paths
- Default deny for sensitive directories (~/.ssh, ~/.gnupg, ~/.aws, /etc)
- Write operations restricted to project working directory by default
- Java advantage: `java.nio.file.Path.toRealPath()` for canonical path resolution; NIO file system watchers for real-time monitoring

```java
public sealed interface FilePermission permits
    FilePermission.ReadOnly,
    FilePermission.ReadWrite,
    FilePermission.Denied {

    record ReadOnly(PathMatcher allowedPaths) implements FilePermission {}
    record ReadWrite(PathMatcher allowedPaths) implements FilePermission {}
    record Denied(PathMatcher deniedPaths) implements FilePermission {}
}
```

#### 1.2.4 Credential Leakage

**Log Exposure**: API keys or tokens appear in conversation logs, debug output, or audit trails.

**Context Window Exposure**: Credentials loaded into LLM context via environment variables or file reads.

**Exfiltration via Tool Calls**: Credentials sent to external services through web fetches or MCP calls.

**Mitigation Strategy**:
- Credential redaction in all log outputs and conversation history
- Environment variable isolation: agent process has filtered env vars
- Pattern-based secret detection (regex for API keys, tokens, passwords)
- Egress filtering: block outbound requests containing detected secrets
- Java advantage: Custom `toString()` on credential types that mask values; `SecretString` type that prevents accidental logging

```java
public final class SecretString {
    private final char[] value;

    @Override
    public String toString() {
        return "***REDACTED***";
    }

    public char[] expose() {
        return Arrays.copyOf(value, value.length);
    }

    public void destroy() {
        Arrays.fill(value, '\0');
    }
}
```

#### 1.2.5 Supply Chain Attacks via Plugins/MCP Servers

**Malicious Package Execution**: npm install scripts, Maven plugin execution, or build tool hooks execute malicious code.

**Trojan MCP Servers**: MCP servers distributed through package registries contain backdoors.

**Dependency Confusion**: Attacker publishes packages with names matching internal dependencies.

**Mitigation Strategy**:
- MCP server installation requires explicit user consent with full command display
- MCP server registry with verified publishers and security audits
- Sandbox execution for MCP server processes (see Section 3)
- Build command sandboxing to prevent network access during dependency resolution
- SBOM (Software Bill of Materials) generation for installed MCP servers

#### 1.2.6 Agent Acting Beyond Intended Scope

**Scope Creep**: Agent performs operations not requested by the user (e.g., deploying code when asked to review).

**Destructive Operations**: Agent runs `rm -rf`, `git push --force`, `DROP TABLE`, or other irreversible commands.

**Autonomous Escalation**: Agent chains tools to bypass individual restrictions (e.g., write a script then execute it).

**Mitigation Strategy**:
- Tool-level permission system (Section 2) with tiered approval
- Destructive operation detection and mandatory user confirmation
- Operation chaining analysis: detect multi-step bypass attempts
- Session-scoped permissions that do not persist across conversations
- Configurable autonomy levels (conservative, balanced, autonomous)

---

## 2. Permission System

### 2.1 Architecture Overview

AceClaw implements a **tiered permission model** with four levels, evaluated at both tool-level and resource-level granularity. The permission system is the primary security boundary between the agent and the host system.

```
Permission Resolution Chain:
  Global Defaults -> Profile Overrides -> Project Settings (.aceclaw/permissions.json)
  -> Session Overrides -> Runtime User Decisions
```

### 2.2 Permission Tiers

```java
public sealed interface PermissionDecision permits
    PermissionDecision.AutoAllow,
    PermissionDecision.PromptOnce,
    PermissionDecision.AlwaysAsk,
    PermissionDecision.Deny {

    /** Tool executes without user prompt. For low-risk, read-only operations. */
    record AutoAllow(String reason) implements PermissionDecision {}

    /** User prompted on first invocation; decision cached for session. */
    record PromptOnce(String reason, Duration cacheTimeout) implements PermissionDecision {}

    /** User prompted every time. For destructive or sensitive operations. */
    record AlwaysAsk(String reason) implements PermissionDecision {}

    /** Operation blocked entirely. Cannot be overridden at runtime. */
    record Deny(String reason) implements PermissionDecision {}
}
```

### 2.3 Tool-Level Permissions

Each tool category has a default permission tier:

| Tool Category | Default Tier | Examples |
|--------------|-------------|----------|
| **File Read** (project scope) | AutoAllow | Read source files in working directory |
| **File Read** (outside project) | PromptOnce | Read files outside working directory |
| **File Read** (sensitive paths) | Deny | ~/.ssh/*, ~/.aws/*, ~/.gnupg/* |
| **File Write** (project scope) | PromptOnce | Edit source files in working directory |
| **File Write** (outside project) | AlwaysAsk | Write files outside working directory |
| **Bash** (safe commands) | AutoAllow | ls, cat, git status, git diff, grep |
| **Bash** (general commands) | PromptOnce | npm install, cargo build, make |
| **Bash** (destructive commands) | AlwaysAsk | rm, git push, docker, sudo |
| **Bash** (blocked commands) | Deny | rm -rf /, shutdown, format |
| **Web Fetch** (allowed domains) | PromptOnce | Documentation sites, package registries |
| **Web Fetch** (unknown domains) | AlwaysAsk | Any unrecognized domain |
| **MCP Tool Call** | PromptOnce | Registered MCP server tools |
| **MCP Server Install** | AlwaysAsk | Installing new MCP servers |

### 2.4 Resource-Level Permissions

Beyond tool-level permissions, resource-level checks apply:

```java
public sealed interface ResourcePermission permits
    ResourcePermission.FileSystem,
    ResourcePermission.Network,
    ResourcePermission.Process,
    ResourcePermission.MCP {

    record FileSystem(
        List<PathMatcher> readAllow,
        List<PathMatcher> readDeny,
        List<PathMatcher> writeAllow,
        List<PathMatcher> writeDeny
    ) implements ResourcePermission {}

    record Network(
        List<String> allowedDomains,
        List<String> blockedDomains,
        boolean blockPrivateRanges
    ) implements ResourcePermission {}

    record Process(
        List<String> safeBinaries,
        List<String> blockedBinaries,
        List<String> blockedPatterns // regex patterns for dangerous commands
    ) implements ResourcePermission {}

    record MCP(
        List<String> allowedServers,
        Map<String, List<String>> serverToolAllowlist
    ) implements ResourcePermission {}
}
```

### 2.5 Session Permission Modes

Inspired by Claude Code's permission modes, AceClaw supports session-level autonomy settings:

| Mode | Description | Risk Level |
|------|------------|------------|
| **Normal (default)** | Prompts for every potentially dangerous operation | Low |
| **Accept Edits** | Auto-accepts file edits within project, prompts for other operations | Medium |
| **Plan Mode** | Read-only operations only, no file modifications or commands | Minimal |
| **Auto-Accept** | Eliminates most permission prompts for the session | High |
| **Delegate** | Coordination-only mode for team leads; no direct tool execution | Minimal |

These modes compose with the 4-tier permission system: even in Auto-Accept mode, `Deny` rules are always enforced. Plan Mode restricts the agent to read-only tools regardless of tier settings.

### 2.6 Permission Persistence and Revocation

- **Session permissions**: Cached in memory, cleared on session end
- **Project permissions**: Stored in `.aceclaw/permissions.json` within the project
- **Global permissions**: Stored in `~/.aceclaw/settings.json`
- **Managed permissions**: Enterprise-managed via `~/.aceclaw/managed-settings.json` (read-only)
- **Revocation**: Users can revoke any permission at any time via `/permissions` command
- **Expiration**: PromptOnce decisions expire after configurable timeout (default: session lifetime)

### 2.7 Permission Resolution Algorithm

```java
public class PermissionResolver {

    public PermissionDecision resolve(ToolInvocation invocation) {
        // 1. Check managed (enterprise) settings first - cannot be overridden
        var managed = managedSettings.evaluate(invocation);
        if (managed instanceof Deny) return managed;

        // 2. Check explicit deny rules
        var denyCheck = denyRules.evaluate(invocation);
        if (denyCheck instanceof Deny) return denyCheck;

        // 3. Check session cache
        var cached = sessionCache.get(invocation.cacheKey());
        if (cached != null) return cached;

        // 4. Check project settings
        var projectDecision = projectSettings.evaluate(invocation);
        if (projectDecision != null) return projectDecision;

        // 5. Check global settings
        var globalDecision = globalSettings.evaluate(invocation);
        if (globalDecision != null) return globalDecision;

        // 6. Fall back to defaults based on tool category and risk level
        return defaultPermissions.evaluate(invocation);
    }
}
```

---

## 3. Execution Sandbox

### 3.1 Overview

AceClaw implements OS-level sandboxing for all bash command execution, providing filesystem and network isolation. The sandbox ensures that even if the agent is manipulated via prompt injection, the blast radius is contained.

### 3.2 Sandbox Architecture

```
+-----------------------------------------------------+
|  AceClaw JVM Process (Host)                         |
|                                                      |
|  +------------------+   +------------------------+  |
|  | Permission Engine |   | Sandbox Controller     |  |
|  +------------------+   +------------------------+  |
|          |                        |                  |
|          v                        v                  |
|  +------------------+   +------------------------+  |
|  | Tool Executor    |-->| Process Spawner        |  |
|  +------------------+   +------------------------+  |
|                                   |                  |
+-----------------------------------|------------------+
                                    |
                    +---------------v--------------+
                    |  Sandboxed Process           |
                    |  (bubblewrap/seatbelt/nsjail)|
                    |                              |
                    |  - Restricted filesystem     |
                    |  - Network proxy             |
                    |  - Resource limits           |
                    |  - No privilege escalation   |
                    +------------------------------+
```

### 3.3 Platform-Specific Sandbox Implementations

#### macOS: Seatbelt (sandbox-exec)
- Uses Apple's built-in Seatbelt framework
- Profile-based restrictions on filesystem, network, and IPC
- Zero additional dependencies on macOS

#### Linux: bubblewrap (bwrap)
- Lightweight unprivileged sandboxing using Linux namespaces
- Mount namespace for filesystem isolation
- Network namespace for network isolation
- PID namespace for process isolation
- Requires `bubblewrap` and `socat` packages

#### Cross-Platform Fallback: nsjail
- Google's lightweight process isolation tool
- Supports Linux (native) with potential macOS support
- Karabiner-based sandboxing capabilities

### 3.4 Filesystem Sandbox Rules

```java
public record SandboxFilesystemPolicy(
    List<Path> readWritePaths,    // Project working directory
    List<Path> readOnlyPaths,     // System libraries, tool binaries
    List<Path> deniedPaths,       // Sensitive directories
    Path tempDirectory,           // Isolated temp directory
    boolean readOnlyRoot          // Root filesystem is read-only
) {
    public static SandboxFilesystemPolicy defaultPolicy(Path workingDir) {
        return new SandboxFilesystemPolicy(
            List.of(workingDir),                           // read-write
            List.of(                                       // read-only
                Path.of("/usr"), Path.of("/bin"),
                Path.of("/lib"), Path.of("/opt"),
                Path.of("/etc/ssl"),                       // TLS certs
                Path.of(System.getProperty("java.home"))   // JDK
            ),
            List.of(                                       // denied
                Path.of(System.getProperty("user.home"), ".ssh"),
                Path.of(System.getProperty("user.home"), ".aws"),
                Path.of(System.getProperty("user.home"), ".gnupg"),
                Path.of(System.getProperty("user.home"), ".aceclaw", "credentials"),
                Path.of("/etc/shadow"),
                Path.of("/etc/passwd")
            ),
            Path.of("/tmp/aceclaw-sandbox-" + ProcessHandle.current().pid()),
            true
        );
    }
}
```

### 3.5 Network Sandbox Rules

Network isolation is enforced via a proxy server running outside the sandbox:

- **Default**: All network access blocked
- **Allowed domains**: Configurable allowlist (e.g., `github.com`, `npmjs.org`, `pypi.org`)
- **Private IP blocking**: Block requests to `10.0.0.0/8`, `172.16.0.0/12`, `192.168.0.0/16`, `169.254.0.0/16`
- **HTTPS enforcement**: Reject HTTP URLs except for localhost development
- **Domain fronting protection**: Validate TLS SNI matches requested host
- **New domain requests**: Trigger user permission prompt with domain details

```java
public record SandboxNetworkPolicy(
    Set<String> allowedDomains,
    boolean blockPrivateRanges,
    boolean requireHttps,
    int httpProxyPort,
    int socksProxyPort
) {
    public static SandboxNetworkPolicy restrictive() {
        return new SandboxNetworkPolicy(
            Set.of(),    // No domains allowed by default
            true,        // Block private ranges
            true,        // Require HTTPS
            0,           // Auto-assign proxy port
            0            // Auto-assign SOCKS port
        );
    }
}
```

### 3.6 Resource Limits

```java
public record ResourceLimits(
    Duration maxExecutionTime,    // Default: 120 seconds
    long maxMemoryBytes,          // Default: 512 MB
    int maxProcesses,             // Default: 64
    long maxFileSize,             // Default: 100 MB
    long maxDiskUsage,            // Default: 1 GB
    int maxOpenFiles              // Default: 1024
) {
    public static ResourceLimits defaults() {
        return new ResourceLimits(
            Duration.ofSeconds(120),
            512 * 1024 * 1024L,
            64,
            100 * 1024 * 1024L,
            1024 * 1024 * 1024L,
            1024
        );
    }
}
```

### 3.7 Sandbox Modes

| Mode | Description | Use Case |
|------|------------|----------|
| **auto-allow** | Sandboxed commands auto-approved; unsandboxable commands require permission | Default for interactive use |
| **strict** | All commands sandboxed; unsandboxable commands blocked | CI/CD, untrusted environments |
| **permissive** | Sandbox enforced but all commands go through standard permission flow | Maximum user control |
| **disabled** | No sandboxing; rely on permission system only | Trusted environments, debugging |

---

## 4. Credential Security

### 4.1 API Key Storage

AceClaw uses platform-native credential storage via the `java-keyring` library:

| Platform | Backend | Security Level |
|----------|---------|---------------|
| macOS | Keychain Services (Secure Enclave on Apple Silicon) | Hardware-backed |
| Linux | DBus Secret Service (GNOME Keyring, KDE Wallet) | Software-encrypted |
| Windows | Credential Manager (DPAPI encryption) | OS-level encryption |

```java
public class CredentialManager {
    private final Keyring keyring;
    private static final String SERVICE_NAME = "aceclaw";

    public void storeApiKey(String provider, SecretString apiKey) {
        keyring.setPassword(SERVICE_NAME, provider, new String(apiKey.expose()));
        apiKey.destroy();
        auditLog.record(AuditEvent.credentialStored(provider));
    }

    public SecretString retrieveApiKey(String provider) {
        String raw = keyring.getPassword(SERVICE_NAME, provider);
        if (raw == null) throw new CredentialNotFoundException(provider);
        auditLog.record(AuditEvent.credentialAccessed(provider));
        return new SecretString(raw.toCharArray());
    }

    public void deleteApiKey(String provider) {
        keyring.deletePassword(SERVICE_NAME, provider);
        auditLog.record(AuditEvent.credentialDeleted(provider));
    }
}
```

### 4.2 Environment Variable Isolation

- Agent subprocess inherits a **filtered** environment: only explicitly allowed variables pass through
- Default allowed: `PATH`, `HOME`, `LANG`, `TERM`, `SHELL`, `EDITOR`, tool-specific vars
- Default denied: `AWS_*`, `GITHUB_TOKEN`, `NPM_TOKEN`, `SSH_*`, any variable matching secret patterns
- Configurable via `~/.aceclaw/settings.json` and project `.aceclaw/settings.json`

```java
public class EnvironmentFilter {
    private static final List<Pattern> DENY_PATTERNS = List.of(
        Pattern.compile("(?i).*(?:key|secret|token|password|credential|auth).*"),
        Pattern.compile("AWS_.*"),
        Pattern.compile("GITHUB_TOKEN"),
        Pattern.compile("NPM_TOKEN"),
        Pattern.compile("SSH_AUTH_SOCK"),
        Pattern.compile("GPG_.*")
    );

    private static final Set<String> ALLOW_LIST = Set.of(
        "PATH", "HOME", "LANG", "LC_ALL", "TERM", "SHELL",
        "EDITOR", "VISUAL", "JAVA_HOME", "NODE_PATH",
        "GOPATH", "CARGO_HOME", "RUSTUP_HOME"
    );

    public Map<String, String> filterEnvironment(Map<String, String> env) {
        return env.entrySet().stream()
            .filter(e -> isAllowed(e.getKey()))
            .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    private boolean isAllowed(String name) {
        if (ALLOW_LIST.contains(name)) return true;
        return DENY_PATTERNS.stream().noneMatch(p -> p.matcher(name).matches());
    }
}
```

### 4.3 Credential Redaction in Logs and Context

All text output (logs, conversation history, audit trails) passes through a redaction pipeline:

```java
public class SecretRedactor {
    private static final List<Pattern> SECRET_PATTERNS = List.of(
        // API keys (various formats)
        Pattern.compile("(?i)(sk|pk|api|key|token|secret|password)[_-]?[a-zA-Z0-9]{20,}"),
        // AWS keys
        Pattern.compile("AKIA[0-9A-Z]{16}"),
        // GitHub tokens
        Pattern.compile("gh[pousr]_[A-Za-z0-9_]{36,}"),
        // Generic base64 secrets (high entropy strings)
        Pattern.compile("[A-Za-z0-9+/]{40,}={0,2}"),
        // Bearer tokens
        Pattern.compile("(?i)bearer\\s+[a-zA-Z0-9._~+/=-]+"),
        // JWT tokens
        Pattern.compile("eyJ[a-zA-Z0-9_-]*\\.eyJ[a-zA-Z0-9_-]*\\.[a-zA-Z0-9_-]*")
    );

    public String redact(String input) {
        String result = input;
        for (Pattern pattern : SECRET_PATTERNS) {
            result = pattern.matcher(result).replaceAll("***REDACTED***");
        }
        return result;
    }
}
```

### 4.4 No Credentials in LLM Context

- API keys are injected into HTTP headers at the transport layer, never exposed to the LLM
- Tool outputs containing detected credentials are redacted before entering conversation context
- MCP server credentials are managed by the MCP client library, not passed through the agent

---

## 5. Audit & Compliance

### 5.1 Structured Audit Logging

Every security-relevant event is logged in structured JSON format:

```java
public sealed interface AuditEvent permits
    AuditEvent.ToolInvocation,
    AuditEvent.PermissionDecision,
    AuditEvent.SandboxViolation,
    AuditEvent.CredentialAccess,
    AuditEvent.MCPCommunication,
    AuditEvent.SessionLifecycle {

    Instant timestamp();
    String sessionId();
    String eventType();

    record ToolInvocation(
        Instant timestamp, String sessionId, String eventType,
        String toolName, String toolArgs, PermissionDecision decision,
        Duration executionTime, boolean sandboxed, String outcome
    ) implements AuditEvent {}

    record PermissionDecision(
        Instant timestamp, String sessionId, String eventType,
        String resource, String action, String decision,
        String reason, String decidedBy // "system", "user", "policy"
    ) implements AuditEvent {}

    record SandboxViolation(
        Instant timestamp, String sessionId, String eventType,
        String violationType, String attemptedResource,
        String blockedBy, String command
    ) implements AuditEvent {}

    record CredentialAccess(
        Instant timestamp, String sessionId, String eventType,
        String provider, String operation // "store", "retrieve", "delete"
    ) implements AuditEvent {}

    record MCPCommunication(
        Instant timestamp, String sessionId, String eventType,
        String serverName, String method, String toolName,
        boolean success, Duration latency
    ) implements AuditEvent {}

    record SessionLifecycle(
        Instant timestamp, String sessionId, String eventType,
        String action, // "start", "end", "permission_change"
        Map<String, String> metadata
    ) implements AuditEvent {}
}
```

### 5.2 Audit Log Storage

- **Default**: `~/.aceclaw/audit/` directory with daily rotation
- **Format**: JSON Lines (one event per line) for easy parsing
- **Retention**: Configurable, default 30 days
- **Integrity**: Each log entry includes HMAC-SHA256 signature for tamper detection
- **Enterprise**: Support for external log shipping (syslog, SIEM integration)

### 5.3 Session Recording

Complete session transcripts are recorded for review and replay:

- Full conversation history (user messages, agent responses, tool calls, tool results)
- All permission decisions with context
- Timing information for performance analysis
- Redacted by default (credentials masked); unredacted mode available for security audits

```java
public record SessionRecording(
    String sessionId,
    Instant startTime,
    Instant endTime,
    Path workingDirectory,
    List<ConversationTurn> turns,
    List<AuditEvent> auditEvents,
    PermissionSnapshot initialPermissions,
    PermissionSnapshot finalPermissions
) {
    public Path save(Path auditDirectory) {
        Path file = auditDirectory.resolve(
            "sessions/" + sessionId + ".jsonl"
        );
        // Write each turn and event as JSON line
        return file;
    }
}
```

### 5.4 Compliance Requirements

| Requirement | Implementation |
|------------|----------------|
| **Data Residency** | All processing local; API calls only to configured LLM endpoints |
| **Access Control** | Permission system with audit trail |
| **Data Minimization** | Only necessary context sent to LLM; credential redaction |
| **Right to Deletion** | Session recordings and audit logs deletable per retention policy |
| **Transparency** | Full audit log of all agent actions available to user |
| **SOC 2 Type II** | Structured logging, access controls, change management |
| **GDPR** | No PII sent to LLM without user consent; data processing records |

---

## 6. Java-Specific Security Advantages

### 6.1 Strong Type System

Java's type system prevents entire classes of vulnerabilities at compile time:

- **Sealed interfaces/classes**: Permission hierarchies are exhaustive; the compiler ensures all cases are handled. No "default" fallthrough that could miss a new permission type.
- **Records**: Immutable data classes for security-critical types (permissions, audit events, policies). Prevents accidental mutation.
- **Pattern matching with exhaustiveness**: `switch` expressions on sealed types guarantee all variants are handled.

```java
// Compiler enforces exhaustive handling - adding a new PermissionDecision
// variant causes compilation errors everywhere it's used
public String describe(PermissionDecision decision) {
    return switch (decision) {
        case AutoAllow a -> "Auto-allowed: " + a.reason();
        case PromptOnce p -> "Prompted (cached): " + p.reason();
        case AlwaysAsk a -> "Requires approval: " + a.reason();
        case Deny d -> "Denied: " + d.reason();
        // Compiler error if a new variant is added and not handled here
    };
}
```

### 6.2 Java Platform Module System (JPMS)

JPMS provides strong encapsulation boundaries:

```
module aceclaw.security {
    exports com.aceclaw.security.api;           // Public API only
    exports com.aceclaw.security.permission;    // Permission types

    // Internal implementation is truly hidden
    // No reflection access to internals without explicit opens

    requires aceclaw.core;
    requires java.logging;
    requires transitive aceclaw.audit;
}

module aceclaw.sandbox {
    exports com.aceclaw.sandbox.api;

    requires aceclaw.security;
    requires aceclaw.core;

    // Sandbox internals cannot be accessed by other modules
}

module aceclaw.mcp {
    exports com.aceclaw.mcp.api;
    exports com.aceclaw.mcp.client;

    requires aceclaw.security;
    requires aceclaw.sandbox;

    // MCP protocol internals hidden from other modules
}
```

### 6.3 Runtime Security Instrumentation

Java's bytecode manipulation capabilities enable runtime security monitoring:

- **ByteBuddy**: Instrument tool execution methods to enforce permissions dynamically
- **Java Agents**: Attach security monitoring without modifying application code
- **JFR (Java Flight Recorder)**: Low-overhead security event recording built into the JVM

```java
// ByteBuddy instrumentation for tool execution monitoring
public class SecurityInstrumentation {
    public static void install() {
        new AgentBuilder.Default()
            .type(isSubTypeOf(ToolExecutor.class))
            .transform((builder, type, classLoader, module, domain) ->
                builder.method(named("execute"))
                    .intercept(MethodDelegation.to(SecurityInterceptor.class))
            )
            .installOnByteBuddyAgent();
    }
}

public class SecurityInterceptor {
    @RuntimeType
    public static Object intercept(
        @SuperCall Callable<?> callable,
        @AllArguments Object[] args
    ) throws Exception {
        ToolInvocation invocation = (ToolInvocation) args[0];

        // Enforce permissions
        PermissionDecision decision = permissionEngine.evaluate(invocation);
        if (decision instanceof Deny d) {
            throw new PermissionDeniedException(d.reason());
        }

        // Record audit event
        auditLogger.preExecution(invocation, decision);

        try {
            Object result = callable.call();
            auditLogger.postExecution(invocation, result);
            return result;
        } catch (Exception e) {
            auditLogger.executionFailed(invocation, e);
            throw e;
        }
    }
}
```

### 6.4 Memory Safety

- No buffer overflows, use-after-free, or memory corruption vulnerabilities
- Garbage collection prevents memory leaks from becoming security issues
- `SecretString` with explicit `destroy()` method for zeroing sensitive data
- No native code execution unless explicitly enabled via JNI (which can be restricted)

---

## 7. Secure MCP Communication

### 7.1 Transport Security

All MCP communication uses secure transports:

| Transport | Security |
|-----------|----------|
| **stdio** | Inherently secure (local process communication) |
| **HTTP (Streamable)** | Mandatory TLS 1.3, certificate validation |
| **SSE** | TLS + authentication token in headers |

### 7.2 Authentication

```java
public sealed interface MCPAuthentication permits
    MCPAuthentication.None,
    MCPAuthentication.BearerToken,
    MCPAuthentication.OAuth2,
    MCPAuthentication.MutualTLS {

    /** Local stdio servers - no auth needed */
    record None() implements MCPAuthentication {}

    /** Simple token-based auth for trusted servers */
    record BearerToken(SecretString token) implements MCPAuthentication {}

    /** Full OAuth 2.0 with PKCE for remote servers */
    record OAuth2(
        String authorizationEndpoint,
        String tokenEndpoint,
        String clientId,
        Set<String> scopes
    ) implements MCPAuthentication {}

    /** Mutual TLS for enterprise deployments */
    record MutualTLS(
        Path clientCertificate,
        Path clientKey,
        Path caCertificate
    ) implements MCPAuthentication {}
}
```

### 7.3 Input Validation

All MCP messages are validated before processing:

- **Schema validation**: JSON-RPC messages validated against MCP specification schema
- **Size limits**: Maximum message size (default: 10 MB), maximum tool description length (default: 10,000 chars)
- **Content sanitization**: Tool descriptions and responses checked for injection patterns
- **Type safety**: Java records ensure MCP message types are correctly structured at compile time

```java
public class MCPInputValidator {
    private static final int MAX_MESSAGE_SIZE = 10 * 1024 * 1024;
    private static final int MAX_TOOL_DESCRIPTION_LENGTH = 10_000;

    public MCPMessage validate(byte[] rawMessage) throws MCPValidationException {
        if (rawMessage.length > MAX_MESSAGE_SIZE) {
            throw new MCPValidationException("Message exceeds size limit");
        }

        MCPMessage message = deserialize(rawMessage);

        // Validate JSON-RPC structure
        validateJsonRpcStructure(message);

        // Validate MCP-specific fields
        if (message instanceof ToolListResult tlr) {
            for (ToolDefinition tool : tlr.tools()) {
                validateToolDescription(tool);
            }
        }

        return message;
    }

    private void validateToolDescription(ToolDefinition tool) {
        if (tool.description().length() > MAX_TOOL_DESCRIPTION_LENGTH) {
            throw new MCPValidationException(
                "Tool description exceeds length limit: " + tool.name()
            );
        }
        // Check for suspicious instruction patterns in tool descriptions
        if (INJECTION_DETECTOR.containsSuspiciousPatterns(tool.description())) {
            auditLog.warn("Suspicious patterns in tool description: " + tool.name());
        }
    }
}
```

### 7.4 MCP Server Lifecycle Security

```
Installation -> Verification -> Configuration -> Runtime -> Audit
     |               |               |              |          |
     v               v               v              v          v
  User consent   Signature check   Permissions   Sandbox    Logging
  Command review Publisher verify  Tool allowlist  Isolation  Events
  SBOM generate  Hash validation   Resource limits Monitoring Reports
```

---

## 8. Self-Learning Security

### 8.1 Memory File Security

AceClaw's self-learning system (MEMORY.md and related files) requires protection against tampering:

```java
public class SecureMemoryStore {
    private final Path memoryDirectory;
    private final SecretKey hmacKey;

    /**
     * Write memory file with integrity signature.
     * The signature file (.sig) stores HMAC-SHA256 of the content.
     */
    public void writeMemory(String filename, String content) {
        Path filePath = memoryDirectory.resolve(filename);
        Path sigPath = memoryDirectory.resolve(filename + ".sig");

        // Write content
        Files.writeString(filePath, content, StandardCharsets.UTF_8);

        // Write HMAC signature
        String hmac = computeHMAC(content);
        Files.writeString(sigPath, hmac, StandardCharsets.UTF_8);

        auditLog.record(AuditEvent.memoryWrite(filename));
    }

    /**
     * Read memory file with integrity verification.
     * Returns empty if tampered or missing.
     */
    public Optional<String> readMemory(String filename) {
        Path filePath = memoryDirectory.resolve(filename);
        Path sigPath = memoryDirectory.resolve(filename + ".sig");

        if (!Files.exists(filePath) || !Files.exists(sigPath)) {
            return Optional.empty();
        }

        String content = Files.readString(filePath, StandardCharsets.UTF_8);
        String storedHmac = Files.readString(sigPath, StandardCharsets.UTF_8);
        String computedHmac = computeHMAC(content);

        if (!MessageDigest.isEqual(
                storedHmac.getBytes(), computedHmac.getBytes())) {
            auditLog.warn(AuditEvent.memoryTamperDetected(filename));
            return Optional.empty();
        }

        return Optional.of(content);
    }
}
```

### 8.2 Memory Content Sanitization

Memory files can be vectors for persistent prompt injection:

- Content written to memory is sanitized for injection patterns
- Memory files are read with `TOOL_UNTRUSTED` trust level
- Maximum memory file size enforced (default: 50 KB per file, 500 KB total)
- Memory changes audited and reviewable by user

### 8.3 Memory File Permissions

- Memory directory: `700` (owner read/write/execute only)
- Memory files: `600` (owner read/write only)
- Signature files: `400` (owner read only)
- Memory directory location: `~/.aceclaw/projects/<project-hash>/memory/`

---

## 9. Hook System Security

### 9.1 Security-Relevant Hooks

Following the Claude Code hook system architecture, AceClaw's hook system provides deterministic security enforcement at lifecycle points:

| Hook Event | Security Purpose | Example |
|------------|-----------------|---------|
| **PreToolUse** | Block dangerous tool invocations before execution | Block `rm -rf`, validate file paths |
| **PostToolUse** | Audit completed tool invocations | Log all bash commands, check for credential leaks |
| **PermissionRequest** | Custom permission logic | Integrate with enterprise IAM systems |
| **SessionStart** | Initialize security context | Load project-specific security policies |
| **SubagentStart** | Restrict subagent capabilities | Enforce read-only for exploration agents |
| **Stop** | Quality gates before session end | Run security scan, verify no sensitive files modified |
| **TaskCompleted** | Validate work product security | Check for introduced vulnerabilities |

### 9.2 Hook Security Constraints

- Hooks execute with the same privileges as the AceClaw process (not sandboxed by default)
- Hook configuration files must have restrictive permissions (`600`)
- Hooks from plugins execute in sandbox by default
- Hook commands are displayed to users during configuration for review
- Hook input modification (PreToolUse) requires explicit opt-in per project
- Malicious hooks in shared `.aceclaw/settings.json` are a supply chain vector; warn users when project hooks differ from global settings

### 9.3 Hook-Based Security Enforcement

```java
public sealed interface HookResult permits
    HookResult.Allow,
    HookResult.Block,
    HookResult.Modify {

    record Allow(String feedback) implements HookResult {}
    record Block(String reason) implements HookResult {}
    record Modify(ToolInvocation modified, String reason) implements HookResult {}
}

public class SecurityHookEngine {
    /**
     * Evaluate all security hooks for a tool invocation.
     * If any hook blocks, the invocation is denied.
     * Hooks run in parallel; first Block result wins.
     */
    public HookResult evaluate(HookEvent event, ToolInvocation invocation) {
        List<CompletableFuture<HookResult>> futures = matchingHooks(event, invocation)
            .stream()
            .map(hook -> CompletableFuture.supplyAsync(() -> hook.execute(invocation)))
            .toList();

        return futures.stream()
            .map(CompletableFuture::join)
            .filter(r -> r instanceof Block)
            .findFirst()
            .orElse(new HookResult.Allow("All hooks passed"));
    }
}
```

---

## 10. Security Configuration Reference

### 10.1 Default Security Profile

```json
{
  "security": {
    "sandbox": {
      "mode": "auto-allow",
      "filesystem": {
        "readOnlyRoot": true,
        "writablePaths": ["${workingDirectory}"],
        "deniedPaths": ["~/.ssh", "~/.aws", "~/.gnupg"]
      },
      "network": {
        "blockPrivateRanges": true,
        "requireHttps": true,
        "allowedDomains": []
      },
      "resources": {
        "maxExecutionTimeSeconds": 120,
        "maxMemoryMB": 512,
        "maxProcesses": 64
      }
    },
    "permissions": {
      "defaultTier": "prompt-once",
      "destructiveCommands": "always-ask",
      "fileWriteOutsideProject": "always-ask",
      "mcpServerInstall": "always-ask",
      "sensitivePathAccess": "deny"
    },
    "credentials": {
      "storage": "system-keyring",
      "environmentFilter": "strict",
      "redactionEnabled": true
    },
    "audit": {
      "enabled": true,
      "directory": "~/.aceclaw/audit",
      "retentionDays": 30,
      "integrityChecks": true,
      "sessionRecording": true
    },
    "mcp": {
      "requireTLS": true,
      "validateToolDescriptions": true,
      "maxMessageSize": 10485760,
      "serverAllowlist": []
    },
    "memory": {
      "integrityChecks": true,
      "maxFileSizeKB": 50,
      "maxTotalSizeKB": 500,
      "sanitizeContent": true
    }
  }
}
```

### 10.2 Enterprise Security Profile

Enterprise deployments can enforce security policies via managed settings:

```json
{
  "managedSecurity": {
    "enforceVersion": "1.0",
    "sandbox": {
      "mode": "strict",
      "allowUnsandboxedCommands": false
    },
    "permissions": {
      "maxAutonomy": "prompt-once",
      "blockDestructiveCommands": true,
      "requireApprovalForNewMCPServers": true
    },
    "audit": {
      "enabled": true,
      "minimumRetentionDays": 90,
      "externalSink": "syslog://security.corp.example.com:514",
      "sessionRecording": true
    },
    "network": {
      "allowedDomains": ["github.corp.example.com", "registry.corp.example.com"],
      "blockPublicDomains": false,
      "proxyUrl": "https://proxy.corp.example.com:8080"
    }
  }
}
```

---

## 11. Security Testing Strategy

### 11.1 Automated Security Tests

| Test Category | Description | Frequency |
|--------------|-------------|-----------|
| **Permission bypass tests** | Attempt to access resources without proper permissions | Every build |
| **Sandbox escape tests** | Attempt filesystem/network access from sandboxed processes | Every build |
| **Injection pattern tests** | Feed known prompt injection patterns through all input paths | Every build |
| **Credential leak tests** | Verify credentials never appear in logs or context | Every build |
| **MCP fuzzing** | Send malformed MCP messages and verify safe handling | Weekly |
| **Dependency audit** | Scan dependencies for known vulnerabilities | Daily (CI) |
| **Penetration testing** | Professional security assessment | Quarterly |

### 11.2 Security Invariants

The following properties must always hold:

1. **No credential in context**: API keys and tokens never appear in LLM conversation context
2. **Sandbox containment**: A sandboxed process cannot access files or networks outside its policy
3. **Permission enforcement**: Every tool invocation passes through the permission resolver
4. **Audit completeness**: Every tool invocation generates an audit event
5. **Fail closed**: If the permission system encounters an error, the operation is denied
6. **Type exhaustiveness**: All permission variants are handled (enforced by compiler)

---

## 12. Comparison with Existing Solutions

| Security Feature | AceClaw (Java) | Claude Code (TS) | OpenClaw (TS) |
|-----------------|---------------|------------------|---------------|
| Type-safe permissions | Sealed interfaces (compile-time) | Runtime checks | Runtime checks |
| Sandbox | bubblewrap/Seatbelt | bubblewrap/Seatbelt | Docker containers |
| Permission tiers | 4-tier sealed hierarchy | 3 modes | Profile-based policies |
| Credential storage | System keyring (java-keyring) | System keyring | Environment vars |
| Module encapsulation | JPMS modules | npm packages | npm packages |
| Audit logging | Structured + HMAC integrity | File-based | Configurable |
| MCP security | TLS + OAuth2 + mTLS + validation | TLS + OAuth2 | TLS + allowlists |
| Memory integrity | HMAC-SHA256 signatures | None | None |
| Runtime instrumentation | ByteBuddy + JFR | N/A | N/A |
| Exhaustive permission handling | Compiler-enforced | Manual | Manual |

---

## 13. Implementation Priorities

### Phase 1: Foundation (MVP)
1. Permission system with 4-tier model (sealed interfaces)
2. Basic filesystem sandbox (read-only outside project directory)
3. Credential storage via java-keyring
4. Structured audit logging
5. Secret redaction in logs

### Phase 2: Full Sandbox
1. OS-level sandbox (bubblewrap/Seatbelt integration)
2. Network proxy and domain allowlist
3. Resource limits enforcement
4. Sandbox modes (auto-allow, strict, permissive)

### Phase 3: Enterprise
1. MCP server security (TLS, OAuth2, input validation)
2. Managed security settings
3. External audit log shipping
4. Session recording and replay
5. Memory file integrity

### Phase 4: Advanced
1. ByteBuddy runtime instrumentation
2. JFR security events
3. Automated injection detection
4. MCP server capability auditing
5. Security health check command (`aceclaw doctor`)

---

## References

- [OpenClaw Security Documentation](https://docs.openclaw.ai/gateway/security)
- [Claude Code Sandboxing](https://code.claude.com/docs/en/sandboxing)
- [MCP Security Best Practices](https://modelcontextprotocol.io/specification/draft/basic/security_best_practices)
- [MCP Security Vulnerabilities - Practical DevSecOps](https://www.practical-devsecops.com/mcp-security-vulnerabilities/)
- [OpenClaw Tool Security and Sandboxing - DeepWiki](https://deepwiki.com/openclaw/openclaw/6.2-tool-security-and-sandboxing)
- [Securing MCP: OAuth, mTLS, Zero Trust](https://dasroot.net/posts/2026/02/securing-model-context-protocol-oauth-mtls-zero-trust/)
- [CoSAI MCP Security White Paper](https://adversa.ai/blog/mcp-security-whitepaper-2026-cosai-top-insights/)
- [Java Security Features (2025)](https://javapro.io/2025/12/30/security-in-the-age-of-java-25-new-language-tools-for-safer-code/)
- [java-keyring library](https://github.com/javakeyring/java-keyring)
- [Anthropic Claude Code Sandboxing](https://www.anthropic.com/engineering/claude-code-sandboxing)
- [Prompt Injection in AI Coding Editors](https://arxiv.org/html/2509.22040v1)
- [MCP Attack Vectors - Palo Alto Unit 42](https://unit42.paloaltonetworks.com/model-context-protocol-attack-vectors/)
