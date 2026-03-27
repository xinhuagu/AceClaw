# Multi-Session Model

AceClaw follows a **one daemon, many sessions** architecture. A single persistent daemon process manages all sessions, while each CLI/TUI window gets its own independent session.

## Scripts

### `dev.sh` — Development Entrypoint

- Rebuilds the CLI distribution from source
- Stops and restarts the daemon (destructive — interrupts all active sessions)
- Optionally runs benchmark checks (`--check`, `--baseline`, `--auto`)
- Use when you are developing AceClaw itself or need a clean daemon restart

### `tui.sh` — Non-Destructive TUI Entrypoint

- Connects to the running daemon, or starts one if none exists
- Never stops or restarts the daemon
- Never runs benchmarks
- Use when you want to open another interactive window against the same daemon

### Recommended Workflow

1. Start with `./dev.sh` in your primary terminal (rebuilds + starts daemon)
2. Open additional TUI windows with `./tui.sh` in other terminals or workspaces
3. Each TUI gets its own independent session and conversation history
4. If you need to restart the daemon, use `./dev.sh` again (will warn about active sessions)

## Workspace Exclusivity

Each workspace (project directory) can have at most **one active TUI session** at a time. This prevents confusion from two terminals operating on the same files simultaneously.

- If you run `./tui.sh` in a workspace that already has an active TUI, it will refuse with a clear error message
- The lock is based on an interactive attachment, not on the session itself — sessions can persist beyond TUI detachment
- Stale attachments (from crashed CLIs) are automatically cleaned up after 2 minutes via heartbeat timeout

## State Model

### Session-Local State

Each TUI session owns:

- Conversation messages (chat history)
- Context window contents
- Active tasks and their status
- Resume checkpoints

### Daemon-Shared State

The daemon manages state shared across all sessions:

- Memory stores (auto-memory, markdown memory, candidate state)
- Learning engines (self-improvement, pattern detection, correction rules)
- Cron scheduler and deferred actions
- MCP client connections
- Tool registry and permission manager
- System prompt and model configuration

### Workspace-Shared State

Some state is shared by all sessions targeting the same workspace:

- Workspace-level memory and skill drafts
- Historical session snapshots
- Resume checkpoint routing (sessions can resume within the same workspace)

## Session Identity

Each session is identified by:

- **Session ID** (UUID): visible in the startup banner (first 8 characters)
- **Workspace**: the canonical project directory path, visible in the banner and status line

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
