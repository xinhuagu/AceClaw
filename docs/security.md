# AceClaw Security Model

> Version 1.1 | 2026-05-01
>
> See also [runtime-governance.md](runtime-governance.md) for AceClaw's positioning around where governance lives (protocol vs. connector vs. runtime). This document covers the security mechanisms; that one covers the *layer* they operate at.

AceClaw defends across five dimensions: local surface isolation, permission enforcement, memory integrity, content boundaries, and data protection.

---

## 1. Sealed Local Surfaces

The daemon exposes exactly two local surfaces, each with its own isolation model:

- **Unix Domain Socket** (`~/.aceclaw/aceclaw.sock`) — primary CLI transport. POSIX-permission isolated (700, owner-only). The CLI talks to the daemon over JSON-RPC 2.0 here.
- **Loopback WebSocket bridge** (default `127.0.0.1:3141`) — optional, off by default. When enabled (`webSocket.enabled: true` in `~/.aceclaw/config.json`), it lets the React dashboard receive a fan-out of session events and post permission decisions. Bound to localhost only and gated by an explicit `allowedOrigins` list.

There are no HTTP / REST listeners, no remote endpoints, and no outbound network traffic except the LLM provider call (which is gated by your configured API key).

### UDS protections

| Component | Security Property |
|-----------|-------------------|
| `UdsListener` | Binds UDS with POSIX 700 permissions (owner-only connect) |
| `DaemonLock` | OS-level file lock prevents concurrent daemon instances; PID file is POSIX 600 |
| Socket cleanup | Stale socket files removed on startup to prevent ghost connections |
| Connection isolation | Each accepted connection runs on a dedicated virtual thread |

### WebSocket bridge protections

The bridge is opt-in for a reason: opening any TCP listener (even loopback) widens the attack surface compared to UDS-only operation. To keep the additional surface narrow:

| Component | Security Property |
|-----------|-------------------|
| Default state | Disabled. Must be explicitly enabled in config to listen at all |
| Bind address | Always `127.0.0.1` (loopback) — never reachable from off-host |
| `allowedOrigins` | Empty by default → all browsers rejected (HTTP 1008 close). Origin must be added explicitly for the dashboard to connect |
| Non-browser clients | Tools without an `Origin` header (curl, Java HTTP client) are accepted, since cross-site browser attacks cannot suppress that header |
| Per-session filtering | The dashboard reducer filters envelopes by sessionId — a tab on session B cannot observe session A's events even though both arrive on the same socket |
| Permission round-trip | Dashboard approve/deny is gated by the same `PermissionManager` that gates CLI decisions; the daemon enforces a session-id guard so a tab on one session can't approve another's tool calls |

---

## 2. Sealed Permission System

Tool operations are classified by risk level via `PermissionLevel`:

| Level | Auto-approved? | Tools |
|-------|----------------|-------|
| `READ` | Yes | `read_file`, `glob`, `grep` |
| `WRITE` | Needs approval | `write_file`, `edit_file` |
| `EXECUTE` | Needs approval | `bash` |
| `DANGEROUS` | Always needs approval | Destructive operations (`rm -rf`, `git push --force`) |

### Permission Decision (Sealed Interface)

```java
public sealed interface PermissionDecision
    permits Approved, Denied, NeedsUserApproval {}
```

All decisions are explicit — no silent failures. The compiler enforces exhaustive handling.

### Permission Modes

| Mode | Behavior |
|------|----------|
| `normal` (default) | Prompts for WRITE/EXECUTE/DANGEROUS |
| `accept-edits` | Auto-accepts WRITE, prompts EXECUTE/DANGEROUS |
| `plan` | Read-only — denies all WRITE/EXECUTE/DANGEROUS |
| `auto-accept` | Accepts everything (use with caution) |

### Session-Level Approvals

`PermissionManager` tracks per-session blanket approvals via `ConcurrentHashSet`. Once a user approves a tool for the session, subsequent calls skip the prompt. Approvals are cleared on session close.

### Sub-Agent Privilege Isolation

Sub-agents receive **filtered tool registries** — they cannot access tools beyond their granted permission level, preventing privilege escalation.

