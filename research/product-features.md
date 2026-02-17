# Chelava - Product Features & Differentiation Strategy

## 1. Product Vision

**Chelava** - A high-performance, secure, self-learning AI coding agent built on Java, designed for enterprise developers who need reliability, security, and speed.

### Vision Statement

Chelava reimagines the AI coding agent experience by leveraging the Java ecosystem's strengths: type safety, concurrency, enterprise maturity, and GraalVM native compilation. While existing agents like Claude Code (TypeScript/Node.js) have proven the interactive AI coding paradigm, Chelava delivers the same powerful workflow with the performance characteristics, security guarantees, and operational maturity that enterprise environments demand.

### Why Java?

The choice of Java is not arbitrary -- it is a strategic differentiator:

- **Startup performance**: GraalVM native image eliminates JVM cold start, delivering <50ms startup
- **Concurrency model**: Virtual threads (Project Loom) enable massive parallelism without callback complexity
- **Type system**: Sealed interfaces, records, and pattern matching create self-documenting, error-resistant code
- **Enterprise ecosystem**: Decades of production-grade libraries for logging, monitoring, security, and integration
- **Developer population**: Java remains the #1 or #2 most-used enterprise language globally

---

## 2. Target User Personas

### Persona 1: Solo Developer ("Alex")
- **Role**: Full-stack developer, freelancer or startup engineer
- **Pain Points**: Wants fast AI assistance without heavy IDE plugins; needs multi-model support to manage API costs
- **Needs**: Quick startup, easy installation (single binary), intuitive CLI, project-level config
- **Chelava Value**: Instant startup via native image, multi-LLM switching, lightweight resource usage

### Persona 2: Team Lead ("Jordan")
- **Role**: Engineering team lead at a mid-size company (20-50 engineers)
- **Pain Points**: Needs consistent tooling across the team; wants to enforce coding standards through automation
- **Needs**: Shared project configuration, hook system for CI/CD integration, permission controls
- **Chelava Value**: Project-level .chelava/ config, hook system, permission model, team-wide tool policies

### Persona 3: Enterprise Architect ("Morgan")
- **Role**: Principal engineer or architect at a Fortune 500 company
- **Pain Points**: Security compliance, audit requirements, on-premise LLM deployment, monitoring integration
- **Needs**: SSO/RBAC, audit logging, JPMS module boundaries, Ollama/local model support, Micrometer metrics
- **Chelava Value**: Enterprise-grade security model, JVM sandbox, JPMS encapsulation, monitoring dashboards, self-hosted LLM support

---

## 3. Core Features (MVP - v1.0)

### 3.1 Interactive CLI with Rich Terminal UI

**Description**: A responsive, keyboard-driven terminal interface that supports syntax-highlighted code display, streaming LLM output, multi-line input editing, and status indicators.

**Key Capabilities**:
- Rich text rendering with ANSI color support
- Streaming token display from LLM responses
- Multi-line input with Emacs/Vi keybindings
- Progress indicators for long-running tool operations
- Markdown rendering in terminal (code blocks, tables, lists)
- Command history with search (Ctrl+R)
- Tab completion for commands and file paths
- Compact and verbose display modes

**Technical Approach**: JLine 3 for terminal interaction, commonmark-java for Markdown parsing with a custom ANSI renderer for rich terminal output. Picocli for command-line argument parsing and subcommand routing.

### 3.2 Multi-LLM Support

**Description**: First-class support for multiple LLM providers with a unified interface, enabling users to switch models based on task complexity, cost, or privacy requirements.

**Supported Providers (MVP)**:
- **Anthropic Claude**: Claude Opus 4.6, Sonnet 4.5, Haiku 4.5 (primary)
- **OpenAI**: GPT-4o, GPT-4o-mini, o1/o3 series
- **Local Models via Ollama**: Llama, CodeLlama, DeepSeek Coder, Qwen
- **AWS Bedrock / Azure OpenAI**: Enterprise cloud endpoints

