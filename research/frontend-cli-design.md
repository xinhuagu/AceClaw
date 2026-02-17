# Chelava Frontend/CLI Design Document

## 1. Overview

This document defines the frontend architecture, terminal user interface, and developer experience for Chelava -- a Java-based AI coding agent. The design prioritizes a fast, responsive terminal-first experience with extensibility for IDE integration and optional web monitoring.

### Design Principles

- **Terminal-first**: The primary interface is a rich terminal experience
- **Streaming-native**: All LLM output renders incrementally as tokens arrive
- **Minimal startup**: Sub-second launch via GraalVM native image
- **Accessible**: Screen reader support, configurable colors, high contrast mode
- **Extensible**: Shared core with pluggable UI adapters (terminal, IDE, web)

---

## 2. Technology Stack

### 2.1 Core CLI Framework: Picocli

**Recommendation**: Use [Picocli](https://picocli.info/) for command-line argument parsing and subcommand routing.

**Rationale**:
- Single-source-file embeddable or standard Maven dependency
- Native GraalVM support with compile-time annotation processing (generates reflection config automatically)
- ANSI color output, TAB autocompletion, subcommands, negatable options
- Supports Java, Kotlin, Groovy, Scala
- Active maintenance, 12k+ GitHub stars

**Key Features Used**:
- `@Command` annotations for subcommands (`chelava chat`, `chelava config`, `chelava init`)
- `@Option` / `@Parameters` for typed argument parsing
- Built-in `--help`, `--version`, color theme support
- Shell completion script generation (bash, zsh, fish)

```java
@Command(name = "chelava", mixinStandardHelpOptions = true,
         version = "Chelava 1.0",
         description = "AI coding agent for the JVM")
public class ChelavaCli implements Runnable {

    @Command(name = "chat", description = "Start interactive conversation")
    void chat(@Option(names = {"-m", "--model"}) String model,
              @Option(names = {"--resume"}) String sessionId) {
        // Launch interactive terminal session
    }

    @Command(name = "run", description = "Execute a task non-interactively")
    void run(@Parameters(description = "Task prompt") String prompt) {
        // Single-shot execution
    }
}
```

### 2.2 Terminal I/O: JLine3

**Recommendation**: Use [JLine3](https://github.com/jline/jline3) (v4.x) for interactive terminal input handling.

**Rationale**:
- Industry-standard Java terminal library (used by Maven, Gradle, Groovy, JShell)
- Line editing with Emacs/Vi key bindings
- Persistent command history with search (Ctrl+R)
- Programmable TAB completion
- ANSI escape sequence support, wide character handling
- Mouse tracking support
- JPMS module support (v4.0+)
- Java 11+ (v4.x)

**Key Features Used**:
- `LineReader` for multi-line input with custom delimiters
- `Terminal` abstraction for cross-platform terminal detection
- `AttributedString` / `AttributedStyle` for styled output
- `History` for session history persistence
- `Completer` implementations for slash commands and file paths

```java
Terminal terminal = TerminalBuilder.builder()
    .system(true)
    .jansi(true)
    .build();

LineReader reader = LineReaderBuilder.builder()
    .terminal(terminal)
    .completer(new ChelavaCOmpleter())  // slash commands, files
    .highlighter(new ChelavaHighlighter())
    .parser(new ChelavaParser())  // multi-line support
    .variable(LineReader.SECONDARY_PROMPT_PATTERN, "... ")
    .build();
```

### 2.3 Markdown Rendering: commonmark-java + Custom ANSI Renderer

**Recommendation**: Use [commonmark-java](https://github.com/commonmark/commonmark-java) for parsing, with a custom `AnsiRenderer` for terminal output.

**Rationale**:
- Actively maintained (updated Jan 2026), CommonMark spec compliant
- Small footprint, zero core dependencies
- Extensible visitor pattern for custom rendering
- Extensions for tables, strikethrough, autolinks
- JPMS module support

**Architecture**:

```
Markdown Text --> commonmark-java Parser --> AST --> AnsiRenderer --> Terminal
```

The custom `AnsiRenderer` translates AST nodes to ANSI-formatted terminal output:

| Markdown Element | Terminal Rendering |
|---|---|
| `# Heading` | Bold + color (hierarchical sizing via indent) |
| `**bold**` | ANSI bold |
| `*italic*` | ANSI italic (or underline on terminals without italic) |
| `` `inline code` `` | Inverse/background color highlight |
| ```` ```code block``` ```` | Bordered box with syntax highlighting |
| `> blockquote` | Indented with vertical bar prefix |
| `- list item` | Bullet character with indent |
| `[link](url)` | Underlined text + dimmed URL |
| `| table |` | Unicode box-drawing characters |
| `---` | Horizontal rule with line-drawing chars |

### 2.4 Syntax Highlighting

**Recommendation**: Build a lightweight token-based highlighter supporting top languages.

**Approach**:
- Regex-based tokenizer for common languages (Java, Python, JavaScript, TypeScript, Go, Rust, SQL, JSON, YAML, XML, Bash)
- Map token types (keyword, string, comment, number, operator) to ANSI color styles
- Configurable color themes (dark/light/solarized/monokai)
- Fallback to plain text for unknown languages

```java
public interface SyntaxHighlighter {
    AttributedString highlight(String code, String language);
}

public class RegexHighlighter implements SyntaxHighlighter {
    private final Map<String, List<TokenRule>> languageRules;
    private final ColorTheme theme;
    // ...
}
```

Language detection strategy:
1. Explicit language tag in fenced code blocks
2. File extension from context (when editing files)
3. Heuristic detection for untagged blocks

---

## 3. Interactive UI Components

### 3.1 Component Library

All UI components are built on JLine3's `Terminal` and `AttributedString` primitives. No dependency on Lanterna (avoids full-screen TUI overhead for a conversational agent).

#### Permission Approval Dialog

```
  Chelava wants to edit src/main/java/App.java

  [y] Allow once  [n] Deny  [a] Always allow  [Esc] Cancel
```

Implementation: Single-key input capture with styled prompt. Stores "always allow" preferences in session config.

#### Progress / Spinner

```
  Reading project structure...  [=====>          ] 35%
  Analyzing dependencies...     /
```

Implementation: Background thread updates terminal line using carriage return (`\r`) and ANSI clear-line sequences. Supports:
- Indeterminate spinner (rotating chars: `|/-\`)
- Determinate progress bar with percentage
- Multi-line status for parallel operations

#### File Diff Display

```
  src/main/java/App.java
  @@ -12,4 +12,6 @@
      public void init() {
  -       logger.info("starting");
  +       logger.info("starting application");
  +       loadConfig();
      }
```

Implementation: Unified diff format with ANSI coloring (red for deletions, green for additions). Line numbers in dim. Context lines in default color. Header in bold.

#### Streaming Text Display

Tokens from LLM responses are rendered incrementally:

1. Raw token stream arrives via SSE/WebSocket callback
2. Tokens buffered in a `MarkdownStreamBuffer` that tracks partial state
3. Complete lines/paragraphs flushed to `AnsiRenderer`
4. Partial code blocks buffered until closing fence detected
5. Terminal cursor managed to avoid flickering

```java
public class StreamingRenderer {
    private final AnsiRenderer renderer;
    private final StringBuilder buffer = new StringBuilder();
    private boolean inCodeBlock = false;

    public void onToken(String token) {
        buffer.append(token);
        if (canFlush()) {
            String segment = extractFlushable();
            renderer.renderIncremental(segment);
        }
    }
}
```

#### Multi-Select Menu

```
  Select tools to enable:
  > [x] File Read/Write
    [x] Bash Execution
    [ ] Web Search
    [x] Code Search

  (Space to toggle, Enter to confirm, a to select all)
```

Implementation: Arrow key navigation, space to toggle, enter to confirm. Rendered using cursor positioning.

### 3.2 Conversation UX

#### Input Modes

| Mode | Trigger | Behavior |
|---|---|---|
| Single-line | Default | Enter submits |
| Multi-line | Ctrl+Enter or `\` at EOL | Secondary prompt `...`, Ctrl+D submits |
| Editor | `/edit` command | Opens `$EDITOR` for long prompts |
| Paste | Auto-detect multi-line paste | Enters multi-line mode automatically |

#### Slash Commands

```
/help                  - Show available commands
/clear                 - Clear conversation context
/compact               - Summarize and compact context
/config                - Open configuration
/cost                  - Show token usage and cost
/diff                  - Review pending changes
/exit                  - End session
/history               - Browse session history
/init                  - Initialize project settings
/model <name>          - Switch AI model
/resume <session-id>   - Resume previous session
/tools                 - List and toggle available tools
/undo                  - Undo last file change
```

#### Session Management

- Sessions auto-saved to `~/.chelava/sessions/<id>.json`
- Contains: conversation history, tool call log, file change log, model config
- Resumable with `/resume` or `chelava chat --resume <id>`
- Session list with `chelava sessions list` (shows timestamp, summary, token count)

#### Context Usage Indicator

Displayed in the prompt line or status bar:

```
chelava> [tokens: 12.4k/128k | cost: $0.23 | model: claude-sonnet-4-5-20250929]
```

Updates after each turn. Color-coded: green (< 50%), yellow (50-80%), red (> 80%).

---

## 4. Terminal Output Architecture

### 4.1 Output Pipeline

```
LLM Response Stream
       |
       v
+------------------+
| Token Aggregator | -- buffers tokens, detects boundaries
+------------------+
       |
       v
+------------------+
| Markdown Parser  | -- commonmark-java incremental parse
+------------------+
       |
       v
+------------------+
| ANSI Renderer    | -- AST nodes -> styled terminal text
+------------------+
       |
       v
+------------------+
| Terminal Writer  | -- JLine3 terminal output with cursor mgmt
+------------------+
       |
       v
    Terminal
```

### 4.2 Rendering Strategy

**Incremental rendering** is critical for perceived responsiveness:

1. **Text paragraphs**: Flush word-by-word as tokens arrive
2. **Code blocks**: Buffer until complete (or flush line-by-line with re-highlight on completion)
3. **Tables**: Buffer until complete, then render with box-drawing characters
4. **Lists**: Flush per item
5. **Tool calls**: Render inline status indicator, then result

**Terminal width adaptation**: All output wraps to terminal width. Code blocks use horizontal scroll or truncation with `...` indicator. Tables compress columns proportionally.

### 4.3 Tool Call Visualization

```
  Reading file: src/main/java/App.java
  > 45 lines read (1.2ms)

  Searching codebase: "DatabaseConnection"
  > Found 3 matches in 2 files (23ms)

  Executing: mvn test -pl core
  > Tests: 42 passed, 0 failed (8.3s)

  Editing: src/main/java/App.java (lines 12-18)
  > Applied 2 insertions, 1 deletion
```

Each tool call shows: tool name, arguments summary, spinner during execution, result summary with timing.

---

## 5. Configuration System

### 5.1 Configuration Files

```
~/.chelava/
  config.toml          # Global configuration
  themes/
    dark.toml           # Color themes
    light.toml
    custom.toml
  sessions/             # Saved sessions
  keys.toml             # API keys (encrypted)
```

### 5.2 Theme Configuration

```toml
[theme]
name = "dark"

[theme.colors]
prompt = "#61AFEF"
user_input = "#ABB2BF"
assistant_text = "#E5C07B"
code_background = "#282C34"
code_keyword = "#C678DD"
code_string = "#98C379"
code_comment = "#5C6370"
error = "#E06C75"
warning = "#E5C07B"
success = "#98C379"
info = "#61AFEF"
diff_add = "#98C379"
diff_remove = "#E06C75"
spinner = "#61AFEF"

[theme.symbols]
prompt = ">"
continuation = "..."
spinner_frames = ["|", "/", "-", "\\"]
bullet = "-"
checkbox_on = "[x]"
checkbox_off = "[ ]"
success = "ok"
error = "!!"
warning = "!?"
```

### 5.3 Accessibility Configuration

```toml
[accessibility]
high_contrast = false
screen_reader = false      # Simplified output, no spinners
reduce_motion = false      # No animated spinners
color_mode = "auto"        # auto | 256 | 16 | none
unicode_symbols = true     # false = ASCII-only fallback
```

---

## 6. GraalVM Native Image Build

### 6.1 Build Configuration

For instant startup (critical for CLI tools):

```xml
<plugin>
    <groupId>org.graalvm.buildtools</groupId>
    <artifactId>native-maven-plugin</artifactId>
    <configuration>
        <imageName>chelava</imageName>
        <mainClass>com.chelava.cli.ChelavaCli</mainClass>
        <buildArgs>
            <buildArg>--no-fallback</buildArg>
            <buildArg>-H:+ReportExceptionStackTraces</buildArg>
        </buildArgs>
    </configuration>
</plugin>
```

**Expected performance**:
- Startup time: < 50ms (vs ~1-2s JVM cold start)
- Binary size: ~30-50MB (statically linked)
- Memory: ~30-60MB RSS at idle

### 6.2 Picocli GraalVM Integration

Picocli's annotation processor generates native-image config at compile time:

```xml
<dependency>
    <groupId>info.picocli</groupId>
    <artifactId>picocli-codegen</artifactId>
    <scope>provided</scope>
</dependency>
```

This auto-generates:
- `META-INF/native-image/picocli-generated/reflect-config.json`
- `META-INF/native-image/picocli-generated/resource-config.json`
- `META-INF/native-image/picocli-generated/proxy-config.json`

---

## 7. IDE Integration Strategy

### 7.1 Architecture

```
+-------------------+     +-------------------+     +------------------+
|   VS Code Ext     |     |  IntelliJ Plugin  |     |  Terminal CLI    |
|  (TypeScript)     |     |  (Kotlin/Java)    |     |  (Java/Native)   |
+--------+----------+     +--------+----------+     +--------+---------+
         |                         |                          |
         |    JSON-RPC / LSP       |    Plugin API            |  Direct
         |                         |                          |
+--------v-------------------------v--------------------------v---------+
|                        Chelava Core Engine                            |
|  (Conversation Manager, Tool Executor, LLM Client, Context Builder)  |
+----------------------------------------------------------------------+
```

### 7.2 VS Code Extension

**Approach**: TypeScript extension that communicates with Chelava core via JSON-RPC over stdio.

Key features:
- Inline chat panel (similar to GitHub Copilot Chat)
- File diff preview in VS Code diff editor
- Permission prompts as VS Code notifications
- Terminal output in VS Code integrated terminal
- Status bar showing model, token usage, cost

Communication protocol:
```json
{
    "jsonrpc": "2.0",
    "method": "chelava/chat",
    "params": {
        "message": "Add error handling to the login method",
        "context": {
            "activeFile": "src/main/java/Auth.java",
            "selection": { "start": 12, "end": 25 }
        }
    }
}
```

### 7.3 IntelliJ Plugin

**Approach**: Kotlin-based plugin using IntelliJ Platform SDK.

Key features:
- Tool window with chat interface
- Action integration (right-click > "Ask Chelava")
- Inline code suggestions
- Project-aware context (module structure, dependencies)
- Integration with IntelliJ's diff viewer

Since IntelliJ 2023.2+ supports LSP client API, Chelava can expose an LSP-compatible interface for basic features, with richer integration via the native Plugin SDK.

### 7.4 Shared Protocol

Define a `ChelavaProtocol` interface that all UI adapters implement:

```java
public interface ChelavaUiAdapter {
    // Input
    CompletableFuture<String> getUserInput(String prompt);
    CompletableFuture<PermissionResult> requestPermission(PermissionRequest request);
    CompletableFuture<SelectionResult> showSelection(SelectionRequest request);

    // Output
    void onStreamStart(StreamMeta meta);
    void onStreamToken(String token);
    void onStreamEnd();
    void onToolCallStart(ToolCallInfo info);
    void onToolCallEnd(ToolCallResult result);
    void onError(ChelavaError error);

    // Status
    void updateStatus(StatusInfo status);
    void updateProgress(ProgressInfo progress);
}
```

Terminal CLI, VS Code, and IntelliJ each implement this interface with their respective rendering.

---

## 8. Web Dashboard (v2, Optional)

### 8.1 Framework: Javalin

**Recommendation**: Use [Javalin](https://javalin.io/) for the optional web monitoring dashboard.

**Rationale**:
- Extremely lightweight (~7k LOC), embeddable
- WebSocket support built-in
- Virtual Threads (Project Loom) by default
- 1M+ monthly downloads, active community
- No configuration boilerplate

### 8.2 Dashboard Features

```
+--------------------------------------------------+
|  Chelava Dashboard           [Session: abc-123]   |
+--------------------------------------------------+
|                                                    |
|  Conversation                    | Context         |
|  +-----------+                   | Tokens: 12.4k  |
|  | User:     |                   | Cost: $0.23    |
|  | "Fix the  |                   | Model: sonnet  |
|  |  login    |                   |                 |
|  |  bug"     |                   | Tools Used      |
|  +-----------+                   | - FileRead (3)  |
|  | Agent:    |                   | - Bash (1)      |
|  | "I found  |                   | - Edit (2)      |
|  |  the      |                   |                 |
|  |  issue.." |                   | Files Modified  |
|  +-----------+                   | - Auth.java     |
|                                  | - LoginSvc.java |
+--------------------------------------------------+
```

### 8.3 Implementation

```java
Javalin app = Javalin.create(config -> {
    config.staticFiles.add("/web-dashboard", Location.CLASSPATH);
}).start(7070);

app.ws("/ws/session/{id}", ws -> {
    ws.onConnect(ctx -> sessionManager.subscribe(ctx));
    ws.onMessage(ctx -> sessionManager.handleInput(ctx));
    ws.onClose(ctx -> sessionManager.unsubscribe(ctx));
});

app.get("/api/sessions", ctx -> {
    ctx.json(sessionManager.listSessions());
});
```

Frontend: Minimal vanilla JS + CSS (no framework) served from classpath resources. WebSocket for real-time conversation streaming.

---

## 9. Accessibility

### 9.1 Screen Reader Support

When `accessibility.screen_reader = true`:
- Disable animated spinners (use static text: "Working...")
- Remove decorative Unicode characters
- Use semantic text instead of visual symbols
- Announce tool call start/end with clear text
- Linearize table output as key-value pairs
- Prefix output sections with labels ("Assistant response:", "Code block:", etc.)

### 9.2 Color Modes

| Mode | Description |
|---|---|
| `auto` | Detect terminal capabilities (truecolor > 256 > 16 > none) |
| `truecolor` | Full 24-bit RGB colors |
| `256` | 256-color xterm palette |
| `16` | Standard 16 ANSI colors |
| `none` | No colors (plain text + formatting via indentation) |

### 9.3 High Contrast Mode

When enabled:
- Text: pure white (#FFFFFF) on pure black (#000000)
- Borders: bright white
- Errors: bright red, bold
- Success: bright green, bold
- No dim or faint text
- Minimum contrast ratio: 7:1 (WCAG AAA)

---

## 10. Key Dependencies Summary

| Component | Library | Version | Purpose |
|---|---|---|---|
| CLI Framework | Picocli | 4.7+ | Argument parsing, subcommands, completions |
| Terminal I/O | JLine3 | 4.x | Line editing, history, terminal abstraction |
| ANSI Support | Jansi | 2.4+ | Cross-platform ANSI (bundled with JLine) |
| Markdown Parser | commonmark-java | 0.23+ | Markdown AST parsing |
| TOML Config | toml4j or jackson-dataformat-toml | latest | Configuration file parsing |
| Web Dashboard | Javalin | 6.x | REST + WebSocket server (optional) |
| Native Build | GraalVM native-image | 23+ | Ahead-of-time compilation |
| JSON | Jackson | 2.17+ | JSON serialization (LLM API, config) |

---

## 11. User Flow Examples

### 11.1 First Launch

```
$ chelava init

  Welcome to Chelava - AI Coding Agent for Java

  API Key Setup:
  Enter your API key: ****************************************
  Key validated successfully.

  Project detected: Maven (Java 21)

  Configuration saved to .chelava/config.toml

  Run 'chelava chat' to start a conversation.
```

### 11.2 Interactive Session

```
$ chelava chat

chelava> Fix the NullPointerException in UserService.getUserById

  Searching codebase: "UserService" "getUserById"
  > Found in src/main/java/com/app/service/UserService.java

  Reading file: src/main/java/com/app/service/UserService.java
  > 82 lines (1ms)

  The issue is on line 45 where `userRepository.findById(id)` can
  return `null`, but the result is used directly without a null check.

  I'll fix this by using `Optional` properly:

  src/main/java/com/app/service/UserService.java
  @@ -43,5 +43,5 @@
       public User getUserById(Long id) {
  -        User user = userRepository.findById(id);
  -        return user.toDto();
  +        return userRepository.findById(id)
  +            .orElseThrow(() -> new UserNotFoundException(id));
       }

  Allow edit to UserService.java? [y/n/a] y
  > File updated.

  Running: mvn test -pl service
  > Tests: 28 passed, 0 failed (4.2s)

chelava> [tokens: 3.2k/128k | cost: $0.04 | model: sonnet-4.5]
```

---

## 12. Implementation Phases

### Phase 1: Core Terminal (MVP)
- Picocli CLI structure with `chat`, `init`, `config` commands
- JLine3 interactive input with history and basic completion
- Plain-text streaming output (no markdown rendering)
- Basic permission dialogs (y/n)
- Session save/restore
- GraalVM native image build

### Phase 2: Rich Terminal
- Markdown-to-ANSI rendering via commonmark-java
- Syntax highlighting for code blocks
- Diff display with colors
- Progress bars and spinners
- Theme support (dark/light)
- Slash commands

### Phase 3: IDE Integration
- JSON-RPC protocol definition
- VS Code extension (TypeScript)
- IntelliJ plugin (Kotlin)
- Shared `ChelavaUiAdapter` interface

### Phase 4: Web Dashboard + Polish
- Javalin-based monitoring dashboard
- WebSocket real-time updates
- Accessibility features
- Screen reader support
- Additional color themes

---

## 13. References

- [JLine3 GitHub](https://github.com/jline/jline3) - Terminal I/O library
- [Picocli](https://picocli.info/) - CLI framework
- [commonmark-java](https://github.com/commonmark/commonmark-java) - Markdown parser
- [Javalin](https://javalin.io/) - Lightweight web framework
- [Lanterna](https://github.com/mabe02/lanterna) - Full TUI framework (evaluated, not selected)
- [GraalVM Native Image + Picocli](https://www.infoq.com/articles/java-native-cli-graalvm-picocli/) - Native compilation guide
- [IntelliJ LSP Support](https://plugins.jetbrains.com/docs/intellij/language-server-protocol.html) - IDE integration
- [VS Code LSP Guide](https://code.visualstudio.com/api/language-extensions/language-server-extension-guide) - VS Code extension development