---

## 3. Memory Integrity (HMAC-SHA256)

Every persisted memory entry is signed with HMAC-SHA256.

| Property | Implementation |
|----------|----------------|
| **Signing** | `MemorySigner.sign()` computes `HMAC-SHA256(id\|category\|content\|tags\|createdAt\|source)` |
| **Verification** | `MessageDigest.isEqual()` — constant-time comparison prevents timing attacks |
| **Key storage** | 32-byte random secret at `~/.aceclaw/memory/memory.key` (POSIX 600) |
| **Tamper handling** | Corrupted entries silently skipped on load, warning logged |
| **Mutable field exclusion** | `accessCount` and `lastAccessedAt` are excluded from the signable payload so that read-tracking does not invalidate signatures |

### Candidate Store Integrity

`CandidateStore` also uses HMAC signing for learning candidates (`candidates.jsonl`). All transitions are logged to `candidate-transitions.jsonl` for auditability.

---

## 4. Content Boundaries

### System Prompt Budget

`SystemPromptBudget` prevents the system prompt from consuming excessive context:

| Cap | Default | Purpose |
|-----|---------|---------|
| Per-tier | 20,000 chars | Prevents any single memory tier from dominating |
| Total | 150,000 chars | Caps the entire assembled system prompt |

For smaller context windows, `forContextWindow()` scales the budget proportionally (up to 25% of effective window).

### Truncation Strategy (TierTruncator)

When a tier exceeds its cap, `TierTruncator` applies 70/20/10 truncation:
- **70%** head (preserves core instructions)
- **20%** tail (preserves recent additions)
- **10%** marker (`<!-- [TRUNCATED] Original: N chars -->`)

Protected tiers (Soul priority=100, Managed Policy priority=90) are never truncated.

### Tool Result Truncation

Tool outputs are capped at **30,000 characters** with a 40/60 head/tail split. This prevents a single tool result from flooding the context window.

### 8-Tier Priority Ordering

Memory tiers are loaded in strict priority order (100 → 50). Human-authored content (Tiers 1-5) always outranks agent-generated memory (Tiers 6-8), ensuring operator intent takes precedence over learned knowledge.

---

## 5. Data Protection

| Threat | Mitigation |
|--------|------------|
| **Cross-project leakage** | SHA-256 hashed workspace paths under `~/.aceclaw/workspaces/`. Separate JSONL per project. |
| **Unbounded growth** | Journal: 500 lines/day. MEMORY.md: 50KB/file, 500KB/workspace. Consolidator: dedup + merge + prune. |
| **Path traversal** | `PathResolver.resolve()` normalizes paths. `RuleEngine` and `MarkdownMemoryStore` validate file names (no `/`, `\`, `..`). |
| **Read-before-write** | `WriteFileTool` requires existing files to be read first, preventing blind overwrites. |
| **Process isolation** | `BashExecTool`: 120s default timeout (max 600s), output capped at 30K chars, stdin redirected to `/dev/null`, process tree destruction on timeout. |
| **Memory injection** | Memories are plain text (not executable), loaded as markdown sections in the system prompt. |
| **Stale memory pollution** | `MemoryConsolidator` prunes entries >90 days with zero access. Similarity merge (>80% Jaccard) prevents near-duplicates. |
| **Key file exposure** | POSIX 600 on signing keys and PID files. |

---

## Security Design Principles

| Principle | How AceClaw implements it |
|-----------|---------------------------|
| **Defense in depth** | Permission system + UDS isolation + loopback-bound WebSocket with origin allowlist + HMAC signing + content budgets |
| **Fail-safe defaults** | Only READ auto-approved; all writes need explicit approval |
| **Least privilege** | Sub-agents get filtered tool registries; socket/PID files owner-only |
| **Sealed exhaustiveness** | `PermissionDecision`, `PermissionLevel`, `MemoryTier` — compiler enforces complete handling |
| **Transparency** | Compaction reports reduction %; truncation marked with comments; permission checks logged |
| **Constant-time verification** | HMAC comparison via `MessageDigest.isEqual()` prevents timing side-channels |