**Key Capabilities**:
- Unified chat completion interface abstracting provider differences
- Model-specific parameter tuning (temperature, max tokens, system prompts)
- Streaming response support across all providers
- Automatic fallback chains (e.g., try Claude -> fallback to local Ollama)
- Cost tracking and token usage reporting
- API key management with secure credential storage

### 3.3 Tool System

**Description**: A modular, extensible tool framework enabling the AI agent to interact with the development environment through well-defined operations.

**Built-in Tools (MVP)**:

| Tool | Description | Safety Level |
|------|-------------|-------------|
| `ReadFile` | Read file contents with line range support, multimodal (images, PDFs) | Safe (auto-approve) |
| `WriteFile` | Create or overwrite files (enforces read-before-write) | Requires approval |
| `EditFile` | Exact string replacement with uniqueness validation | Requires approval |
| `MultiEdit` | Batch find-and-replace operations in a single call | Requires approval |
| `BashExec` | Execute shell commands with timeout and persistent session | Requires approval |
| `BashOutput` | Retrieve incremental output from background shells | Safe |
| `GlobSearch` | Fast file pattern matching (sorted by modification time) | Safe |
| `GrepSearch` | Content search built on regex with multiline support | Safe |
| `WebFetch` | Retrieve and process web content with AI summarization | Requires approval |
| `WebSearch` | Web queries with domain filtering | Requires approval |
| `ListDirectory` | Directory listing with pattern filtering | Safe |
| `NotebookEdit` | Modify Jupyter notebook cells (replace, insert, delete) | Requires approval |
| `AskUser` | Structured Q&A with multiple questions and options | Safe |

**Tool Framework Design**:
- Tools defined as sealed interfaces with typed input/output records
- Automatic input validation via annotation processing
- Timeout and resource limit enforcement (default 120s, max 600s for Bash)
- Output truncation for large results (30K char limit)
- Execution audit logging
- Parallel tool execution via virtual threads (independent tools execute concurrently)
- Tool result caching for repeated reads
- Read-before-write enforcement to prevent blind overwrites
- Edit uniqueness validation (exact string must be unique or explicit `replace_all`)
- Absolute path requirement for all file operations

### 3.4 MCP (Model Context Protocol) Support

**Description**: Full implementation of the MCP protocol for integrating external tool servers, enabling extensibility beyond built-in tools.

**Key Capabilities**:
- MCP client supporting stdio and SSE transports
- Server discovery and capability negotiation
- Tool, resource, and prompt template support
- Automatic schema validation for MCP tool inputs
- Connection lifecycle management (connect, reconnect, graceful shutdown)
- Configuration via .chelava/mcp-servers.json

### 3.5 Conversation Management

**Description**: Intelligent conversation lifecycle management with context window optimization, following the proven ReAct (Reason + Act) agent loop pattern.

**Agent Loop (ReAct Pattern)**:
1. User provides a task or question
2. Agent creates a step-by-step plan
3. Agent reasons about the next action needed
4. Agent executes a tool (file read, bash command, search, etc.)
5. Agent analyzes the tool result
6. Loop continues until task is complete or agent needs user input

**Key Capabilities**:
- Conversation history persistence as JSONL transcript files
- Automatic context compression when approaching token limits (configurable threshold, default ~92%)
- Manual compaction via `/compact` command for strategic breakpoints
- System prompt management with project-level customization (CHELAVA.md hierarchy)
- Conversation forking and branching
- Session resume capability with persistent session IDs
- Multi-turn tool use with result incorporation
- Image/screenshot support in conversations (multimodal)
- Automatic session cleanup based on configurable retention period

### 3.6 Project-Level Configuration (.chelava/ Directory)

**Description**: Per-project configuration supporting team-wide standards and individual customization.

**Directory Structure**:
```
.chelava/
  CHELAVA.md          # Project instructions (like CLAUDE.md)
  config.json         # Model, tool, and behavior settings
  mcp-servers.json    # MCP server configurations
  hooks/              # Pre/post execution hooks
  memory/             # Agent memory store
  templates/          # Custom prompt templates
```

**Configuration Hierarchy** (lowest to highest priority):
1. System defaults
2. Global user config (~/.chelava/config.json)
3. Project config (.chelava/config.json)
4. Environment variables
5. Command-line arguments

