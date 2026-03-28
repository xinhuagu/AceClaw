# Multi-Session Model

AceClaw follows a **one daemon, many sessions** architecture. A single persistent daemon process manages all sessions, while each CLI/TUI window gets its own independent session.

## Installation

```bash
curl -fsSL https://raw.githubusercontent.com/xinhuagu/AceClaw/main/install.sh | sh
```

Downloads the latest pre-built release to `~/.aceclaw/`. Only requires Java 21 runtime.

After installation: `aceclaw`, `aceclaw-tui`, `aceclaw-restart`, `aceclaw-update`.

### Update

```bash
aceclaw-update
```

Checks for a newer release, downloads and replaces if available. Refuses to update if the daemon has active sessions — stop all sessions first.

## Scripts

### `dev.sh` / `aceclaw-dev` — Development Entrypoint

- Rebuilds the CLI distribution from source
- Stops and restarts the daemon (destructive — interrupts all active sessions)
- Auto-runs benchmark checks on feature branches (`--auto` by default)
- Use when you are developing AceClaw itself and want benchmark validation

### `restart.sh` / `aceclaw-restart` — Quick Restart

- Rebuilds the CLI distribution from source
- Stops and restarts the daemon (destructive — interrupts all active sessions)
- Never runs benchmarks — fastest way to restart
- Use when you just want a clean daemon restart without waiting for checks

### `tui.sh` / `aceclaw-tui` — Non-Destructive TUI Entrypoint

- Connects to the running daemon, or starts one if none exists
- Never stops or restarts the daemon
- Never runs benchmarks
- Use when you want to open another interactive window against the same daemon

### Recommended Workflow

1. Start with `./restart.sh` or `./dev.sh` in your primary terminal (rebuilds + starts daemon)
2. Open additional TUI windows with `./tui.sh` in other terminals or workspaces
3. Each TUI gets its own independent session and conversation history
4. If you need to restart the daemon, use `./restart.sh` (fast) or `./dev.sh` (with benchmarks)
5. All scripts warn about active sessions before restarting the daemon

## Workspace Exclusivity

Each workspace (project directory) can have at most **one active TUI session** at a time. This prevents confusion from two terminals operating on the same files simultaneously.

- If you run `./tui.sh` in a workspace that already has an active TUI, it will refuse with a clear error message
- The lock is based on an interactive attachment, not on the session itself — sessions can persist beyond TUI detachment
- Stale attachments (from crashed CLIs) are automatically cleaned up after 2 minutes via heartbeat timeout

## State Model

For a complete classification of every memory store, concurrency guarantees, and promotion paths, see [Memory Ownership Model](memory-ownership.md).

### Session-Local State

Each TUI session owns:

- Conversation messages (chat history) — `SessionHistoryStore`
- Context window contents — in-memory only
- Active tasks and their status — `ResumeCheckpointStore`, `FilePlanCheckpointStore`
- Error/pattern detection for current session

### Workspace-Shared State

All sessions targeting the same project directory share:

- Workspace memory — `MarkdownMemoryStore` (MEMORY.md, topic files)
- Daily journal — `DailyJournal`
- Learning metrics — `LearningExplanationStore`, `LearningValidationStore`
- Skill drafts — `SkillDraftGenerator`, `SessionSkillPacker`
- Promoted rules — `CorrectionRulePromoter` → ACECLAW.md

### Global/Daemon-Shared State

The daemon manages state shared across all sessions and workspaces:

- Global memory — `AutoMemoryStore` (global.jsonl)
- Learning candidates — `CandidateStore`
- Historical index — `HistoricalLogIndex`
- User-scoped skills — `SkillMetricsStore`, `SkillRefinementEngine`
- Cron scheduler, deferred actions, MCP clients
- Tool registry, permission manager, system prompt

## Session Identity

Each session is identified by:

- **Session ID** (UUID): visible in the startup banner and status line as `sid=<first 8 chars>`
- **Workspace**: the canonical project directory path, visible in the startup banner (full path) and status line as `ws=<directory name>`

The `daemon status` command shows the count of active sessions and active TUI attachments.

## Architecture

```
Terminal 1 (dev.sh)          Terminal 2 (tui.sh)         Terminal 3 (tui.sh)
  CLI [session-abc]            CLI [session-def]           CLI [session-ghi]
        |                           |                           |
        +---------- UDS -----------+---------- UDS ------------+
                                    |
                              AceClaw Daemon
                    +---------------------------+
                    | SessionManager            |
                    |   session-abc (workspace A)|
                    |   session-def (workspace B)|
                    |   session-ghi (workspace C)|
                    | WorkspaceAttachmentRegistry|
                    |   workspace A -> abc       |
                    |   workspace B -> def       |
                    |   workspace C -> ghi       |
                    | Shared: memory, learning,  |
                    |   tools, MCP, cron, ...    |
                    +---------------------------+
```