### 3.7 Permission System

**Description**: A multi-modal permission system that balances agent autonomy with user safety, supporting different trust levels for different workflows.

**Permission Modes**:

| Mode | Behavior | Use Case |
|------|----------|----------|
| **Normal** (default) | Prompts for every dangerous operation | Standard interactive use |
| **Accept Edits** | Auto-accepts file edits, prompts for other ops | Trusted editing workflows |
| **Plan Mode** | Read-only operations only, no modifications | Research and planning |
| **Auto-accept** | Eliminates permission prompts for the session | Trusted automation |
| **Delegate** | Coordination-only (for multi-agent team leads) | Multi-agent orchestration |

**Permission Granularity**:
- **Auto-approve**: Read-only operations (file reads, searches, directory listing)
- **User-approve**: Write operations (file edits, bash commands, web fetch)
- **Deny**: Operations explicitly blocked by configuration

**Key Capabilities**:
- Per-tool permission configuration with regex matchers
- Regex-based path allowlists/denylists
- Bash command pattern matching (allow `git *`, deny `rm -rf *`)
- Session-level permission caching ("allow all file edits for this session")
- Audit log of all permission decisions
- OS-level sandbox isolation (filesystem and network)
- Filesystem isolation: agent only accesses approved directories
- Network isolation: agent only connects to approved domains
- New domain requests trigger user permission prompts

### 3.8 Hook System

**Description**: Event-driven automation hooks that execute user-defined scripts at key lifecycle points. Hooks provide deterministic control over agent behavior, ensuring actions always happen rather than relying on LLM judgment.

**Hook Events**:

| Event | When It Fires | Use Case |
|-------|--------------|----------|
| `SessionStart` | Session begins or resumes | Re-inject context, setup environment |
| `UserPromptSubmit` | User submits prompt, before processing | Input validation, logging |
| `PreToolUse` | Before a tool executes (can block) | Validation, formatting checks |
| `PostToolUse` | After a tool succeeds | Auto-format, notifications, logging |
| `PostToolUseFailure` | After a tool fails | Error reporting, retry logic |
| `PreCompact` | Before context compaction | Re-inject critical context |
| `Stop` | Agent finishes responding | Quality gates, test running |
| `SubagentStart/Stop` | Subagent lifecycle events | Resource tracking |
| `TaskCompleted` | Task marked as completed | Verification hooks |
| `SessionEnd` | Session terminates | Cleanup, reporting |

**Hook Types**:
- **Command**: Runs a shell command (formatting, validation, logging)
- **Prompt**: Single-turn LLM evaluation for judgment-based decisions
- **Agent**: Multi-turn verification with tool access for complex checks

**Hook I/O Model**:
- Input: JSON on stdin with event-specific fields
- Output: Exit code 0 = proceed, exit code 2 = block action, other = proceed with warning
- Hooks can modify tool inputs before execution (PreToolUse)
- All matching hooks execute in parallel

**Hook Definition**:
```json
{
  "hooks": {
    "PreToolUse": [
      {
        "matcher": "EditFile|WriteFile",
        "type": "command",
        "command": "prettier --write ${file}",
        "timeout": 5000
      }
    ]
  }
}
```

### 3.9 Subagent Delegation System

**Description**: Separate agent instances spawned to handle focused subtasks, each running in its own context window. This prevents main conversation context bloat and enables parallel work.

**Built-in Subagent Types**:

| Type | Model | Tools | Purpose |
|------|-------|-------|---------|
| **Explore** | Fast model (Haiku) | Read-only (Glob, Grep, Read) | Codebase search and analysis |
| **Plan** | Inherits from main | Read-only | Research for plan mode |
| **General** | Inherits from main | All tools | Complex multi-step tasks |

**Key Capabilities**:
- Custom subagent definitions via Markdown files with YAML frontmatter
- Per-subagent tool restrictions, model selection, and permission modes
- Independent auto-compaction at configurable capacity threshold
- Persistent subagent memory (user, project, or local scope)
- Foreground (blocking) and background (concurrent) execution modes
- Subagent-scoped hooks for lifecycle control

### 3.10 Skills and Slash Commands

**Description**: Extensibility through model-invoked skills and user-invoked slash commands.

**Skills**:
- Defined as Markdown files with YAML frontmatter in `.chelava/skills/`
- Agent automatically matches user requests against skill descriptions
- Skills can be preloaded into subagent contexts
- Model-invoked (automatic) or user-invoked (via slash commands)

**Slash Commands**:
- User-invocable commands defined as Markdown files in `.chelava/commands/`
- Support `$ARGUMENTS` placeholder for dynamic input
- Built-in commands: `/help`, `/clear`, `/compact`, `/config`, `/model`, `/tools`, `/undo`, `/exit`

**Plugin Architecture** (post-MVP):
- Distributable bundles of commands + skills + agents + hooks + MCP configs
- Namespaced to prevent conflicts (`/plugin-name:command-name`)
- Version controlled with semantic versioning

---

## 4. Java Differentiation Strategy (Key Selling Points)

### 4.1 Performance: GraalVM Native Image

**Differentiator**: Instant startup and minimal memory footprint via ahead-of-time compilation.

| Metric | Node.js (Claude Code) | Chelava (GraalVM Native) |
|--------|----------------------|--------------------------|
| Cold Start | ~500-800ms | <50ms |
| Memory (idle) | ~80-120MB | ~20-30MB |
| Memory (active) | ~200-400MB | ~60-120MB |
| Binary Size | N/A (requires Node.js) | Single ~50MB binary |
| Distribution | npm install | Single binary download |

**Why It Matters**: Developers invoke the agent dozens of times per day. Sub-50ms startup means the agent feels like a native shell command, not an application launch.

### 4.2 Concurrency: Virtual Threads (Project Loom)

**Differentiator**: Effortless parallel execution of tools, searches, and agent tasks using Java 21+ virtual threads.

**Concrete Benefits**:
- **Parallel file search**: Search thousands of files simultaneously without thread pool tuning
- **Concurrent tool execution**: Run independent tools in parallel (e.g., read 5 files at once)
- **Multi-agent orchestration**: Each agent runs on its own virtual thread with structured concurrency ensuring clean teardown
- **Streaming + tools**: Handle streaming LLM output while executing tools concurrently
- **Structured Concurrency**: Parent-child task relationships with automatic cancellation propagation

**Example**: When an agent needs to read 10 files, grep 3 directories, and check git status, Chelava executes all 14 operations concurrently in <100ms total, vs sequential execution taking 500ms+.

### 4.3 Type Safety: Modern Java Features

**Differentiator**: Compile-time guarantees that eliminate entire categories of runtime errors common in dynamically-typed agent implementations.

**Key Features Used**:
- **Sealed interfaces**: Tool types, permission types, and message types are exhaustive -- the compiler enforces handling all cases
- **Records**: Immutable data carriers for tool inputs/outputs, LLM messages, configuration
- **Pattern matching**: Clean, exhaustive dispatching on message types and tool results
- **Optional<T>**: Explicit null handling without NPEs
- **Generics**: Type-safe tool framework with `Tool<I extends ToolInput, O extends ToolOutput>`

**Example**:
```java
sealed interface ToolResult permits ToolSuccess, ToolError, ToolTimeout {
    record ToolSuccess(String output) implements ToolResult {}
    record ToolError(String message, Throwable cause) implements ToolResult {}
    record ToolTimeout(Duration elapsed) implements ToolResult {}
}

// Compiler enforces exhaustive handling
switch (result) {
    case ToolSuccess s -> display(s.output());
    case ToolError e -> handleError(e.message(), e.cause());
    case ToolTimeout t -> retryOrAbort(t.elapsed());
}
```

### 4.4 Enterprise Readiness

**Differentiator**: Production-grade operational capabilities built on mature Java ecosystem.

| Capability | Implementation | Benefit |
|-----------|---------------|---------|
| Modular Architecture | JPMS (Java Module System) | Strong encapsulation, clear module boundaries |
| Logging | SLF4J + Logback | Structured logging, log levels, appenders |
| Monitoring | Micrometer + Prometheus | Token usage, latency, error rates, custom metrics |
| Audit Trail | Custom audit log framework | Compliance, debugging, security forensics |
| Configuration | Typesafe Config / SmallRye | Hierarchical config with validation |
| Testing | JUnit 5 + Mockito + TestContainers | Comprehensive test infrastructure |
| Build | Gradle with convention plugins | Reproducible builds, dependency management |

### 4.5 Self-Learning Memory System

**Differentiator**: Advanced memory architecture that enables the agent to learn from past interactions and improve over time.

**Memory Types**:
- **Session Memory**: Current conversation context and tool results
- **Project Memory**: Per-project learnings stored in .chelava/memory/
- **Global Memory**: Cross-project patterns and preferences in ~/.chelava/memory/
- **Typed Memory Stores**: Separate stores for code patterns, error solutions, user preferences, and project conventions

**Key Capabilities**:
- Parallel memory retrieval across all stores using virtual threads
- Pattern recognition for recurring errors and solutions
- Automatic memory consolidation (merge similar memories)
- Memory relevance scoring based on current context
- Configurable retention policies (time-based, usage-based)
- Memory export/import for team sharing

### 4.6 Security Architecture

**Differentiator**: JVM-level security guarantees that go beyond process-level isolation. Informed by OpenClaw's security failures (publicly exposed API keys, prompt injection, malicious skill exploits within 48 hours of launch), Chelava makes security a first-class concern.

**Security Layers**:
1. **Permission Model**: Multi-modal permissions with granular per-tool, per-path, per-command control
2. **OS-Level Sandbox**: Filesystem isolation (approved directories only) and network isolation (approved domains only)
3. **JVM Module Encapsulation**: JPMS strong encapsulation prevents internal API access
4. **Process Isolation**: Tool execution in sandboxed child processes when configured
5. **Audit Logging**: Every tool invocation, permission decision, and LLM interaction logged with timestamps
6. **Credential Management**: Secure API key storage with OS keychain integration, never exposed in logs
7. **Input Validation**: Strict validation of all tool inputs, file paths, and command patterns
8. **Prompt Injection Defense**: Detection and flagging of potential prompt injection in tool results
9. **Read-Before-Write Enforcement**: Prevents blind file overwrites
10. **Skill/Plugin Validation**: Source verification for all extensions and skills (learning from OpenClaw's malicious skill incidents)
11. **Default-Deny Network**: New network connections require explicit user approval

---

## 5. MVP Scope Definition

### 5.1 In Scope (v1.0)

| Feature | Priority | Rationale |
|---------|----------|-----------|
| Interactive CLI | P0 | Core user experience, must be excellent from day one |
| Claude API integration | P0 | Primary LLM provider, most capable model |
| OpenAI API integration | P0 | Market expectation, cost flexibility |
| Ollama integration | P0 | Privacy/offline use, enterprise requirement |
| File read/write/edit tools | P0 | Fundamental coding agent operations |
| Bash execution tool | P0 | Essential for running tests, builds, git |
| Glob/Grep search tools | P0 | Code navigation and understanding |
| Permission system | P0 | Safety-critical for file/bash operations |
| Project config (.chelava/) | P0 | Per-project customization |
| Conversation management | P0 | Context window handling is critical for usability |
| MCP client support | P1 | Extensibility story, community tooling |
| Hook system | P1 | Automation workflows, CI/CD integration |
| Web fetch/search tools | P1 | Documentation lookup, API exploration |
| Memory system (basic) | P1 | Project-level auto-memory for continuity |
| GraalVM native image | P1 | Performance differentiator |
| Subagent delegation (Explore) | P1 | Prevents main context bloat, enables parallel research |
| Slash commands | P1 | User-invocable shortcuts for common workflows |
| Skills system (basic) | P2 | Model-invoked extensibility |
| Token usage tracking | P2 | Cost management |
| Notebook support | P2 | Jupyter/data science workflows |

### 5.2 Out of Scope (v1.0)

| Feature | Rationale for Exclusion |
|---------|------------------------|
| Multi-agent team orchestration | Complex feature, needs stable single-agent and subagent first |
| Plugin distribution system | Needs stable core API before building ecosystem |
| Web dashboard | CLI-first approach, dashboard adds significant scope |
| IDE plugins | Requires stable core API, plugin ecosystems vary |
| Custom model fine-tuning | Niche feature, most users use standard models |
| Enterprise SSO/RBAC | Needs organizational deployment infrastructure |
| MCP server mode | Client-first, server adds significant complexity |
| LSP integration | Requires mature plugin/IDE architecture |
| Voice input/output | Experimental, low priority for coding tasks |
| GUI application | CLI is the primary interface for target users |

### 5.3 Scope Rationale

The MVP focuses on **parity with Claude Code's core experience** while delivering the **Java differentiation advantages** (startup speed, concurrency, type safety). Features are prioritized by:
1. **User impact**: Does it directly affect the coding workflow?
2. **Differentiating value**: Does it showcase Java's advantages?
3. **Implementation risk**: Can we deliver it reliably in the MVP timeline?

---

## 6. Advanced Features Roadmap (Post-MVP)

### v1.1 - Enhanced Agent Intelligence
- Advanced self-learning memory with pattern recognition and typed memory stores
- Full subagent system with custom agent definitions (Markdown + YAML frontmatter)
- Conversation branching and session management UI
- Token budget optimization with model auto-selection
- Enhanced streaming with partial tool result display
- PreToolUse input modification hooks

### v1.2 - Plugin Ecosystem & Skills
- Plugin architecture: distributable bundles of commands + skills + agents + hooks + MCP configs
- Plugin marketplace / registry (like ClawHub but with security validation)
- LSP integration for code intelligence
- Custom slash commands with $ARGUMENTS support
- Skill auto-discovery based on project context

### v1.3 - Multi-Agent Team Orchestration
- Team-based agent architecture with structured concurrency
- Shared task list with dependencies and file-locking for concurrent access
- Inter-agent direct messaging protocol (not just report-to-parent)
- Delegate mode for team leads (coordination-only)
- Plan approval workflow (teammates plan in read-only until lead approves)
- Quality gates via TeammateIdle and TaskCompleted hooks
- Self-claiming: teammates auto-claim next unblocked task after completion

### v2.0 - Enterprise Platform
- Web dashboard for agent monitoring and management (Javalin-based)
- SSO integration (SAML, OIDC)
- Role-based access control (RBAC)
- Centralized configuration management
- Audit log aggregation and compliance reporting
- Usage analytics and cost allocation

### v2.1 - IDE Integration
- VS Code extension via JSON-RPC over stdio
- IntelliJ IDEA plugin via Platform SDK
- Language Server Protocol (LSP) bridge
- Shared ChelavaUiAdapter interface for all UI targets
- Inline code suggestions and actions

### v3.0 - AI Platform
- Custom model fine-tuning pipelines
- Organization-specific knowledge bases
- Code review automation
- Automated testing generation
- CI/CD pipeline integration

---

## 7. Success Metrics

### Performance Metrics

| Metric | Target (MVP) | Measurement |
|--------|-------------|-------------|
| Cold startup time | <50ms (native image) | Time from invocation to interactive prompt |
| First token latency | <200ms (after LLM response starts) | Time from LLM first byte to terminal display |
| File search (10K files) | <500ms | Glob/grep across medium codebase |
| Memory usage (idle) | <30MB | RSS after startup |
| Memory usage (active) | <120MB | RSS during active conversation |
| Binary size | <60MB | GraalVM native image |

### Quality Metrics

| Metric | Target (MVP) | Measurement |
|--------|-------------|-------------|
| Task completion rate | >85% | Percentage of user-requested tasks completed successfully |
| Tool execution success rate | >95% | Percentage of tool invocations that succeed |
| Permission accuracy | 100% | No unauthorized operations executed |
| Crash rate | <0.1% | Unhandled exceptions per session |

### User Experience Metrics

| Metric | Target (MVP) | Measurement |
|--------|-------------|-------------|
| Time to first value | <5 minutes | From installation to first successful coding task |
| Session duration | >15 minutes avg | Indicates sustained usefulness |
| Return usage rate | >60% weekly | Users who return within 7 days |
| Net Promoter Score | >40 | User satisfaction survey |

### Competitive Benchmarks

| Benchmark | Claude Code (Baseline) | Chelava Target |
|-----------|----------------------|----------------|
| Startup time | ~500-800ms | <50ms (10x faster) |
| Memory footprint | ~80-120MB idle | <30MB idle (4x less) |
| Parallel file search | Sequential/limited | Full virtual thread parallelism |
| Distribution | Requires Node.js + npm | Single binary |
| Enterprise monitoring | None built-in | Micrometer + Prometheus |
| Type safety | Runtime (JS) | Compile-time (Java) |

---

## 8. Competitive Analysis

### vs. Claude Code (Anthropic)
- **Chelava advantage**: Performance (startup, memory), type safety, enterprise features, native binary distribution
- **Claude Code advantage**: First-mover, tight Anthropic integration, large community, rapid iteration
- **Strategy**: Position as the enterprise-grade alternative with better operational characteristics

### vs. Aider (Python)
- **Chelava advantage**: Performance, type safety, GraalVM native image, structured concurrency
- **Aider advantage**: Mature git integration, multi-file editing, Python ecosystem
- **Strategy**: Match git integration quality, differentiate on performance and enterprise readiness

### vs. Continue (TypeScript)
- **Chelava advantage**: CLI-native (vs IDE-dependent), performance, enterprise features
- **Continue advantage**: IDE integration, visual interface, established user base
- **Strategy**: CLI-first approach with future IDE plugin path

### vs. Cursor/Windsurf (Commercial)
- **Chelava advantage**: Open source, CLI-based, no vendor lock-in, self-hosted LLM support
- **Cursor advantage**: Polished IDE, proprietary model optimizations, funding
- **Strategy**: Open-source community, enterprise self-hosting, CLI workflow preference

---

## 9. Technical Constraints and Assumptions

### Constraints
1. **Java 21+**: Minimum Java version for virtual threads and modern language features
2. **GraalVM**: Required for native image compilation; some reflection restrictions apply
3. **Terminal compatibility**: Must work on major terminal emulators (iTerm2, Windows Terminal, GNOME Terminal, Alacritty)
4. **Platform support**: macOS (ARM64, x86_64), Linux (x86_64, ARM64), Windows (x86_64)
5. **Offline capability**: Core features must work with local Ollama models (no internet required)

### Assumptions
1. Users have Java 21+ installed OR use the native image binary
2. Users have API keys for cloud LLM providers
3. Target codebases are <1M lines of code for optimal performance
4. Terminal supports ANSI escape codes and Unicode
5. MCP ecosystem will continue to grow and standardize

---

## 10. Risk Assessment

| Risk | Probability | Impact | Mitigation |
|------|------------|--------|------------|
| GraalVM native image limitations (reflection, dynamic classloading) | Medium | High | Early prototyping, GraalVM reachability metadata, fallback to JVM mode |
| Feature parity gap with Claude Code | High | Medium | Focus on core workflow first, differentiate on performance/enterprise |
| LLM API changes breaking integrations | Medium | Medium | Abstract provider interface, version pinning, integration tests |
| Adoption challenge (why switch from existing tools?) | High | High | Clear performance benchmarks, enterprise features, easy migration |
| Virtual thread maturity issues | Low | Medium | Extensive testing, fallback to platform threads |
| Community building difficulty | Medium | High | Strong documentation, contribution guides, showcase projects |

---

---

## 11. References

- OpenClaw/Claude Code Architecture Research: `research/openclaw-architecture.md`
- Java Framework Stack & Implementation Plan: `research/java-framework-stack.md`
- Frontend/CLI Design Document: `research/frontend-cli-design.md`

---

*Document Version: 1.1*
*Last Updated: 2026-02-16*
*Author: Product Owner (Chelava PRD Team)*
*Based on: OpenClaw architecture research, Claude Code documentation, competitive analysis*
