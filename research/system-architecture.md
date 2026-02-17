# Chelava System Architecture

## 1. Executive Summary

Chelava is a Java-based AI coding agent that reimagines the agentic coding paradigm with JVM-native advantages: virtual threads for true parallel tool execution, structured concurrency for safe agent task trees, GraalVM native image for instant startup, and Java's mature type system for a robust, extensible plugin ecosystem. This document defines the system architecture, core abstractions, concurrency model, and feasibility analysis.

### Design Philosophy

- **Agent-first**: The agent loop is the core primitive; everything else serves it
- **Type-safe extensibility**: Java's type system enforces correct tool contracts at compile time
- **Parallel by default**: Virtual threads make concurrent tool execution the natural path
- **Virtual threads over reactive streams**: Project Loom eliminates the need for reactive programming patterns (Flow, RxJava, Reactor). Chelava uses simple blocking code on virtual threads instead. This results in simpler, more debuggable code with natural backpressure via BlockingQueue capacity. Flow API is available for external library interop but is not used internally
- **Memory as a first-class citizen**: Typed, searchable memory stores with parallel retrieval
- **Minimal runtime footprint**: GraalVM native image for CLI startup under 100ms
- **Security by design**: Permission system and sandbox integrated from day one

---

## 2. Module Structure

```
chelava/
  chelava-bom/              # Bill of Materials (dependency management)
  chelava-core/             # Agent loop, tool system, LLM client abstractions
  chelava-infra/            # Gateway, event bus, health, scheduler, shutdown
  chelava-llm/              # LLM provider implementations (Anthropic, OpenAI, etc.)
  chelava-tools/            # Built-in tools (file, bash, search, web, git)
  chelava-memory/           # Context management, auto-memory, self-learning
  chelava-security/         # Permission system, sandbox, audit logging
  chelava-mcp/              # MCP protocol client/server implementation
  chelava-cli/              # Terminal UI, CLI parsing, REPL
  chelava-sdk/              # Extension API for plugins and custom tools
  chelava-server/           # HTTP/WebSocket server for IDE integrations
  chelava-test/             # Test utilities and fixtures
```

### Module Dependency Graph

```
chelava-cli ──> chelava-core ──> chelava-sdk (API contracts)
     |               |                  ^
     |               |                  |
     v               v                  |
chelava-server  chelava-llm        chelava-tools
     |               |                  |
     v               v                  v
chelava-mcp    chelava-memory     chelava-security
                     |
                     v
              chelava-infra (gateway, events, health, scheduler)
                     ^
                     |
              chelava-core (depends on infra for lifecycle)
```

### Module Responsibilities

#### chelava-core
The heart of the system. Contains:
- **AgentLoop**: The main agentic execution cycle (prompt -> LLM -> tool_use -> execute -> repeat)
- **Tool registry and dispatch**: Tool discovery, validation, and execution
- **Message protocol**: Typed message representations (user, assistant, tool_result, system)
- **Turn management**: Conversation turn tracking with structured metadata
- **Context engine**: Assembles the optimal context window for each LLM call
- **Sub-agent orchestration**: Spawn isolated agent contexts for delegated tasks
- **Agent team orchestration**: TeamManager, TeamMessageRouter, TeamMessageInjector, in-process/external teammate spawning (see Section 10)

#### chelava-infra
Infrastructure backbone for operational reliability:
- **Gateway**: Central control plane for client connections, request routing
- **EventBus**: Type-safe in-process event system using BlockingQueue per subscriber and virtual threads
- **MessageQueue**: In-process message queue for decoupled inter-agent communication (point-to-point, pub/sub, request-reply, dead letter)
- **HealthMonitor**: Component health tracking with parallel checks via virtual threads
- **Scheduler**: Periodic task execution (maintenance, health checks, memory consolidation)
- **CircuitBreaker**: Fault tolerance for external service calls (LLM APIs, MCP servers)
- **GracefulShutdownManager**: Ordered component shutdown with state persistence
- **HeartbeatSender/Receiver**: Agent team liveness monitoring

#### chelava-llm
LLM provider abstraction and implementations:
- **LLMClient interface**: Unified API for all providers
- **Streaming support**: Token-by-token streaming via blocking `StreamSession` on virtual threads, with natural backpressure via `BlockingQueue` capacity
- **Model failover**: Configurable fallback chains (e.g., Claude -> GPT -> local)
- **Usage tracking**: Token counting, cost estimation, rate limiting
- **Provider implementations**: Anthropic (Claude), OpenAI, Google (Gemini), local (Ollama)

#### chelava-tools
Built-in tool implementations:
- **FileTools**: Read, write, edit, glob, grep (with diff-based edit validation)
- **BashTool**: Command execution with timeout, streaming output, sandbox integration
- **SearchTools**: Codebase search (ripgrep-like), web search, semantic search
- **GitTools**: Status, diff, commit, branch operations
- **WebTools**: HTTP fetch, web scraping with content extraction
- **NotebookTools**: Jupyter notebook cell operations

#### chelava-memory
Memory subsystem with tiered architecture:
- **ConversationMemory**: Current session messages with compression
- **ProjectMemory**: Persistent per-project knowledge (CHELAVA.md equivalent)
- **AutoMemory**: Self-learning pattern storage, mistake tracking
- **MemoryIndex**: Embedding-based semantic search across memory stores
- **Compaction engine**: Intelligent context summarization

#### chelava-security
Security infrastructure:
- **PermissionPolicy**: Declarative permission rules for tool execution
- **Sandbox**: Process isolation for bash commands, file system boundaries
- **AuditLog**: Structured logging of all tool executions and LLM calls
- **SecretDetector**: Prevents accidental exposure of credentials/keys

#### chelava-mcp
Model Context Protocol implementation:
- **MCPClient**: Connect to external MCP servers (stdio, SSE, WebSocket)
- **MCPServer**: Expose Chelava tools as MCP endpoints
- **MCPToolBridge**: Adapt MCP tools to Chelava's tool interface
- **Protocol negotiation**: Version compatibility and capability discovery

#### chelava-cli
User-facing terminal interface:
- **REPL**: Interactive read-eval-print loop with rich formatting
- **CommandParser**: Slash commands (/help, /commit, /compact, etc.)
- **TerminalUI**: Markdown rendering, syntax highlighting, progress indicators
- **SessionManager**: Conversation persistence and resumption

#### chelava-sdk
Public extension API:
- **Tool SPI**: Interface for custom tool plugins
- **Provider SPI**: Interface for custom LLM providers
- **Memory SPI**: Interface for custom memory backends
- **Event system**: Hook into agent lifecycle events
- **Configuration API**: Type-safe plugin configuration

#### chelava-server
IDE integration server:
- **HTTP API**: REST endpoints for IDE extensions
- **WebSocket**: Real-time streaming for editor plugins
- **LSP bridge**: Language Server Protocol integration points

---

## 3. Core Abstractions

### 3.1 Agent and Agent Loop

```java
// The fundamental agent abstraction
public sealed interface Agent {
    String id();
    AgentConfig config();
    CompletableFuture<AgentResult> execute(AgentTask task);
    void cancel();
}

// Agent configuration
public record AgentConfig(
    String model,
    String systemPrompt,
    List<ToolDefinition> tools,
    PermissionPolicy permissions,
    MemoryConfig memory,
    int maxTurns,
    Duration timeout
) {}

// The core agent loop - the heart of the system
public interface AgentLoop {

    /**
     * Run the agent loop: prompt -> LLM -> check stop reason ->
     * if tool_use: execute tools -> add results -> repeat
     * if end_turn: return response to user
     */
    Turn runTurn(ConversationContext context, UserMessage input);

    /**
     * Spawn a sub-agent with its own isolated context.
     * Returns a condensed summary (not the full sub-agent conversation).
     */
    SubAgentResult delegate(AgentConfig subAgentConfig, String task);
}

// A single turn in the conversation
public record Turn(
    String id,
    List<Message> messages,
    List<ToolExecution> toolExecutions,
    TokenUsage usage,
    StopReason stopReason,
    Duration duration
) {}

// Stop reasons mirror LLM API responses
public enum StopReason {
    END_TURN,      // Model decided it's done
    TOOL_USE,      // Model wants to use tools (loop continues)
    MAX_TOKENS,    // Context limit reached, need compaction
    CANCELLED,     // User or system cancelled
    ERROR          // Unrecoverable error
}
```

### 3.2 Message Protocol

```java
// Typed message hierarchy
public sealed interface Message {
    String id();
    Instant timestamp();
}

public record UserMessage(
    String id,
    Instant timestamp,
    String content
) implements Message {}

public record AssistantMessage(
    String id,
    Instant timestamp,
    List<ContentBlock> content
) implements Message {}

public sealed interface ContentBlock {}

public record TextBlock(String text) implements ContentBlock {}

public record ToolUseBlock(
    String toolUseId,
    String toolName,
    JsonNode input
) implements ContentBlock {}

public record ToolResultMessage(
    String id,
    Instant timestamp,
    String toolUseId,
    ToolResult result
) implements Message {}

public record SystemMessage(
    String id,
    Instant timestamp,
    String content,
    CacheControl cacheControl
) implements Message {}
```

### 3.3 Tool System

```java
// Tool definition - what the LLM sees
public record ToolDefinition(
    String name,
    String description,
    JsonSchema inputSchema,
    ToolPermission requiredPermission
) {}

// Tool interface - what plugin authors implement
public interface Tool {
    ToolDefinition definition();

    /**
     * Execute the tool. Runs on a virtual thread.
     * @param input validated JSON input matching the schema
     * @param context execution context with permissions, cancellation
     * @return the tool result (success or error)
     */
    ToolResult execute(JsonNode input, ToolExecutionContext context);

    /**
     * Optional: validate input beyond JSON schema.
     * Throw ToolValidationException for invalid input.
     */
    default void validate(JsonNode input) {}
}

// Tool execution result
public sealed interface ToolResult {
    String toolUseId();
}

public record ToolSuccess(
    String toolUseId,
    String content,
    boolean isError
) implements ToolResult {}

public record ToolError(
    String toolUseId,
    String errorMessage,
    ErrorKind kind
) implements ToolResult {}

// Permission levels for tools
public enum ToolPermission {
    READ_ONLY,         // File reads, search, git status
    WRITE_LOCAL,       // File writes within project
    EXECUTE_SANDBOXED, // Bash in sandbox
    EXECUTE_UNSAFE,    // Bash without sandbox
    NETWORK,           // HTTP requests, web search
    DESTRUCTIVE        // Git push, file delete, etc.
}

// Execution context provided to tools
public interface ToolExecutionContext {
    Path workingDirectory();
    PermissionPolicy permissions();
    CancellationToken cancellation();
    AuditLog auditLog();
    ToolProgressReporter progress();
}
```

### 3.4 LLM Client

```java
// Unified LLM client interface
public interface LLMClient {
    String providerId();
    List<String> supportedModels();

    /**
     * Send a message and get a complete response.
     */
    AssistantMessage complete(LLMRequest request);

    /**
     * Stream a response event by event.
     * Returns a StreamSession that the caller reads on a virtual thread.
     * The caller blocks cheaply on session.next(), which is ideal
     * for virtual threads (no reactive complexity needed).
     */
    StreamSession stream(LLMRequest request);

    /**
     * Check model availability and rate limits.
     */
    ModelStatus status(String model);
}

// Blocking stream session - consumed on a virtual thread
public interface StreamSession extends AutoCloseable {
    /**
     * Block until the next event is available.
     * Safe to call from a virtual thread (cheap blocking).
     */
    StreamEvent next() throws InterruptedException;

    /**
     * Check if more events are available.
     */
    boolean hasNext();

    /**
     * Cancel the stream and release resources.
     */
    void cancel();
}

// Request to the LLM
public record LLMRequest(
    String model,
    List<Message> messages,
    List<ToolDefinition> tools,
    SystemPrompt systemPrompt,
    int maxTokens,
    double temperature,
    ThinkingConfig thinking
) {}

// Streaming events
public sealed interface StreamEvent {}
public record TextDelta(String text) implements StreamEvent {}
public record ToolUseDelta(String toolUseId, String name, String partialJson) implements StreamEvent {}
public record ThinkingDelta(String thought) implements StreamEvent {}
public record UsageUpdate(TokenUsage usage) implements StreamEvent {}
public record StreamComplete(StopReason reason) implements StreamEvent {}
```

### 3.5 Memory System

```java
// Memory store abstraction
public interface MemoryStore<K, V> {
    Optional<V> get(K key);
    void put(K key, V value);
    void remove(K key);
    List<V> search(String query, int limit);
    Stream<Map.Entry<K, V>> entries();
}

// Conversation context with compression support
public interface ConversationContext {
    List<Message> messages();
    void addMessage(Message message);
    TokenUsage estimateTokens();

    /**
     * Compact the conversation by summarizing older messages.
     * Preserves: system prompt, recent messages, key decisions.
     * Summarizes: old tool results, verbose outputs, resolved discussions.
     */
    CompactionResult compact(CompactionStrategy strategy);
}

// Context window manager
public interface ContextWindow {
    int maxTokens();
    int usedTokens();
    int availableTokens();

    /**
     * Assemble the optimal context for the next LLM call.
     * Includes: system prompt, compacted history, recent messages,
     * injected memory, tool definitions.
     */
    LLMRequest assemble(ConversationContext conversation, MemoryInjections memory);
}

// Long-term project memory (like CLAUDE.md / CHELAVA.md)
public interface ProjectMemory {
    String load(Path projectRoot);
    void update(Path projectRoot, String content);
    List<String> getInstructions(Path projectRoot);
}

// Auto-memory for self-learning
public interface AutoMemory {
    void recordInsight(String category, String insight);
    void recordMistake(String context, String mistake, String correction);
    void recordPattern(String pattern, String strategy);
    List<MemoryEntry> retrieve(String query, int limit);
}
```

### 3.6 Permission and Security

```java
// Permission policy - determines what tools can do
public interface PermissionPolicy {
    PermissionDecision check(ToolPermission required, ToolExecutionContext context);
    PermissionDecision checkPath(Path path, PathAccess access);
    PermissionDecision checkCommand(String command, List<String> args);
}

public enum PermissionDecision {
    ALLOW,          // Automatically allowed
    PROMPT_USER,    // Ask user for confirmation
    DENY            // Blocked
}

public enum PathAccess {
    READ, WRITE, EXECUTE, DELETE
}

// Sandbox for process isolation
public interface Sandbox {
    SandboxedProcess execute(String command, SandboxConfig config);
    boolean isAvailable();
}

public record SandboxConfig(
    Path workingDirectory,
    Set<Path> allowedPaths,
    Set<String> allowedCommands,
    Duration timeout,
    long maxOutputBytes,
    boolean networkAccess
) {}
```

---

## 4. Concurrency Architecture

### 4.1 Virtual Threads for Tool Execution

Chelava's primary concurrency advantage over Node.js-based agents (OpenClaw, Claude Code) is **true parallel tool execution** via virtual threads. Where Node.js uses a single-threaded event loop with async/await, Chelava can execute multiple tools genuinely in parallel.

```java
// Parallel tool execution using structured concurrency
public class ParallelToolExecutor {

    /**
     * Execute multiple tools in parallel using virtual threads.
     * If any tool fails, cancel remaining tools and report the failure.
     */
    public List<ToolResult> executeParallel(List<ToolRequest> requests,
                                             ToolExecutionContext context) {
        try (var scope = StructuredTaskScope.open()) {
            // Fork each tool execution onto its own virtual thread
            List<StructuredTaskScope.Subtask<ToolResult>> subtasks =
                requests.stream()
                    .map(req -> scope.fork(() -> executeTool(req, context)))
                    .toList();

            // Wait for all to complete (or first failure)
            scope.join();

            return subtasks.stream()
                .map(StructuredTaskScope.Subtask::get)
                .toList();
        }
    }
}
```

### 4.2 Structured Concurrency for Agent Task Trees

Sub-agent delegation uses structured concurrency to ensure clean lifecycle management:

```java
// Sub-agent execution with structured concurrency
public class SubAgentOrchestrator {

    /**
     * Delegate tasks to sub-agents. Each sub-agent runs in its own
     * virtual thread with an isolated context window.
     *
     * Structured concurrency ensures:
     * - If parent is cancelled, all sub-agents are cancelled
     * - If a sub-agent fails, others can be cancelled (configurable)
     * - No orphaned agent threads
     */
    public List<SubAgentResult> delegateParallel(
            List<SubAgentTask> tasks) {

        try (var scope = StructuredTaskScope.open()) {
            var subtasks = tasks.stream()
                .map(task -> scope.fork(() -> {
                    var agent = createSubAgent(task.config());
                    return agent.execute(task);
                }))
                .toList();

            scope.join();

            return subtasks.stream()
                .map(StructuredTaskScope.Subtask::get)
                .toList();
        }
    }
}
```

### 4.3 Scoped Values for Request Context

Replace ThreadLocal with ScopedValue for clean context propagation:

```java
// Request-scoped context using ScopedValue
public final class AgentContext {
    public static final ScopedValue<String> SESSION_ID = ScopedValue.newInstance();
    public static final ScopedValue<PermissionPolicy> PERMISSIONS = ScopedValue.newInstance();
    public static final ScopedValue<AuditLog> AUDIT_LOG = ScopedValue.newInstance();
    public static final ScopedValue<CancellationToken> CANCELLATION = ScopedValue.newInstance();

    /**
     * Run an agent turn with all necessary context bound.
     */
    public static <T> T withContext(AgentSession session, Callable<T> action) throws Exception {
        return ScopedValue
            .where(SESSION_ID, session.id())
            .where(PERMISSIONS, session.permissions())
            .where(AUDIT_LOG, session.auditLog())
            .where(CANCELLATION, session.cancellationToken())
            .call(action);
    }
}
```

### 4.4 Comparison with Node.js Async Model

| Aspect | Node.js (OpenClaw/Claude Code) | Java (Chelava) |
|--------|-------------------------------|-----------------|
| Concurrency model | Single-threaded event loop + async/await | Virtual threads (millions of lightweight threads) |
| Parallel tool execution | Cooperative multitasking; CPU-bound tools block | True parallelism; each tool on its own thread |
| CPU-bound work | Blocks event loop; requires worker_threads | Natural; virtual threads yield at I/O, run on carrier threads |
| Memory per concurrent task | ~1KB per promise | ~1KB per virtual thread (vs ~1MB platform thread) |
| Structured lifecycle | Manual (Promise.all, AbortController) | Built-in (StructuredTaskScope) |
| Context propagation | Closure capture, AsyncLocalStorage | ScopedValue (zero-cost, immutable) |
| Backpressure | Manual (streams, queues) | BlockingQueue capacity, virtual thread blocking |
| Error handling | Unhandled rejection risks | Structured concurrency catches all |
| Cancellation | AbortController (manual wiring) | Structured concurrency (automatic propagation) |

**Key advantage**: When an agent needs to execute 5 tools in parallel (e.g., read 3 files, run a grep, check git status), Chelava dispatches 5 virtual threads that run truly in parallel. Node.js would interleave I/O callbacks on a single thread, and any CPU-intensive tool (like parsing a large file) blocks everything.

### 4.5 Streaming Architecture

LLM response streaming uses simple blocking reads on virtual threads. Because virtual threads
block cheaply (they unmount from the carrier thread at I/O boundaries), there is no need for
reactive `Flow.Subscriber` complexity. Natural backpressure comes from the consumer blocking
on `session.next()` - the producer cannot outpace the consumer.

```java
// Streaming LLM responses with a simple blocking loop on a virtual thread.
// No reactive callbacks, no subscription management, no onError/onComplete wiring.
Thread.startVirtualThread(() -> {
    try (var session = llmClient.stream(request)) {
        while (session.hasNext()) {
            var event = session.next();
            switch (event) {
                case TextDelta(var text) -> ui.appendText(text);
                case ToolUseDelta(var id, var name, var json) -> ui.showToolProgress(name);
                case ThinkingDelta(var thought) -> ui.showThinking(thought);
                case UsageUpdate(var usage) -> ui.updateUsage(usage);
                case StreamComplete(var reason) -> { /* done */ }
            }
        }
    } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        ui.showError("Stream interrupted");
    } catch (Exception e) {
        ui.showError("Stream error: " + e.getMessage());
    }
});
```

Under the hood, `StreamSession` is backed by a `BlockingQueue<StreamEvent>`. The LLM provider
implementation writes events into the queue from its own virtual thread (the HTTP response reader),
and the consumer reads from the queue with `take()`. Queue capacity provides natural backpressure:

```java
// Internal implementation of StreamSession (inside LLM provider)
public class BlockingQueueStreamSession implements StreamSession {
    private final BlockingQueue<StreamEvent> queue;
    private volatile boolean done = false;
    private volatile boolean cancelled = false;

    public BlockingQueueStreamSession(int capacity) {
        this.queue = new ArrayBlockingQueue<>(capacity); // capacity = backpressure
    }

    @Override
    public StreamEvent next() throws InterruptedException {
        return queue.take(); // blocks cheaply on virtual thread
    }

    @Override
    public boolean hasNext() {
        return !done || !queue.isEmpty();
    }

    @Override
    public void cancel() {
        cancelled = true;
        done = true;
    }

    // Called by the producer (HTTP response reader) on its own virtual thread
    void produce(StreamEvent event) throws InterruptedException {
        if (!cancelled) {
            queue.put(event); // blocks if queue full = natural backpressure
        }
    }

    void complete() {
        done = true;
    }
}
```

---

## 5. GraalVM Native Image Strategy

### 5.1 Compilation Tiers

Chelava uses a **dual-mode** build strategy:

| Component | Native Image | JVM Fallback | Rationale |
|-----------|-------------|--------------|-----------|
| chelava-cli | Yes (primary) | Yes | Instant startup (<100ms) for CLI UX |
| chelava-core | Yes | Yes | Agent loop is reflection-free by design |
| chelava-tools | Yes | Yes | File/bash/search tools are straightforward |
| chelava-llm | Yes | Yes | HTTP clients work well in native |
| chelava-memory | Yes | Yes | Avoids complex reflection |
| chelava-security | Yes | Yes | Sandbox uses ProcessBuilder (native-compatible) |
| chelava-mcp | Partial | Yes | JSON-RPC needs some reflection config |
| chelava-sdk (plugins) | No | Yes | Plugin classloading requires JVM |
| chelava-server | Partial | Yes | HTTP server works; WebSocket may need config |

### 5.2 Design for Native Compatibility

Architecture decisions that enable native compilation:

1. **Sealed interfaces over reflection**: Message types, tool results, and events use sealed interfaces with pattern matching instead of reflection-based dispatch
2. **Records over POJOs**: Immutable records with known structure, no getter/setter reflection needed
3. **Compile-time DI**: No Spring/Guice; use constructor injection and factory methods
4. **JSON handling**: Use Jackson with compile-time module registration or GraalVM-friendly serialization (e.g., record-based serializers)
5. **No dynamic proxies in hot path**: Tool and LLM interfaces use direct implementation, not proxies

### 5.3 Plugin Loading Strategy

For the plugin system, Chelava uses a **hybrid approach**:

```
chelava (native image)
  |
  +-- Core agent loop, built-in tools, CLI (native compiled)
  |
  +-- Plugin host (JVM subprocess, started on demand)
       |
       +-- Custom tools loaded via SPI with classloader isolation
       +-- Communicates with core via local socket / shared memory
```

When plugins are present, Chelava spawns a lightweight JVM subprocess for plugin execution. This preserves native image startup speed for the common case (no plugins) while supporting full plugin flexibility.

### 5.4 Build Configuration

```
# Native image build configuration
native-image \
  --no-fallback \
  --enable-url-protocols=https \
  --initialize-at-build-time \
  -H:+ReportExceptionStackTraces \
  -H:ReflectionConfigurationFiles=reflect-config.json \
  -H:ResourceConfigurationFiles=resource-config.json \
  -jar chelava-cli.jar \
  -o chelava
```

Expected binary sizes:
- CLI only: ~40-60 MB
- Full distribution: ~80-120 MB (comparable to Node.js-based agents with node_modules)

---

## 6. Plugin System

### 6.1 SPI-Based Tool Discovery

```java
// Tool provider SPI
public interface ToolProvider {
    /**
     * Return the tools this provider offers.
     * Called once at startup for tool registration.
     */
    List<Tool> provideTools();

    /**
     * Provider metadata for the tool registry.
     */
    ToolProviderMetadata metadata();
}

// Plugin descriptor (META-INF/services/com.chelava.sdk.ToolProvider)
// com.example.myplugin.MyToolProvider

// Tool provider metadata
public record ToolProviderMetadata(
    String id,
    String name,
    String version,
    String description,
    List<String> requiredPermissions
) {}
```

### 6.2 Classloader Isolation

Each plugin runs in an isolated classloader to prevent dependency conflicts:

```java
public class PluginClassLoader extends URLClassLoader {
    private final Set<String> sharedPackages;

    public PluginClassLoader(URL[] urls, ClassLoader parent,
                              Set<String> sharedPackages) {
        super(urls, parent);
        // Only delegate to parent for Chelava SDK packages
        this.sharedPackages = sharedPackages;
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve)
            throws ClassNotFoundException {
        // Check if this is a shared API package (chelava-sdk)
        if (isSharedPackage(name)) {
            return getParent().loadClass(name);
        }
        // Otherwise, load from plugin's own classpath first
        try {
            return findClass(name);
        } catch (ClassNotFoundException e) {
            return getParent().loadClass(name);
        }
    }
}
```

### 6.3 Plugin Lifecycle

```
1. Discovery:  Scan ~/.chelava/plugins/ and project .chelava/plugins/
2. Loading:    Create isolated classloader per plugin JAR
3. Validation: Verify ToolProvider SPI, check required permissions
4. Registration: Add tools to the tool registry
5. Execution:  Tool calls dispatched to plugin's virtual thread
6. Unloading:  Close classloader, GC plugin classes (on reload)
```

### 6.4 Plugin Distribution

```
~/.chelava/
  plugins/
    my-database-tools/
      plugin.json          # Plugin metadata
      my-database-tools.jar
      lib/                 # Plugin-specific dependencies
        postgresql-42.7.jar
```

---

## 7. Memory Architecture

### 7.1 Tiered Memory Model

```
+------------------------------------------------------------------+
|                    Context Window (LLM Request)                   |
|  +------------------------------------------------------------+  |
|  | System Prompt (cached)                                      |  |
|  +------------------------------------------------------------+  |
|  | Injected Memory (project instructions, auto-memory hits)    |  |
|  +------------------------------------------------------------+  |
|  | Compacted History (summaries of older turns)                |  |
|  +------------------------------------------------------------+  |
|  | Recent Messages (last N turns, verbatim)                    |  |
|  +------------------------------------------------------------+  |
|  | Tool Definitions (available tools)                          |  |
|  +------------------------------------------------------------+  |
+------------------------------------------------------------------+

         ^                    ^                    ^
         |                    |                    |
  Short-term Memory    Medium-term Memory    Long-term Memory
  (conversation)       (session summaries)   (project + auto)
```

### 7.2 Short-term Memory: Conversation Context

The current conversation with intelligent compression:

- **Full verbatim**: Last 5-7 turns kept in full
- **Tool result clearing**: First compression pass removes verbose tool outputs (file contents, bash output), replacing with summaries
- **Turn summarization**: Older turns compressed to 1-2 sentence summaries preserving: decisions made, files modified, errors encountered
- **Anchor preservation**: Key architectural decisions, user preferences, and error contexts are never compressed

```java
public class ConversationCompactor {

    public CompactionResult compact(List<Message> messages, int targetTokens) {
        var result = new CompactionResult();

        // Phase 1: Clear verbose tool results (safe, high token savings)
        result.addPhase(clearToolResults(messages));

        // Phase 2: Summarize old turns (moderate savings)
        if (result.estimatedTokens() > targetTokens) {
            result.addPhase(summarizeOldTurns(messages));
        }

        // Phase 3: Drop irrelevant context (aggressive, last resort)
        if (result.estimatedTokens() > targetTokens) {
            result.addPhase(dropIrrelevantContext(messages));
        }

        return result;
    }
}
```

### 7.3 Long-term Memory: Project Memory

Persistent per-project knowledge, analogous to CLAUDE.md:

```
project-root/
  CHELAVA.md              # Project-level instructions (user-managed)
  .chelava/
    memory/
      MEMORY.md           # Auto-generated project memory
      patterns.md         # Recognized patterns
      debugging.md        # Debugging insights
      architecture.md     # Architecture decisions
```

### 7.4 Auto-Memory: Self-Learning

The self-learning memory system tracks patterns and improves over time:

```java
public class AutoMemorySystem {

    // Categories of auto-memory
    public enum Category {
        MISTAKE,          // "I tried X but Y was the correct approach"
        PATTERN,          // "When user asks for X, the codebase uses pattern Y"
        PREFERENCE,       // "User prefers approach X over Y"
        CODEBASE_INSIGHT, // "Module X depends on Y, uses pattern Z"
        STRATEGY          // "For task type X, strategy Y works best"
    }

    /**
     * After each turn, analyze what happened and record learnings.
     * This runs asynchronously on a virtual thread.
     */
    public void analyzeAndRecord(Turn turn, ConversationContext context) {
        // Detect mistakes: tool errors followed by corrections
        detectMistakes(turn).forEach(this::recordMistake);

        // Detect patterns: repeated code structures or approaches
        detectPatterns(turn).forEach(this::recordPattern);

        // Track user preferences: corrections, style choices
        detectPreferences(turn).forEach(this::recordPreference);
    }
}
```

### 7.5 Parallel Memory Retrieval

A key advantage over single-threaded agents: Chelava retrieves from multiple memory stores simultaneously:

```java
public class ParallelMemoryRetriever {

    /**
     * Retrieve relevant memory from all stores in parallel.
     * Virtual threads make this efficient without callback complexity.
     */
    public MemoryInjections retrieve(String query, AgentConfig config) {
        try (var scope = StructuredTaskScope.open()) {
            var projectMemory = scope.fork(() ->
                projectStore.getInstructions(config.projectRoot()));
            var autoMemory = scope.fork(() ->
                autoStore.retrieve(query, 5));
            var codebaseContext = scope.fork(() ->
                codebaseIndex.search(query, 10));

            scope.join();

            return new MemoryInjections(
                projectMemory.get(),
                autoMemory.get(),
                codebaseContext.get()
            );
        }
    }
}
```

---

## 8. Agent Loop Detail

### 8.1 Main Loop Flow

```
User Input
    |
    v
[Assemble Context]
    |-- Load system prompt (cached)
    |-- Retrieve memories (parallel: project, auto, codebase)
    |-- Build message history (compacted if needed)
    |-- Attach tool definitions
    |
    v
[LLM Call] --------> Stream response to terminal
    |
    v
[Check Stop Reason]
    |
    +-- end_turn ---------> Display response, wait for user
    |
    +-- tool_use ---------> [Execute Tools]
    |                           |
    |                           +-- Single tool: execute on virtual thread
    |                           +-- Multiple tools: parallel via StructuredTaskScope
    |                           |
    |                           v
    |                       [Permission Check]
    |                           |
    |                           +-- ALLOW: execute immediately
    |                           +-- PROMPT_USER: ask user, then execute or skip
    |                           +-- DENY: return error to LLM
    |                           |
    |                           v
    |                       [Add Tool Results to Messages]
    |                           |
    |                           v
    |                       [Loop back to Assemble Context]
    |
    +-- max_tokens -------> [Compact Context, then retry]
    |
    +-- error ------------> [Handle Error, notify user]
```

### 8.2 Sub-Agent Pattern

```java
public class MainAgentLoop implements AgentLoop {

    @Override
    public Turn runTurn(ConversationContext context, UserMessage input) {
        while (true) {
            var request = contextWindow.assemble(context, memoryRetriever.retrieve(input.content(), config));
            var response = llmClient.complete(request);

            context.addMessage(response);

            switch (response.stopReason()) {
                case END_TURN -> {
                    autoMemory.analyzeAndRecord(currentTurn, context);
                    return currentTurn;
                }
                case TOOL_USE -> {
                    var toolRequests = extractToolRequests(response);
                    var results = toolExecutor.executeParallel(toolRequests, executionContext);
                    results.forEach(context::addToolResult);
                }
                case MAX_TOKENS -> {
                    context.compact(CompactionStrategy.AGGRESSIVE);
                }
            }
        }
    }

    @Override
    public SubAgentResult delegate(AgentConfig subConfig, String task) {
        // Sub-agent runs in its own virtual thread with isolated context
        var subAgent = new MainAgentLoop(subConfig, llmClient, toolExecutor);
        var subContext = ConversationContext.fresh();
        var result = subAgent.runTurn(subContext, new UserMessage(task));

        // Return condensed summary, not full conversation
        return new SubAgentResult(
            summarize(result, 1500), // ~1500 token summary
            result.toolExecutions(),
            result.usage()
        );
    }
}
```

---

## 9. Hook System (Lifecycle Events)

### 9.1 Overview

Hooks provide **deterministic control** over agent behavior at key lifecycle points. Unlike LLM-driven decisions (which are probabilistic), hooks guarantee that certain actions always happen. This is critical for code formatting, quality gates, logging, and security enforcement.

### 9.2 Hook Events

```java
// All lifecycle events that can trigger hooks
public enum HookEvent {
    SESSION_START,       // Session begins or resumes
    SESSION_END,         // Session terminates
    USER_PROMPT_SUBMIT,  // User submits prompt, before processing
    PRE_TOOL_USE,        // Before a tool executes (can block or modify input)
    POST_TOOL_USE,       // After a tool succeeds
    POST_TOOL_FAILURE,   // After a tool fails
    PERMISSION_REQUEST,  // Permission dialog appears
    NOTIFICATION,        // Agent sends a notification
    SUBAGENT_START,      // Subagent is spawned
    SUBAGENT_STOP,       // Subagent finishes
    STOP,                // Agent finishes responding
    PRE_COMPACT,         // Before context compaction
    TASK_COMPLETED,      // Task marked as completed
    TEAMMATE_IDLE        // Agent team member about to go idle
}

// Hook definition
public record HookDefinition(
    HookEvent event,
    String matcher,          // Regex pattern to filter (e.g., tool name)
    HookType type,           // command, prompt, or agent
    String command,          // Shell command to execute
    Duration timeout
) {}

public enum HookType {
    COMMAND,    // Runs a shell command; exit code determines behavior
    PROMPT,     // Single-turn LLM evaluation for judgment-based decisions
    AGENT       // Multi-turn verification with tool access
}
```

### 9.3 Hook I/O Model

```java
// Hook execution context (passed as JSON on stdin)
public record HookInput(
    String sessionId,
    Path workingDirectory,
    HookEvent event,
    String toolName,         // For tool-related events
    JsonNode toolInput,      // For PRE_TOOL_USE (modifiable)
    JsonNode toolOutput,     // For POST_TOOL_USE
    String triggerType       // For PRE_COMPACT: "manual" or "auto"
) {}

// Hook execution result
public sealed interface HookResult {}

public record HookAllow(
    String stdout,           // Added to agent context (for some events)
    JsonNode modifiedInput   // For PRE_TOOL_USE: modified tool input
) implements HookResult {}

public record HookBlock(
    String reason            // Feedback sent to agent
) implements HookResult {}

public record HookPassthrough(
    String stderr            // Logged but not shown to agent
) implements HookResult {}
```

### 9.4 Hook Execution

- Hooks matching an event run **in parallel** on virtual threads
- Exit code 0 = allow (proceed), exit code 2 = block, other = passthrough
- PRE_TOOL_USE hooks can modify tool inputs before execution
- All matching hooks are deduplicated automatically

### 9.5 Hook Configuration

```json
{
  "hooks": {
    "PostToolUse": [
      {
        "matcher": "Edit|Write",
        "type": "command",
        "command": "prettier --write $TOOL_INPUT_FILE_PATH"
      }
    ],
    "PreToolUse": [
      {
        "matcher": "Bash",
        "type": "command",
        "command": "./scripts/validate-command.sh"
      }
    ],
    "Stop": [
      {
        "type": "agent",
        "command": "Run the test suite and verify all tests pass"
      }
    ]
  }
}
```

### 9.6 Key Use Cases

1. **Auto-format code** after file edits (PostToolUse + Edit/Write)
2. **Block edits to protected files** (PreToolUse + path pattern check)
3. **Desktop notifications** when agent completes (Notification)
4. **Re-inject critical context** after compaction (PreCompact)
5. **Quality gates** - run tests before declaring done (Stop with agent type)
6. **Audit logging** of all bash commands (PostToolUse + Bash)
7. **Security enforcement** - block dangerous commands (PreToolUse + Bash)

---

## 10. Agent Teams (Multi-Agent Orchestration)

### 10.1 Overview

Agent Teams enable multiple Chelava agent instances to work collaboratively on complex tasks. Unlike subagents (which report back to a parent and return condensed results), teammates are fully independent agent sessions that communicate directly with each other through a typed messaging system and coordinate work through a shared task list.

This design faithfully ports Claude Code's agent team architecture to Java, preserving the same communication model, task coordination, lifecycle management, and coordination patterns while enhancing with Java 21+ advantages: virtual threads for in-process teammates, sealed interfaces for exhaustive message handling, `BlockingQueue`-based delivery, and `StructuredTaskScope` for lifecycle management.

### 10.2 Architecture

```
+------------------------------------------------------------+
|                     Team Lead Session                       |
|  (coordinator, spawns teammates, manages task list)         |
+------------------------------------------------------------+
     |              |              |              |
     | spawn        | spawn        | spawn        | messages
     v              v              v              v
+-----------+ +-----------+ +-----------+ +----------------+
| Teammate  | | Teammate  | | Teammate  | | Message Router |
| "arch"    | | "impl"    | | "test"    | | (per-team)     |
| (virtual  | | (virtual  | | (external | +----------------+
|  thread)  | |  thread)  | |  process) |        |
+-----------+ +-----------+ +-----------+        |
     |              |              |              |
     +----+---------+---------+----+--------------+
          |                   |
  +---------------+   +---------------+
  | Task Store    |   | Message Store |
  | (per-task     |   | (per-agent    |
  |  JSON files)  |   |  inbox files) |
  +---------------+   +---------------+
          |                   |
     ~/.chelava/tasks/    ~/.chelava/teams/
       {team-name}/         {team-name}/
```

### 10.3 Team Lifecycle

#### 10.3.1 Team Commands

```java
/**
 * Commands for managing agent team lifecycle.
 * Each command is a sealed record for exhaustive handling.
 */
public sealed interface TeamCommand permits
    TeamCommand.CreateTeam,
    TeamCommand.SpawnTeammate,
    TeamCommand.ShutdownTeammate,
    TeamCommand.DeleteTeam {

    /**
     * Create a new agent team. Initializes:
     * - Team config file at ~/.chelava/teams/{name}/config.json
     * - Task directory at ~/.chelava/tasks/{name}/
     * - Inbox directory at ~/.chelava/teams/{name}/inboxes/
     * - Message router for the team
     */
    record CreateTeam(
        String name,
        String description
    ) implements TeamCommand {}

    /**
     * Spawn a new teammate into an existing team.
     * Registers the teammate in config.json members array,
     * creates an inbox file, and starts the agent session.
     */
    record SpawnTeammate(
        TeammateConfig config
    ) implements TeamCommand {}

    /**
     * Request a teammate to shut down gracefully.
     * Sends a ShutdownRequest message; teammate must approve or reject.
     */
    record ShutdownTeammate(
        String name,
        String reason
    ) implements TeamCommand {}

    /**
     * Delete a team and all associated resources.
     * Fails if any teammates are still active - all teammates
     * must be shut down first (matches Claude Code behavior).
     */
    record DeleteTeam() implements TeamCommand {}
}
```

#### 10.3.2 Team Manager

```java
/**
 * Manages the lifecycle of an agent team.
 * One team per session (matches Claude Code constraint).
 */
public interface TeamManager {

    /**
     * Create a new team. Sets up file structure and message routing.
     */
    TeamHandle createTeam(TeamCommand.CreateTeam command);

    /**
     * Spawn a teammate into the current team.
     * Supports three execution backends:
     * - IN_PROCESS: virtual thread in same JVM (default, ~40% less resources)
     * - EXTERNAL_PROCESS: separate JVM via ProcessBuilder (for isolation)
     * - TMUX: separate process in tmux split pane (for visibility)
     */
    TeammateHandle spawnTeammate(TeamCommand.SpawnTeammate command);

    /**
     * Request graceful shutdown of a teammate.
     * Returns a future that completes when the teammate confirms shutdown.
     */
    CompletableFuture<ShutdownResult> shutdownTeammate(TeamCommand.ShutdownTeammate command);

    /**
     * Delete the team. Fails if active teammates exist.
     */
    void deleteTeam(TeamCommand.DeleteTeam command);

    /**
     * Get the current team handle, or empty if no team is active.
     */
    Optional<TeamHandle> currentTeam();
}

/**
 * Handle to a running team with access to its components.
 */
public record TeamHandle(
    String teamName,
    String description,
    TeamConfig config,
    TaskStore taskStore,
    TeamMessageRouter messageRouter,
    List<TeammateHandle> teammates,
    Path teamDir,
    Path taskDir
) {}

/**
 * Handle to a running teammate.
 */
public record TeammateHandle(
    String name,
    String agentId,
    TeammateBackend backend,
    AgentState state,
    Instant joinedAt
) {}

/**
 * Teammate execution backends.
 */
public enum TeammateBackend {
    IN_PROCESS,       // Virtual thread in same JVM (default)
    EXTERNAL_PROCESS, // Separate JVM via ProcessBuilder
    TMUX              // Separate process in tmux split pane
}

/**
 * Result of a shutdown request.
 */
public sealed interface ShutdownResult permits
    ShutdownResult.Approved,
    ShutdownResult.Rejected {

    record Approved(String name, Instant shutdownAt) implements ShutdownResult {}
    record Rejected(String name, String reason) implements ShutdownResult {}
}
```

#### 10.3.3 Team Configuration

```java
/**
 * Team configuration persisted as JSON at
 * ~/.chelava/teams/{team-name}/config.json
 *
 * Teammates discover each other by reading this file.
 */
public record TeamConfig(
    String teamName,
    String description,
    String leadAgentId,
    Instant createdAt,
    List<TeamMember> members
) {
    /**
     * A registered team member. All fields match Claude Code's
     * config.json member structure.
     */
    public record TeamMember(
        String name,             // Human-readable name (used for messaging)
        String agentId,          // Unique identifier
        String agentType,        // Role/type (e.g., "researcher", "implementer")
        String color,            // Display color for UI
        Instant joinedAt,
        TeammateBackend backendType,
        String model,            // LLM model override (null = inherit from lead)
        String prompt,           // Initial task/system prompt
        boolean planModeRequired,// Whether teammate requires plan approval
        Path workingDirectory
    ) {}
}

/**
 * Configuration for spawning a new teammate.
 * Defined as Markdown files with YAML frontmatter (like subagent definitions).
 */
public record TeammateConfig(
    String name,
    String description,
    String agentType,
    String model,
    List<String> tools,
    List<String> disallowedTools,
    PermissionMode permissionMode,
    int maxTurns,
    boolean planModeRequired,
    MemoryScope memoryScope,
    List<HookDefinition> hooks,
    TeammateBackend backend,
    Path workingDirectory
) {
    /**
     * Create a minimal config with sensible defaults.
     */
    public static TeammateConfig of(String name, String description) {
        return new TeammateConfig(
            name, description, "general-purpose", null,
            List.of(), List.of(), PermissionMode.DEFAULT,
            100, false, MemoryScope.PROJECT, List.of(),
            TeammateBackend.IN_PROCESS, null
        );
    }
}
```

### 10.4 Communication System

#### 10.4.1 Team Message Types

The message type hierarchy faithfully ports all Claude Code SendMessage types, plus adds Java-specific enhancements (typed records, sealed exhaustiveness, timestamps).

```java
/**
 * All inter-agent messages in the team system.
 * Sealed interface ensures exhaustive handling at compile time.
 *
 * <p>Messages are delivered to each agent's personal inbox and
 * injected as a new user turn between agent loop iterations
 * (consuming tokens, matching Claude Code's delivery model).
 */
public sealed interface TeamMessage permits
    TeamMessage.DirectMessage,
    TeamMessage.Broadcast,
    TeamMessage.ShutdownRequest,
    TeamMessage.ShutdownResponse,
    TeamMessage.PlanApprovalRequest,
    TeamMessage.PlanApprovalResponse,
    TeamMessage.IdleNotification {

    /** Unique message identifier. */
    String messageId();

    /** Timestamp when the message was created. */
    Instant timestamp();

    /**
     * Direct message from one teammate to another.
     * Delivered to the recipient's inbox only.
     * Team lead does NOT automatically see DMs between peers,
     * but receives a summary via IdleNotification.peerDmSummaries.
     */
    record DirectMessage(
        String messageId,
        Instant timestamp,
        String from,
        String to,
        String content,
        String summary       // 5-10 word summary shown as preview in UI
    ) implements TeamMessage {}

    /**
     * Broadcast message sent to ALL teammates on the team.
     * Expensive: sends a separate copy to each teammate's inbox.
     * Use sparingly - only for critical team-wide announcements.
     */
    record Broadcast(
        String messageId,
        Instant timestamp,
        String from,
        String content,
        String summary
    ) implements TeamMessage {}

    /**
     * Request for a teammate to shut down gracefully.
     * The recipient MUST respond with a ShutdownResponse.
     */
    record ShutdownRequest(
        String messageId,
        Instant timestamp,
        String requestId,    // Correlation ID for the response
        String from,
        String target,
        String reason
    ) implements TeamMessage {}

    /**
     * Response to a ShutdownRequest. If approved, the teammate
     * process/thread terminates after sending this message.
     */
    record ShutdownResponse(
        String messageId,
        Instant timestamp,
        String requestId,    // Must match the ShutdownRequest.requestId
        String from,
        boolean approved,
        String reason        // Why shutdown was rejected (if !approved)
    ) implements TeamMessage {}

    /**
     * Request from a teammate (in plan mode) to the team lead
     * for approval of a proposed plan. Sent when the teammate
     * calls ExitPlanMode.
     */
    record PlanApprovalRequest(
        String messageId,
        Instant timestamp,
        String requestId,
        String from,
        String planContent   // The full plan text for review
    ) implements TeamMessage {}

    /**
     * Team lead's response to a PlanApprovalRequest.
     * If approved, the teammate exits plan mode and can proceed.
     * If rejected, the teammate receives feedback and can revise.
     */
    record PlanApprovalResponse(
        String messageId,
        Instant timestamp,
        String requestId,    // Must match the PlanApprovalRequest.requestId
        String from,
        String to,
        boolean approved,
        String feedback      // Rejection feedback or approval notes
    ) implements TeamMessage {}

    /**
     * Automatically sent when a teammate finishes a turn and goes idle.
     * This is the primary mechanism for the team lead to know when
     * teammates are available for new work.
     *
     * <p>Includes summaries of any peer DMs the teammate sent during
     * its turn, giving the lead visibility without the full content.
     */
    record IdleNotification(
        String messageId,
        Instant timestamp,
        String from,
        String reason,       // Why the teammate went idle (e.g., "task completed")
        List<PeerDmSummary> peerDmSummaries
    ) implements TeamMessage {}

    /**
     * Summary of a peer-to-peer DM, included in IdleNotification
     * to give the team lead visibility into peer collaboration.
     */
    record PeerDmSummary(
        String to,
        String summary
    ) {}
}
```

#### 10.4.2 Message Routing

```java
/**
 * Routes messages between teammates in a team.
 *
 * <p>Supports two transport modes:
 * <ul>
 *   <li><b>In-process</b>: BlockingQueue per agent, zero-copy delivery.
 *       Used when teammates run as virtual threads in the same JVM.</li>
 *   <li><b>File-based</b>: JSON inbox files with atomic writes and file locks.
 *       Used for cross-process teammates (external JVM, tmux).</li>
 * </ul>
 *
 * <p>The router automatically selects the transport based on the
 * recipient's TeammateBackend type.
 */
public interface TeamMessageRouter extends AutoCloseable {

    /**
     * Send a direct message to a specific teammate.
     * Selects in-process or file-based transport automatically.
     */
    void send(TeamMessage.DirectMessage message);

    /**
     * Broadcast a message to all teammates.
     * Sends a separate copy to each teammate's inbox.
     */
    void broadcast(TeamMessage.Broadcast message);

    /**
     * Send a shutdown request to a teammate.
     */
    void sendShutdownRequest(TeamMessage.ShutdownRequest request);

    /**
     * Send a shutdown response back.
     */
    void sendShutdownResponse(TeamMessage.ShutdownResponse response);

    /**
     * Send a plan approval request to the team lead.
     */
    void sendPlanApprovalRequest(TeamMessage.PlanApprovalRequest request);

    /**
     * Send a plan approval response to a teammate.
     */
    void sendPlanApprovalResponse(TeamMessage.PlanApprovalResponse response);

    /**
     * Send an idle notification (system-generated, not user-invoked).
     */
    void sendIdleNotification(TeamMessage.IdleNotification notification);

    /**
     * Receive the next message from this agent's inbox.
     * Blocks until a message is available or the timeout expires.
     * Messages are picked up between agent turns and injected as
     * new user turns (consuming tokens).
     *
     * @param agentName the agent whose inbox to check
     * @param timeout maximum time to wait
     * @return the next message, or empty if timeout expires
     */
    Optional<TeamMessage> receive(String agentName, Duration timeout);

    /**
     * Poll this agent's inbox without blocking.
     * Returns all pending messages and clears the inbox.
     */
    List<TeamMessage> drainInbox(String agentName);

    /**
     * Register a new agent inbox (called when teammate spawns).
     */
    void registerInbox(String agentName, TeammateBackend backend);

    /**
     * Remove an agent inbox (called when teammate shuts down).
     */
    void removeInbox(String agentName);
}
```

#### 10.4.3 In-Process Transport

```java
/**
 * In-process message transport using BlockingQueue per agent.
 * Used when teammates run as virtual threads in the same JVM.
 * Zero external dependencies, zero-copy message passing.
 */
public class InProcessMessageTransport {
    private final ConcurrentHashMap<String, BlockingQueue<TeamMessage>> inboxes =
        new ConcurrentHashMap<>();

    public void registerInbox(String agentName, int capacity) {
        inboxes.putIfAbsent(agentName, new ArrayBlockingQueue<>(capacity));
    }

    public void send(String recipient, TeamMessage message) {
        var inbox = inboxes.get(recipient);
        if (inbox == null) {
            throw new TeammateNotFoundException(recipient);
        }
        // offer() is non-blocking; if inbox is full, message is dropped
        // with a warning (natural backpressure)
        if (!inbox.offer(message)) {
            logger.warn("Inbox full for agent '{}', message dropped: {}",
                recipient, message.messageId());
        }
    }

    public Optional<TeamMessage> receive(String agentName, Duration timeout) {
        var inbox = inboxes.get(agentName);
        if (inbox == null) return Optional.empty();
        try {
            return Optional.ofNullable(
                inbox.poll(timeout.toMillis(), TimeUnit.MILLISECONDS)
            );
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        }
    }

    public List<TeamMessage> drainInbox(String agentName) {
        var inbox = inboxes.get(agentName);
        if (inbox == null) return List.of();
        var messages = new ArrayList<TeamMessage>();
        inbox.drainTo(messages);
        return List.copyOf(messages);
    }
}
```

#### 10.4.4 File-Based Transport

```java
/**
 * File-based message transport for cross-process teammates.
 * Each agent's inbox is a JSON file at:
 *   ~/.chelava/teams/{team-name}/inboxes/{agent-name}.json
 *
 * <p>Uses atomic writes (write to temp file + Files.move) and
 * file locks (.lock files) to prevent corruption from concurrent access.
 *
 * <p>Matches Claude Code's file-based inbox approach.
 */
public class FileBasedMessageTransport {
    private final Path inboxDir;
    private final ObjectMapper mapper;

    public FileBasedMessageTransport(Path teamDir, ObjectMapper mapper) {
        this.inboxDir = teamDir.resolve("inboxes");
        this.mapper = mapper;
    }

    /**
     * Append a message to the recipient's inbox file.
     * Uses file lock + atomic write to prevent corruption.
     */
    public void send(String recipient, TeamMessage message) {
        var inboxFile = inboxDir.resolve(recipient + ".json");
        var lockFile = inboxDir.resolve(recipient + ".lock");

        try (var channel = FileChannel.open(lockFile,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var lock = channel.lock()) {

            // Read existing messages
            List<TeamMessage> messages = readInbox(inboxFile);
            messages.add(message);

            // Atomic write: temp file + move
            var tempFile = Files.createTempFile(inboxDir, "inbox-", ".tmp");
            mapper.writeValue(tempFile.toFile(), messages);
            Files.move(tempFile, inboxFile, StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING);

        } catch (IOException e) {
            throw new MessageDeliveryException(
                "Failed to deliver message to " + recipient, e);
        }
    }

    /**
     * Read and clear all messages from an agent's inbox.
     * Uses file lock for safe concurrent access.
     */
    public List<TeamMessage> drainInbox(String agentName) {
        var inboxFile = inboxDir.resolve(agentName + ".json");
        var lockFile = inboxDir.resolve(agentName + ".lock");

        try (var channel = FileChannel.open(lockFile,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var lock = channel.lock()) {

            List<TeamMessage> messages = readInbox(inboxFile);

            // Clear the inbox
            Files.writeString(inboxFile, "[]");

            return messages;

        } catch (IOException e) {
            throw new MessageDeliveryException(
                "Failed to drain inbox for " + agentName, e);
        }
    }

    /**
     * Watch for inbox changes using NIO WatchService.
     * Used by external-process teammates to detect new messages
     * without polling.
     */
    public WatchService createInboxWatcher() throws IOException {
        var watchService = inboxDir.getFileSystem().newWatchService();
        inboxDir.register(watchService, StandardWatchEventKinds.ENTRY_MODIFY);
        return watchService;
    }

    private List<TeamMessage> readInbox(Path inboxFile) throws IOException {
        if (!Files.exists(inboxFile)) return new ArrayList<>();
        var content = Files.readString(inboxFile);
        if (content.isBlank() || content.equals("[]")) return new ArrayList<>();
        return new ArrayList<>(mapper.readValue(content,
            mapper.getTypeFactory().constructCollectionType(List.class, TeamMessage.class)));
    }
}
```

#### 10.4.5 Message Delivery Model

Messages are delivered and consumed following Claude Code's exact model:

1. **Delivery**: Messages are written to the recipient's inbox (BlockingQueue or JSON file)
2. **Pickup**: Between agent loop turns, the agent checks its inbox for pending messages
3. **Injection**: Pending messages are injected as new user turns into the conversation (consuming tokens)
4. **Idle notification**: When an agent finishes a turn and has no more work, it auto-sends an `IdleNotification` to the team lead
5. **Peer DM visibility**: When a teammate sends a DM to another teammate, a summary is included in the sender's next `IdleNotification`, giving the lead visibility without the full content

```java
/**
 * Integration point between the team message system and the agent loop.
 * Called between turns to check for and inject incoming messages.
 */
public class TeamMessageInjector {

    private final TeamMessageRouter router;
    private final String agentName;
    private final List<TeamMessage.PeerDmSummary> pendingPeerDmSummaries =
        new ArrayList<>();

    /**
     * Check inbox and inject any pending messages as user turns.
     * Called by the agent loop between iterations.
     *
     * @param context the conversation context to inject messages into
     * @return true if any messages were injected
     */
    public boolean injectPendingMessages(ConversationContext context) {
        var messages = router.drainInbox(agentName);
        if (messages.isEmpty()) return false;

        for (var message : messages) {
            var userTurn = formatAsUserTurn(message);
            context.addMessage(userTurn);
        }
        return true;
    }

    /**
     * Called when this agent sends a DM to a peer (not the lead).
     * Records the summary for inclusion in the next idle notification.
     */
    public void recordPeerDm(String to, String summary) {
        pendingPeerDmSummaries.add(new TeamMessage.PeerDmSummary(to, summary));
    }

    /**
     * Send idle notification when this agent finishes its turn.
     * Includes any peer DM summaries accumulated during the turn.
     */
    public void sendIdleNotification(String reason) {
        router.sendIdleNotification(new TeamMessage.IdleNotification(
            UUID.randomUUID().toString(),
            Instant.now(),
            agentName,
            reason,
            List.copyOf(pendingPeerDmSummaries)
        ));
        pendingPeerDmSummaries.clear();
    }

    private UserMessage formatAsUserTurn(TeamMessage message) {
        var content = switch (message) {
            case TeamMessage.DirectMessage dm ->
                "[Message from %s]: %s".formatted(dm.from(), dm.content());
            case TeamMessage.Broadcast b ->
                "[Broadcast from %s]: %s".formatted(b.from(), b.content());
            case TeamMessage.ShutdownRequest sr ->
                "[Shutdown request from %s (requestId: %s)]: %s"
                    .formatted(sr.from(), sr.requestId(), sr.reason());
            case TeamMessage.ShutdownResponse sr ->
                "[Shutdown response (requestId: %s)]: %s"
                    .formatted(sr.requestId(), sr.approved() ? "approved" : "rejected: " + sr.reason());
            case TeamMessage.PlanApprovalRequest par ->
                "[Plan approval request from %s (requestId: %s)]: %s"
                    .formatted(par.from(), par.requestId(), par.planContent());
            case TeamMessage.PlanApprovalResponse par ->
                "[Plan approval response (requestId: %s)]: %s"
                    .formatted(par.requestId(), par.approved() ? "approved" : "rejected: " + par.feedback());
            case TeamMessage.IdleNotification idle ->
                "[%s is idle]: %s".formatted(idle.from(), idle.reason());
        };
        return new UserMessage(UUID.randomUUID().toString(), Instant.now(), content);
    }
}
```

### 10.5 Task Coordination

#### 10.5.1 Task Model

The task model faithfully ports Claude Code's TaskCreate/TaskUpdate/TaskGet/TaskList system.

```java
/**
 * A task in the shared task list.
 * Each task is stored as a separate JSON file:
 *   ~/.chelava/tasks/{team-name}/{taskId}.json
 *
 * <p>Matches Claude Code's task model exactly: auto-incrementing IDs,
 * dependency tracking with automatic unblocking, and owner-based claiming.
 */
public record AgentTask(
    String id,                   // Auto-incrementing string ID ("1", "2", ...)
    String subject,              // Brief title in imperative form ("Fix auth bug")
    String description,          // Detailed description with acceptance criteria
    String activeForm,           // Present continuous form ("Fixing auth bug")
    TaskStatus status,
    String owner,                // Agent name who owns this task (null = unassigned)
    Set<String> blocks,          // Task IDs that this task blocks
    Set<String> blockedBy,       // Task IDs that must complete before this one
    Map<String, Object> metadata,// Arbitrary key-value metadata
    Instant createdAt,
    Instant updatedAt
) {
    /**
     * A task is available for claiming when it is pending,
     * has no owner, and has no open blocking dependencies.
     */
    public boolean isAvailable() {
        return status == TaskStatus.PENDING
            && owner == null
            && blockedBy.isEmpty();
    }
}

/**
 * Task status lifecycle: PENDING -> IN_PROGRESS -> COMPLETED
 * DELETED is a terminal state that removes the task.
 */
public enum TaskStatus {
    PENDING,       // Created but not started
    IN_PROGRESS,   // Claimed and being worked on
    COMPLETED,     // Successfully finished
    DELETED        // Permanently removed
}
```

#### 10.5.2 Task Store

```java
/**
 * Persistent task store backed by individual JSON files per task.
 * Uses file locking for safe concurrent access from multiple teammates.
 *
 * <p>File structure:
 * <pre>
 * ~/.chelava/tasks/{team-name}/
 *   counter.txt          # Next task ID (auto-incrementing)
 *   1.json               # Task 1
 *   2.json               # Task 2
 *   ...
 * </pre>
 *
 * <p>For in-process teammates, an in-memory implementation using
 * ReentrantReadWriteLock is available (no file I/O overhead).
 */
public interface TaskStore {

    /**
     * Create a new task with auto-incrementing ID.
     */
    AgentTask create(String subject, String description, String activeForm);

    /**
     * Get a task by ID.
     */
    Optional<AgentTask> get(String taskId);

    /**
     * Update a task. Supports partial updates (only non-null fields are applied).
     * Automatically handles dependency unblocking: when a task is completed,
     * it is removed from the blockedBy set of all tasks it blocks.
     */
    AgentTask update(TaskUpdate update);

    /**
     * List all non-deleted tasks.
     */
    List<AgentTask> list();

    /**
     * List tasks available for claiming (pending, no owner, not blocked).
     * Sorted by ID ascending (teammates prefer lowest ID first).
     */
    List<AgentTask> listAvailable();
}

/**
 * Partial update for a task. Null fields are not applied.
 */
public record TaskUpdate(
    String taskId,
    TaskStatus status,
    String subject,
    String description,
    String activeForm,
    String owner,
    Set<String> addBlocks,
    Set<String> addBlockedBy,
    Map<String, Object> metadata
) {
    public static TaskUpdate status(String taskId, TaskStatus status) {
        return new TaskUpdate(taskId, status, null, null, null, null, null, null, null);
    }

    public static TaskUpdate claim(String taskId, String owner) {
        return new TaskUpdate(taskId, TaskStatus.IN_PROGRESS, null, null, null,
            owner, null, null, null);
    }
}
```

#### 10.5.3 File-Based Task Store

```java
/**
 * Task store backed by individual JSON files with file locking.
 * Used when teammates run in separate processes.
 */
public class FileTaskStore implements TaskStore {
    private final Path taskDir;
    private final Path counterFile;
    private final Path lockFile;
    private final ObjectMapper mapper;

    public FileTaskStore(Path taskDir, ObjectMapper mapper) {
        this.taskDir = taskDir;
        this.counterFile = taskDir.resolve("counter.txt");
        this.lockFile = taskDir.resolve(".lock");
        this.mapper = mapper;
    }

    @Override
    public AgentTask create(String subject, String description, String activeForm) {
        return withFileLock(() -> {
            var id = nextId();
            var task = new AgentTask(
                id, subject, description, activeForm,
                TaskStatus.PENDING, null,
                Set.of(), Set.of(), Map.of(),
                Instant.now(), Instant.now()
            );
            writeTask(task);
            return task;
        });
    }

    @Override
    public AgentTask update(TaskUpdate update) {
        return withFileLock(() -> {
            var task = readTask(update.taskId())
                .orElseThrow(() -> new TaskNotFoundException(update.taskId()));

            var updated = applyUpdate(task, update);
            writeTask(updated);

            // Auto-unblock: if task is completed, remove it from
            // blockedBy sets of all tasks it blocks
            if (update.status() == TaskStatus.COMPLETED) {
                unblockDependents(updated);
            }

            return updated;
        });
    }

    @Override
    public List<AgentTask> listAvailable() {
        return list().stream()
            .filter(AgentTask::isAvailable)
            .sorted(Comparator.comparing(t -> Integer.parseInt(t.id())))
            .toList();
    }

    /**
     * Execute an operation under file lock.
     * Uses java.nio.channels.FileLock for cross-process safety.
     */
    private <T> T withFileLock(Callable<T> operation) {
        try (var channel = FileChannel.open(lockFile,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var lock = channel.lock()) {
            return operation.call();
        } catch (Exception e) {
            throw new TaskStoreException("Task store operation failed", e);
        }
    }

    private String nextId() throws IOException {
        int counter = 1;
        if (Files.exists(counterFile)) {
            counter = Integer.parseInt(Files.readString(counterFile).trim()) + 1;
        }
        Files.writeString(counterFile, String.valueOf(counter));
        return String.valueOf(counter);
    }

    private void unblockDependents(AgentTask completed) throws IOException {
        for (var blockedId : completed.blocks()) {
            readTask(blockedId).ifPresent(blocked -> {
                var newBlockedBy = new HashSet<>(blocked.blockedBy());
                newBlockedBy.remove(completed.id());
                var updated = new AgentTask(
                    blocked.id(), blocked.subject(), blocked.description(),
                    blocked.activeForm(), blocked.status(), blocked.owner(),
                    blocked.blocks(), Set.copyOf(newBlockedBy),
                    blocked.metadata(), blocked.createdAt(), Instant.now()
                );
                try {
                    writeTask(updated);
                } catch (IOException e) {
                    throw new TaskStoreException("Failed to unblock task " + blockedId, e);
                }
            });
        }
    }
}
```

#### 10.5.4 In-Memory Task Store

```java
/**
 * In-memory task store for in-process teammates.
 * Uses ReentrantReadWriteLock instead of file locking.
 * No file I/O overhead - all operations are in-memory.
 */
public class InMemoryTaskStore implements TaskStore {
    private final Map<String, AgentTask> tasks = new LinkedHashMap<>();
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private final AtomicInteger counter = new AtomicInteger(0);

    @Override
    public AgentTask create(String subject, String description, String activeForm) {
        lock.writeLock().lock();
        try {
            var id = String.valueOf(counter.incrementAndGet());
            var task = new AgentTask(
                id, subject, description, activeForm,
                TaskStatus.PENDING, null,
                Set.of(), Set.of(), Map.of(),
                Instant.now(), Instant.now()
            );
            tasks.put(id, task);
            return task;
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public AgentTask update(TaskUpdate update) {
        lock.writeLock().lock();
        try {
            var task = tasks.get(update.taskId());
            if (task == null) throw new TaskNotFoundException(update.taskId());

            var updated = applyUpdate(task, update);
            tasks.put(update.taskId(), updated);

            if (update.status() == TaskStatus.COMPLETED) {
                unblockDependents(updated);
            }

            return updated;
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public List<AgentTask> listAvailable() {
        lock.readLock().lock();
        try {
            return tasks.values().stream()
                .filter(AgentTask::isAvailable)
                .sorted(Comparator.comparing(t -> Integer.parseInt(t.id())))
                .toList();
        } finally {
            lock.readLock().unlock();
        }
    }
}
```

### 10.6 Teammate Execution Model

#### 10.6.1 In-Process Teammates (Virtual Threads)

The primary Java advantage: teammates run as virtual threads in the same JVM, sharing LLM client connection pools, file system caches, and the in-memory task store. This reduces resource usage by ~40% compared to process-per-teammate.

```java
/**
 * Spawns a teammate as a virtual thread in the same JVM.
 * The teammate gets its own isolated ConversationContext and AgentLoop,
 * but shares the LLM client, tool registry, and MessageQueue with
 * the lead and other in-process teammates.
 */
public class InProcessTeammateSpawner {
    private final LLMClient sharedLlmClient;
    private final ToolRegistry sharedToolRegistry;
    private final TeamMessageRouter messageRouter;
    private final TaskStore taskStore;
    private final EventBus eventBus;

    /**
     * Spawn a teammate on a virtual thread with structured concurrency.
     * The teammate runs its own agent loop with an isolated context window.
     */
    public TeammateHandle spawn(TeammateConfig config) {
        var agentId = UUID.randomUUID().toString();

        // Register inbox for this teammate
        messageRouter.registerInbox(config.name(), TeammateBackend.IN_PROCESS);

        // Create isolated agent context
        var agentConfig = buildAgentConfig(config);
        var injector = new TeamMessageInjector(messageRouter, config.name());

        // Start the teammate on a virtual thread
        var thread = Thread.startVirtualThread(() -> {
            // Set environment context via ScopedValue
            ScopedValue
                .where(TeamContext.TEAM_NAME, messageRouter.teamName())
                .where(TeamContext.AGENT_ID, agentId)
                .where(TeamContext.AGENT_NAME, config.name())
                .where(TeamContext.AGENT_TYPE, config.agentType())
                .where(TeamContext.PLAN_MODE_REQUIRED, config.planModeRequired())
                .run(() -> runTeammateLoop(agentConfig, injector, config));
        });

        eventBus.publish(new ChelavaEvent.TeammateJoined(
            UUID.randomUUID().toString(), Instant.now(), "team-manager",
            messageRouter.teamName(), config.name()
        ));

        return new TeammateHandle(
            config.name(), agentId, TeammateBackend.IN_PROCESS,
            AgentState.STARTING, Instant.now()
        );
    }

    private void runTeammateLoop(AgentConfig config,
                                  TeamMessageInjector injector,
                                  TeammateConfig teammateConfig) {
        var context = ConversationContext.fresh();
        var agentLoop = new MainAgentLoop(config, sharedLlmClient, sharedToolRegistry);

        // Inject initial prompt as first user message
        if (teammateConfig.description() != null) {
            context.addMessage(new UserMessage(
                UUID.randomUUID().toString(), Instant.now(),
                teammateConfig.description()
            ));
        }

        while (true) {
            try {
                // Run one agent turn
                var turn = agentLoop.runTurn(context, null);

                // Check inbox between turns
                injector.injectPendingMessages(context);

                // If no pending messages and agent stopped, go idle
                if (turn.stopReason() == StopReason.END_TURN) {
                    injector.sendIdleNotification("Turn completed, waiting for work");
                    // Block until next message arrives
                    var nextMessage = messageRouter.receive(
                        teammateConfig.name(), Duration.ofMinutes(5));
                    if (nextMessage.isEmpty()) {
                        // Heartbeat timeout - still alive, keep waiting
                        continue;
                    }
                    context.addMessage(injector.formatAsUserTurn(nextMessage.get()));
                }
            } catch (TeammateShutdownException e) {
                break; // Graceful exit
            }
        }

        messageRouter.removeInbox(teammateConfig.name());
        eventBus.publish(new ChelavaEvent.TeammateLeft(
            UUID.randomUUID().toString(), Instant.now(), "team-manager",
            messageRouter.teamName(), teammateConfig.name()
        ));
    }
}
```

#### 10.6.2 External Process Teammates

```java
/**
 * Spawns a teammate as a separate JVM process.
 * Used for plugin-heavy teammates (requires classloading) or
 * when full process isolation is needed.
 */
public class ExternalProcessTeammateSpawner {

    /**
     * Spawn a teammate as a separate JVM process using ProcessBuilder.
     * The subprocess receives team context via environment variables
     * (matching Claude Code's CLAUDE_CODE_TEAM_NAME, etc.).
     */
    public TeammateHandle spawn(TeammateConfig config, TeamConfig teamConfig) {
        var agentId = UUID.randomUUID().toString();

        var processBuilder = new ProcessBuilder(
            buildChelavaCommand(config)
        );

        // Set environment variables (matching Claude Code's env vars)
        var env = processBuilder.environment();
        env.put("CHELAVA_TEAM_NAME", teamConfig.teamName());
        env.put("CHELAVA_AGENT_ID", agentId);
        env.put("CHELAVA_AGENT_NAME", config.name());
        env.put("CHELAVA_AGENT_TYPE", config.agentType());
        env.put("CHELAVA_PLAN_MODE_REQUIRED", String.valueOf(config.planModeRequired()));
        env.put("CHELAVA_PARENT_SESSION_ID", AgentContext.SESSION_ID.get());

        if (config.workingDirectory() != null) {
            processBuilder.directory(config.workingDirectory().toFile());
        }

        // Register file-based inbox
        messageRouter.registerInbox(config.name(), TeammateBackend.EXTERNAL_PROCESS);

        try {
            var process = processBuilder.start();
            // Monitor process on a virtual thread
            Thread.startVirtualThread(() -> monitorProcess(config.name(), process));
            return new TeammateHandle(
                config.name(), agentId, TeammateBackend.EXTERNAL_PROCESS,
                AgentState.STARTING, Instant.now()
            );
        } catch (IOException e) {
            throw new TeammateSpawnException(
                "Failed to spawn external teammate: " + config.name(), e);
        }
    }

    private void monitorProcess(String name, Process process) {
        try {
            int exitCode = process.waitFor();
            if (exitCode != 0) {
                logger.warn("Teammate '{}' exited with code {}", name, exitCode);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        }
    }
}
```

#### 10.6.3 Scoped Values for Team Context

```java
/**
 * Team context propagated to teammates via ScopedValue.
 * Matches Claude Code's environment variables but uses Java's
 * zero-cost ScopedValue instead.
 *
 * <p>For external-process teammates, these values are set from
 * the CHELAVA_* environment variables at process startup.
 */
public final class TeamContext {
    public static final ScopedValue<String> TEAM_NAME = ScopedValue.newInstance();
    public static final ScopedValue<String> AGENT_ID = ScopedValue.newInstance();
    public static final ScopedValue<String> AGENT_NAME = ScopedValue.newInstance();
    public static final ScopedValue<String> AGENT_TYPE = ScopedValue.newInstance();
    public static final ScopedValue<String> AGENT_COLOR = ScopedValue.newInstance();
    public static final ScopedValue<Boolean> PLAN_MODE_REQUIRED = ScopedValue.newInstance();
    public static final ScopedValue<String> PARENT_SESSION_ID = ScopedValue.newInstance();

    private TeamContext() {} // Utility class
}
```

### 10.7 Plan Approval Flow

Plan approval allows the team lead to review teammate plans before execution. This matches Claude Code's plan_approval_request/plan_approval_response protocol.

```
Teammate (planModeRequired=true)              Team Lead
    |                                              |
    |  [Works in read-only plan mode]              |
    |  [Calls ExitPlanMode when plan is ready]     |
    |                                              |
    |--- PlanApprovalRequest(planContent) -------->|
    |                                              |
    |                      [Lead reviews the plan] |
    |                                              |
    |<-- PlanApprovalResponse(approved=true) ------|
    |                                              |
    |  [Exits plan mode, proceeds with execution]  |
    |                                              |
    |  --- OR ---                                  |
    |                                              |
    |<-- PlanApprovalResponse(approved=false, ---- |
    |                        feedback="...")        |
    |                                              |
    |  [Stays in plan mode, revises based on       |
    |   feedback, re-submits via ExitPlanMode]     |
```

### 10.8 Hook Integration

Agent team hooks provide deterministic control over teammate behavior:

```java
/**
 * Team-specific hook events that fire during team operations.
 * These use exit codes only (no JSON control), matching Claude Code.
 */

// TeammateIdle hook: fires when a teammate is about to go idle
// exit 0 = allow idle (default)
// exit 2 = keep working (reject idle, teammate continues)
// Other = allow idle, stderr logged

// TaskCompleted hook: fires when a task is being marked as completed
// exit 0 = allow completion (default)
// exit 2 = reject completion (task stays in_progress, stderr sent as feedback)
// Other = allow completion, stderr logged
```

### 10.9 File Structure

```
~/.chelava/
  teams/
    {team-name}/
      config.json            # Team config with members array
      inboxes/
        {agent-name}.json    # Per-agent inbox (file-based transport)
        {agent-name}.lock    # Lock file for atomic inbox access

  tasks/
    {team-name}/
      .lock                  # Global lock file for task operations
      counter.txt            # Auto-incrementing task ID counter
      1.json                 # Task 1
      2.json                 # Task 2
      ...
```

### 10.10 Coordination Patterns

| Pattern | Description | Example |
|---------|-------------|---------|
| **Delegate mode** | Team lead restricted to coordination-only tools (Shift+Tab toggle). Cannot edit files directly, only assigns tasks and reviews results. | Lead creates tasks, teammates implement |
| **Plan approval** | Teammates work in read-only plan mode until lead approves via PlanApprovalResponse. | Architect teammate plans, lead approves, implementer executes |
| **Self-claiming** | Teammates auto-claim next unblocked task after completing one. Prefer lowest ID first. | After finishing task #3, teammate claims task #5 (next available) |
| **Quality gates** | TeammateIdle and TaskCompleted hooks enforce standards via exit codes. | Hook runs test suite before allowing task completion |
| **Parallel specialists** | Multiple teammates with different expertise work simultaneously on independent tasks. | "arch" designs API, "impl" builds UI, "test" writes tests |
| **Pipeline** | Sequential workflow where each teammate's output feeds the next, coordinated via task dependencies. | Research -> Design -> Implement -> Test |
| **Competing hypotheses** | Multiple teammates investigate the same problem with different approaches in parallel. | Teammate A tries approach X, teammate B tries approach Y |
| **Coordinated refactoring** | Each teammate owns a layer (frontend, backend, tests) during cross-cutting changes. | Three teammates refactor auth system across all layers |

### 10.11 Subagents vs Agent Teams

| Aspect | Subagents | Agent Teams |
|--------|-----------|-------------|
| **Context** | Own window; condensed results return to caller | Own window; fully independent sessions |
| **Communication** | Report back to parent agent only | Teammates message each other directly (DMs, broadcast) |
| **Coordination** | Parent agent manages all delegation | Shared task list with self-claiming and dependencies |
| **Lifecycle** | Spawned and completed within a turn | Persistent for the session; explicit spawn/shutdown |
| **Nesting** | Cannot spawn sub-subagents | Cannot nest teams (one team per session) |
| **Execution** | Always in-process (virtual thread) | In-process (virtual thread) or external process |
| **Cost** | Lower (summarized results back) | Higher (each teammate is a full agent session) |
| **Best for** | Focused tasks where only the result matters | Complex work requiring discussion and coordination |
| **Lead role** | Parent agent decides everything | Lead can use Delegate mode (coordination-only) |
| **Plan approval** | Not applicable | Teammates can require lead approval before execution |
| **Hooks** | SubagentStart/SubagentStop hooks | TeammateIdle/TaskCompleted hooks |

### 10.12 Constraints and Limitations

Matching Claude Code's current constraints:

- **One team per session**: A session can have at most one active team
- **No nested teams**: Teams cannot spawn sub-teams
- **Fixed lead**: The session that creates the team is always the lead; leadership cannot transfer
- **No session resumption for in-process teammates**: In-process teammates lose state if the lead session exits
- **Heartbeat timeout**: Teammates that do not respond within 5 minutes are considered crashed
- **Broadcast is expensive**: Each broadcast sends N separate messages (one per teammate); use sparingly

### 10.13 Integration with MessageQueue (Section 13)

The team message system is built on top of the existing `MessageQueue` infrastructure from Section 13. The `TeamMessageRouter` uses `MessageQueue` as its transport layer:

| Team Operation | MessageQueue Mapping |
|---|---|
| Direct message | `messageQueue.send("team.{teamName}.agent.{name}", message)` |
| Broadcast | `messageQueue.publish("team.{teamName}.broadcast", message)` |
| Shutdown request | `messageQueue.send("team.{teamName}.agent.{name}", shutdownRequest)` |
| Plan approval | `messageQueue.send("team.{teamName}.agent.{name}", planApproval)` |
| Idle notification | `messageQueue.publish("team.{teamName}.idle", idleNotification)` |
| Peer DM visibility | Lead subscribes to `team.{teamName}.peer-dms` topic (summary only) |
| Task events | `messageQueue.publish("team.{teamName}.tasks", taskEvent)` |
| Heartbeat | `messageQueue.publish("team.{teamName}.heartbeat", heartbeatPing)` |

For in-process teammates, these map to `BlockingQueue` operations (zero-copy, no serialization).
For external-process teammates, these map to file-based inbox operations (JSON serialization + file locks).

The `TeamMessageRouter` implementation selects the appropriate transport based on the recipient's `TeammateBackend` type, making the communication model transparent to the agent code.

---

## 11. Skills and Commands System

### 11.1 Skills (Model-Invocable)

Skills are capabilities that the agent automatically invokes based on task context:

```
.chelava/skills/
  code-review/
    SKILL.md
  api-conventions/
    SKILL.md
```

```yaml
# SKILL.md format
---
name: code-review
description: Reviews code for best practices. Use when reviewing code or PRs.
disable-model-invocation: false
---

Review guidelines and instructions in markdown...
```

The agent compares user requests against registered skill descriptions and invokes matching skills automatically.

### 11.2 Commands (User-Invocable)

Commands are explicit slash commands triggered by the user:

```
.chelava/commands/
  commit.md       # /commit
  review.md       # /review
  deploy.md       # /deploy
```

Commands support `$ARGUMENTS` placeholder for dynamic input.

### 11.3 Plugin Bundles

Plugins package multiple extension types into distributable units:

```
my-plugin/
  .chelava-plugin/
    plugin.json          # Manifest (name, version, author)
  commands/              # Slash commands
  agents/                # Custom subagent definitions
  skills/                # Model-invocable capabilities
  hooks/
    hooks.json           # Lifecycle event handlers
  .mcp.json              # MCP server configurations
```

Skills from plugins are namespaced: `/plugin-name:skill-name` to prevent conflicts.

---

## 12. Adaptive Skills System

### 12.1 Overview

Chelava's skills system goes beyond static Markdown definitions to become a **self-learning, adaptive** system. Skills evolve based on usage outcomes, user corrections, and accumulated auto-memory insights. When the agent detects repeated patterns, it can automatically propose new skills. This creates a continuous improvement loop where the agent becomes more effective over time.

### 12.2 Skill Lifecycle

```
                         User approves
    +-------+          +-----------+         +----------+
    | Draft  | -------> |  Active   | ------> |Deprecated|
    +-------+          +-----------+         +----------+
        ^                    |                     |
        |                    | Refinement          | Re-activate
        |                    v                     |
        |              +-----------+               |
        +------------- |  Active   | <-------------+
                       | (updated) |
                       +-----------+
                             |
                             | Persistent failures / user disables
                             v
                       +-----------+
                       | Disabled  |
                       +-----------+
```

```java
/**
 * Sealed interface representing the lifecycle states of a skill.
 * Each state carries metadata relevant to that phase.
 */
public sealed interface SkillState permits
    SkillState.Draft,
    SkillState.Active,
    SkillState.Deprecated,
    SkillState.Disabled {

    /**
     * A skill proposed by the system or user, pending approval before activation.
     * @param proposedAt when the draft was created
     * @param source how the draft was generated (manual, auto-generated, refined)
     * @param confidence system confidence score (0.0-1.0) for auto-generated drafts
     */
    record Draft(
        Instant proposedAt,
        SkillSource source,
        double confidence
    ) implements SkillState {}

    /**
     * An approved and actively used skill.
     * @param activatedAt when the skill was activated
     * @param version current version number (incremented on each refinement)
     * @param metrics current performance metrics
     */
    record Active(
        Instant activatedAt,
        int version,
        SkillMetrics metrics
    ) implements SkillState {}

    /**
     * A skill that has been superseded by a newer version but retained for rollback.
     * @param deprecatedAt when the skill was deprecated
     * @param reason why the skill was deprecated
     * @param supersededBy the skill ID that replaced this one (if any)
     */
    record Deprecated(
        Instant deprecatedAt,
        String reason,
        String supersededBy
    ) implements SkillState {}

    /**
     * A skill explicitly disabled due to persistent failures or user action.
     * @param disabledAt when the skill was disabled
     * @param reason why the skill was disabled
     * @param canReEnable whether the skill can be re-enabled
     */
    record Disabled(
        Instant disabledAt,
        String reason,
        boolean canReEnable
    ) implements SkillState {}
}

/**
 * How a skill draft was generated.
 */
public enum SkillSource {
    MANUAL,          // User created the skill manually
    AUTO_GENERATED,  // System detected repeated patterns and proposed a new skill
    REFINED          // System refined an existing skill based on failure analysis
}
```

### 12.3 Skill Metadata and Definition

```java
/**
 * Complete skill definition combining the static SKILL.md content
 * with runtime metadata and state.
 */
public record SkillDefinition(
    String id,
    String name,
    String description,
    String instructions,         // Markdown body from SKILL.md
    SkillState state,
    SkillMetadata metadata,
    Path skillPath               // Filesystem path to SKILL.md
) {}

/**
 * Immutable metadata about a skill, stored as a JSON sidecar file
 * alongside SKILL.md.
 */
public record SkillMetadata(
    String id,
    String name,
    String description,
    Instant createdAt,
    Instant lastModifiedAt,
    SkillSource source,
    List<String> tags,           // For categorization and search
    List<String> relatedMemoryIds, // Links to auto-memory entries that informed this skill
    VersionHistory versionHistory
) {}

/**
 * Version history for skill rollback support.
 */
public record VersionHistory(
    int currentVersion,
    List<VersionEntry> entries
) {
    public record VersionEntry(
        int version,
        Instant timestamp,
        String changeDescription,
        String previousContent     // Skill instructions before this version
    ) {}
}
```

### 12.4 Skill Metrics and Scoring

```java
/**
 * Performance metrics tracked per skill.
 * Stored as a JSON sidecar file ({skill-id}.metrics.json) alongside SKILL.md.
 * Metrics decay over time to prioritize recent performance.
 */
public record SkillMetrics(
    String skillId,
    long invocationCount,
    long successCount,
    long failureCount,
    long userCorrectionCount,
    double averageTurnCount,     // Average turns to complete when using this skill
    Instant lastInvoked,
    Instant lastUpdated,
    double decayedScore          // Weighted score with time decay applied
) {

    /**
     * Calculate the success rate as a ratio (0.0-1.0).
     */
    public double successRate() {
        return invocationCount == 0 ? 0.0 : (double) successCount / invocationCount;
    }

    /**
     * Calculate the user correction rate as a ratio (0.0-1.0).
     * Higher correction rates indicate the skill needs refinement.
     */
    public double correctionRate() {
        return invocationCount == 0 ? 0.0 : (double) userCorrectionCount / invocationCount;
    }

    /**
     * Calculate a composite score for auto-invocation priority.
     * Factors: success rate (weight 0.4), low correction rate (0.3),
     * recency (0.2), invocation frequency (0.1).
     * Score decays exponentially over time (half-life: 30 days).
     */
    public double computeScore() {
        double successWeight = successRate() * 0.4;
        double correctionWeight = (1.0 - correctionRate()) * 0.3;
        double recencyWeight = recencyFactor() * 0.2;
        double frequencyWeight = Math.min(1.0, invocationCount / 100.0) * 0.1;
        return (successWeight + correctionWeight + recencyWeight + frequencyWeight);
    }

    private double recencyFactor() {
        if (lastInvoked == null) return 0.0;
        long daysSinceLastUse = Duration.between(lastInvoked, Instant.now()).toDays();
        double halfLifeDays = 30.0;
        return Math.exp(-0.693 * daysSinceLastUse / halfLifeDays); // Exponential decay
    }
}
```

### 12.5 Skill Learning Loop

The learning loop connects user interactions, tool outcomes, and auto-memory to continuously improve skills:

```
User Interaction
    |
    v
Agent executes skill
    |
    v
Track outcome (success / failure / user correction)
    |
    v
Auto-memory records insight (PATTERN, MISTAKE, STRATEGY)
    |
    v
SkillRefinementEngine analyzes accumulated insights
    |
    +-- Success rate drops below threshold?
    |       -> Trigger refinement
    |
    +-- User correction rate exceeds threshold?
    |       -> Trigger refinement
    |
    +-- Repeated patterns detected in auto-memory?
    |       -> Propose new auto-generated skill
    |
    v
Update SKILL.md with improved instructions
    |
    v
Next invocation uses refined skill
```

```java
/**
 * Tracks skill execution outcomes and feeds them into the learning loop.
 * Runs asynchronously on a virtual thread after each skill invocation.
 */
public interface SkillOutcomeTracker {

    /**
     * Record that a skill was invoked.
     * Called at the start of skill execution.
     */
    void recordInvocation(String skillId, String context);

    /**
     * Record the outcome of a skill invocation.
     * Called after the agent turn completes.
     *
     * @param skillId the skill that was used
     * @param outcome the execution result
     * @param turnCount how many agent turns were needed
     * @param userCorrections any user corrections during execution
     */
    void recordOutcome(String skillId, SkillOutcome outcome,
                       int turnCount, List<String> userCorrections);
}

/**
 * Possible outcomes of a skill invocation.
 */
public sealed interface SkillOutcome permits
    SkillOutcome.Success,
    SkillOutcome.Failure,
    SkillOutcome.UserCorrected {

    record Success(String summary) implements SkillOutcome {}
    record Failure(String reason, Throwable cause) implements SkillOutcome {}
    record UserCorrected(String originalAction, String correction) implements SkillOutcome {}
}
```

### 12.6 Skill Refinement Engine

```java
/**
 * Analyzes skill metrics and auto-memory to identify skills that need improvement,
 * and generates refined skill content using LLM analysis.
 *
 * Runs periodically on a virtual thread via the Scheduler, and can also
 * be triggered manually after a threshold number of failures.
 */
public interface SkillRefinementEngine {

    /**
     * Analyze a skill's metrics and decide if refinement is needed.
     * Refinement is triggered when:
     * - Success rate drops below 70% over the last 10 invocations
     * - User correction rate exceeds 30% over the last 10 invocations
     * - More than 5 consecutive failures
     *
     * @param skillId the skill to analyze
     * @return refinement decision with analysis
     */
    RefinementDecision analyze(String skillId);

    /**
     * Generate refined skill instructions using LLM analysis of failure patterns.
     * The LLM is given:
     * - Current skill content
     * - Recent failure contexts
     * - Related auto-memory entries (MISTAKE, PATTERN, STRATEGY)
     * - User corrections
     *
     * @param skillId the skill to refine
     * @param decision the analysis that triggered refinement
     * @return draft skill with improved instructions
     */
    SkillDefinition refine(String skillId, RefinementDecision decision);

    /**
     * Apply a refined skill, creating a new version.
     * The previous version is preserved in version history for rollback.
     *
     * @param refined the refined skill definition
     * @param approvedByUser whether the user explicitly approved (false = auto-applied)
     */
    void apply(SkillDefinition refined, boolean approvedByUser);

    /**
     * Rollback a skill to a previous version.
     *
     * @param skillId the skill to rollback
     * @param targetVersion the version to restore
     */
    void rollback(String skillId, int targetVersion);
}

/**
 * Result of skill refinement analysis.
 */
public sealed interface RefinementDecision permits
    RefinementDecision.NoActionNeeded,
    RefinementDecision.RefinementRecommended,
    RefinementDecision.DisableRecommended {

    record NoActionNeeded(String reason) implements RefinementDecision {}

    record RefinementRecommended(
        String reason,
        List<String> failurePatterns,
        List<String> relatedMemoryIds,
        double urgency                  // 0.0-1.0, higher = more urgent
    ) implements RefinementDecision {}

    record DisableRecommended(
        String reason,
        long consecutiveFailures
    ) implements RefinementDecision {}
}
```

### 12.7 Auto-Generated Skills

When auto-memory detects repeated patterns (e.g., user always asks the agent to perform a certain workflow in a specific way), the system can automatically propose creating a new skill.

```java
/**
 * Detects recurring patterns in auto-memory and proposes new skills.
 * Runs periodically on a virtual thread via the Scheduler.
 */
public interface SkillProposalEngine {

    /**
     * Scan auto-memory for patterns that could become skills.
     * Criteria for proposing a new skill:
     * - At least 3 PATTERN or STRATEGY entries with similar context
     * - No existing skill covers the detected pattern
     * - High confidence that the pattern is generalizable
     *
     * @return list of proposed skill drafts
     */
    List<SkillDefinition> detectAndPropose();

    /**
     * Generate a SKILL.md draft from accumulated auto-memory entries.
     * Uses LLM to synthesize patterns into coherent skill instructions.
     *
     * @param memoryIds the auto-memory entries to base the skill on
     * @param suggestedName suggested name for the new skill
     * @return a draft skill definition (state = Draft)
     */
    SkillDefinition generateDraft(List<String> memoryIds, String suggestedName);
}
```

### 12.8 Skill Registry

```java
/**
 * Central registry for all skills in the system.
 * Skills are loaded from:
 * 1. .chelava/skills/ (project-level)
 * 2. ~/.chelava/skills/ (user-level)
 * 3. Plugin skill directories
 * 4. Auto-generated drafts (pending approval)
 */
public interface SkillRegistry {

    /**
     * Get all active skills, sorted by score (highest first).
     */
    List<SkillDefinition> activeSkills();

    /**
     * Get all draft skills pending user approval.
     */
    List<SkillDefinition> pendingDrafts();

    /**
     * Find the best matching skill for a given user request.
     * Uses description matching + score-based ranking.
     *
     * @param userRequest the user's natural language request
     * @return matched skills ranked by relevance and score
     */
    List<SkillMatch> match(String userRequest);

    /**
     * Register a new skill (or update an existing one).
     */
    void register(SkillDefinition skill);

    /**
     * Approve a draft skill, transitioning it to Active state.
     */
    void approve(String skillId);

    /**
     * Reject a draft skill, removing it from the registry.
     */
    void reject(String skillId, String reason);

    /**
     * Disable a skill.
     */
    void disable(String skillId, String reason);

    /**
     * Get the metrics for a specific skill.
     */
    Optional<SkillMetrics> metrics(String skillId);
}

/**
 * Result of matching a user request to available skills.
 */
public record SkillMatch(
    SkillDefinition skill,
    double relevanceScore,     // How well the skill description matches the request
    double performanceScore    // The skill's historical performance score
) implements Comparable<SkillMatch> {

    @Override
    public int compareTo(SkillMatch other) {
        // Higher combined score = better match
        double thisScore = relevanceScore * 0.6 + performanceScore * 0.4;
        double otherScore = other.relevanceScore * 0.6 + other.performanceScore * 0.4;
        return Double.compare(otherScore, thisScore); // Descending
    }
}
```

### 12.9 Persistence Model

Skills use a file-based persistence model consistent with the existing SKILL.md convention:

```
.chelava/skills/
  code-review/
    SKILL.md                    # Skill instructions (Markdown + YAML frontmatter)
    skill-metadata.json         # Immutable metadata + version history
    skill-metrics.json          # Mutable performance metrics (updated after each invocation)
  auto-generated/
    draft-pattern-123/
      SKILL.md                  # Draft skill pending approval
      skill-metadata.json
```

Metrics JSON example:
```json
{
  "skillId": "code-review",
  "invocationCount": 47,
  "successCount": 41,
  "failureCount": 3,
  "userCorrectionCount": 3,
  "averageTurnCount": 4.2,
  "lastInvoked": "2026-02-15T14:30:00Z",
  "lastUpdated": "2026-02-15T14:30:00Z",
  "decayedScore": 0.82
}
```

### 12.10 Integration with Auto-Memory

The adaptive skills system is tightly integrated with the existing auto-memory system:

| Auto-Memory Category | Skill System Usage |
|---|---|
| `MISTAKE` | Used by SkillRefinementEngine to identify failure patterns and improve skill instructions |
| `PATTERN` | Used by SkillProposalEngine to detect recurring workflows that should become skills |
| `PREFERENCE` | Used by SkillRefinementEngine to align skill behavior with user preferences |
| `CODEBASE_INSIGHT` | Used to contextualize skill matching (e.g., project uses specific framework) |
| `STRATEGY` | Used by SkillProposalEngine to create skills from proven strategies |

### 12.11 Scheduled Tasks for Skills

| Task | Default Interval | Purpose |
|------|-----------------|---------|
| Skill metrics decay | 24 hours | Apply time-decay to all skill scores |
| Skill refinement scan | 1 hour | Check all active skills for refinement triggers |
| Pattern detection scan | 6 hours | Scan auto-memory for recurring patterns to propose as skills |
| Metrics persistence | 5 minutes | Flush in-memory metrics updates to JSON sidecar files |

---

## 13. Message Queue (Inter-Agent Communication)

### 13.1 Overview

The Message Queue provides decoupled, asynchronous inter-agent communication for Chelava. It replaces the direct Mailbox pattern (which required agents to know each other's identity) with topic-based pub/sub and named queues. This is critical for agent teams and future scaling.

The message queue is **in-process** (no external broker like Kafka or RabbitMQ), using only `java.base` types: `BlockingQueue`, `ConcurrentHashMap`, virtual threads, and `ScheduledExecutorService`. It is GraalVM native image compatible.

### 13.2 Architecture

```
Producer (Agent A)                              Consumer (Agent B)
    |                                               ^
    |  send("task-assignments", msg)                |  receive("task-assignments")
    v                                               |
+------------------------------------------------------------------+
|                        MessageQueue                                |
|                                                                    |
|  +----------------------+    +-----------------------------+       |
|  | Point-to-Point Queues|    | Pub/Sub Topics             |       |
|  |                      |    |                             |       |
|  | "agent-a-inbox"  [Q] |    | "team-events"   [T]-->[S1] |       |
|  | "agent-b-inbox"  [Q] |    |                  |-->[S2]   |       |
|  | "task-assignments"[Q]|    |                  |-->[S3]   |       |
|  +----------------------+    | "health-beats" [T]-->[S1]   |       |
|                              +-----------------------------+       |
|                                                                    |
|  +---------------------------+    +---------------------------+    |
|  | Dead Letter Queue (DLQ)   |    | Message Persistence       |    |
|  | Failed/expired messages   |    | Optional JSONL per topic  |    |
|  +---------------------------+    +---------------------------+    |
|                                                                    |
|  +---------------------------+                                     |
|  | TTL Expiration Scheduler  |                                     |
|  | (ScheduledExecutorService)|                                     |
|  +---------------------------+                                     |
+------------------------------------------------------------------+
```

### 13.3 Message Types

```java
/**
 * Sealed interface for all inter-agent messages.
 * Each message type is a record with immutable fields.
 * Messages are self-describing and serializable to JSON for persistence.
 */
public sealed interface AgentMessage permits
    AgentMessage.TaskAssignment,
    AgentMessage.TaskResult,
    AgentMessage.ChatMessage,
    AgentMessage.BroadcastMessage,
    AgentMessage.ShutdownRequest,
    AgentMessage.PlanApproval,
    AgentMessage.HeartbeatPing {

    /** Unique message identifier. */
    String messageId();

    /** Timestamp when the message was created. */
    Instant timestamp();

    /** Correlation ID for request-reply patterns. Null for one-way messages. */
    String correlationId();

    /**
     * Task assignment from team lead to a specific agent.
     */
    record TaskAssignment(
        String messageId,
        Instant timestamp,
        String correlationId,
        String taskId,
        String assignee,
        String description,
        Map<String, String> metadata
    ) implements AgentMessage {}

    /**
     * Result of a completed task, sent from agent back to team lead.
     */
    record TaskResult(
        String messageId,
        Instant timestamp,
        String correlationId,
        String taskId,
        String result,
        TaskStatus status,
        Duration executionTime
    ) implements AgentMessage {}

    /**
     * Direct message between two agents.
     */
    record ChatMessage(
        String messageId,
        Instant timestamp,
        String correlationId,
        String from,
        String to,
        String content,
        MessagePriority priority
    ) implements AgentMessage {}

    /**
     * Broadcast message to all agents in a team.
     */
    record BroadcastMessage(
        String messageId,
        Instant timestamp,
        String correlationId,
        String from,
        String content,
        String teamName
    ) implements AgentMessage {}

    /**
     * Request for an agent to shut down gracefully.
     */
    record ShutdownRequest(
        String messageId,
        Instant timestamp,
        String correlationId,
        String target,
        String reason,
        Duration gracePeriod
    ) implements AgentMessage {}

    /**
     * Plan approval or rejection from team lead to teammate.
     */
    record PlanApproval(
        String messageId,
        Instant timestamp,
        String correlationId,
        String planId,
        boolean approved,
        String feedback
    ) implements AgentMessage {}

    /**
     * Heartbeat ping from an agent to the health monitoring topic.
     */
    record HeartbeatPing(
        String messageId,
        Instant timestamp,
        String correlationId,
        String agentId,
        AgentState state,
        int completedTasks,
        TokenUsage sessionUsage
    ) implements AgentMessage {}
}

public enum TaskStatus { PENDING, IN_PROGRESS, COMPLETED, FAILED, CANCELLED }

public enum MessagePriority { LOW, NORMAL, HIGH, URGENT }
```

### 13.4 Queue and Topic Configuration

```java
/**
 * Configuration for a point-to-point queue.
 */
public record QueueConfig(
    String name,
    int capacity,                    // Max messages in queue (backpressure)
    Duration messageTtl,             // Time-to-live for messages (null = no expiry)
    boolean persistent,              // Whether to persist messages to JSONL
    DeadLetterConfig deadLetter      // DLQ configuration (null = no DLQ)
) {
    public static QueueConfig defaults(String name) {
        return new QueueConfig(name, 1024, Duration.ofHours(1), false, null);
    }

    public static QueueConfig persistent(String name) {
        return new QueueConfig(
            name, 1024, Duration.ofHours(24), true,
            new DeadLetterConfig("dlq-" + name, 256, Duration.ofDays(7))
        );
    }
}

/**
 * Configuration for a pub/sub topic.
 */
public record TopicConfig(
    String name,
    int subscriberQueueCapacity,     // Per-subscriber queue capacity
    Duration messageTtl,             // Time-to-live for messages
    boolean persistent               // Whether to persist messages to JSONL
) {
    public static TopicConfig defaults(String name) {
        return new TopicConfig(name, 256, Duration.ofMinutes(30), false);
    }
}

/**
 * Configuration for a dead letter queue.
 */
public record DeadLetterConfig(
    String queueName,
    int capacity,
    Duration retentionPeriod
) {}
```

### 13.5 MessageQueue Interface

```java
/**
 * In-process message queue for decoupled inter-agent communication.
 * Supports point-to-point queues, pub/sub topics, and request-reply patterns.
 *
 * <p>Implementation uses only java.base types:
 * <ul>
 *   <li>{@link java.util.concurrent.BlockingQueue} per queue/subscriber</li>
 *   <li>{@link java.util.concurrent.ConcurrentHashMap} for queue/topic registry</li>
 *   <li>Virtual threads for consumer processing</li>
 *   <li>{@link java.util.concurrent.ScheduledExecutorService} for TTL expiration</li>
 * </ul>
 *
 * <p>Zero external dependencies. GraalVM native image compatible.
 */
public interface MessageQueue extends AutoCloseable {

    // --- Point-to-Point Queue Operations ---

    /**
     * Create a named queue with the given configuration.
     * If the queue already exists, this is a no-op.
     */
    void createQueue(String name, QueueConfig config);

    /**
     * Send a message to a named queue.
     * If the queue is full, this blocks until space is available
     * (natural backpressure via BlockingQueue capacity).
     *
     * @throws QueueNotFoundException if the queue does not exist
     */
    void send(String queueName, AgentMessage message);

    /**
     * Receive a message from a named queue, blocking up to the specified timeout.
     * Returns null if no message is available within the timeout.
     *
     * @param queueName the queue to receive from
     * @param timeout maximum time to wait
     * @return the next message, or null if timeout expires
     */
    AgentMessage receive(String queueName, Duration timeout);

    /**
     * Receive a message from a named queue without blocking.
     * Returns null immediately if no message is available.
     */
    AgentMessage poll(String queueName);

    // --- Pub/Sub Topic Operations ---

    /**
     * Create a named topic with the given configuration.
     * If the topic already exists, this is a no-op.
     */
    void createTopic(String name, TopicConfig config);

    /**
     * Publish a message to a topic. The message is delivered
     * to all active subscribers via their individual queues.
     * Non-blocking: uses offer() to each subscriber's queue.
     */
    void publish(String topicName, AgentMessage message);

    /**
     * Subscribe to a topic. Creates a per-subscriber BlockingQueue
     * and starts a virtual thread that loops on queue.take() to
     * deliver messages to the handler.
     *
     * @param topicName the topic to subscribe to
     * @param handler callback invoked for each message
     * @return subscription handle for unsubscribing
     */
    MessageSubscription subscribe(String topicName, MessageHandler handler);

    // --- Request-Reply Pattern ---

    /**
     * Send a request message and wait for a correlated reply.
     * Assigns a correlation ID to the message and returns a
     * CompletableFuture that completes when a reply with the
     * same correlation ID arrives.
     *
     * @param queueName the queue to send the request to
     * @param message the request message
     * @param replyQueueName the queue to listen for the reply on
     * @param timeout maximum time to wait for a reply
     * @return future that completes with the reply message
     */
    CompletableFuture<AgentMessage> request(
        String queueName,
        AgentMessage message,
        String replyQueueName,
        Duration timeout
    );

    /**
     * Send a reply to a request. The reply must have the same
     * correlation ID as the original request.
     */
    void reply(String queueName, AgentMessage reply);

    // --- Administration ---

    /**
     * Get statistics for a named queue or topic.
     */
    QueueStats stats(String name);

    /**
     * List all queues and topics.
     */
    List<String> listQueues();
    List<String> listTopics();

    /**
     * Get all messages in the dead letter queue for a given queue.
     */
    List<DeadLetterEntry> deadLetters(String queueName);

    /**
     * Retry a dead letter message by re-enqueuing it.
     */
    void retryDeadLetter(String queueName, String messageId);
}

/**
 * Handler for subscribed topic messages.
 */
@FunctionalInterface
public interface MessageHandler {
    void handle(AgentMessage message);
}

/**
 * Subscription handle for unsubscribing from a topic.
 */
public interface MessageSubscription extends AutoCloseable {
    void unsubscribe();
    boolean isActive();
    String topicName();
    String subscriberId();
}

/**
 * Statistics for a queue or topic.
 */
public record QueueStats(
    String name,
    long enqueuedCount,
    long dequeuedCount,
    long expiredCount,
    long deadLetteredCount,
    int currentSize,
    int capacity,
    int subscriberCount,        // 0 for point-to-point queues
    Instant createdAt,
    Instant lastMessageAt
) {}

/**
 * Entry in the dead letter queue.
 */
public record DeadLetterEntry(
    AgentMessage originalMessage,
    String failureReason,
    Instant deadLetteredAt,
    int retryCount
) {}
```

### 13.6 Implementation

```java
/**
 * In-process message queue implementation using BlockingQueue and virtual threads.
 * Zero external dependencies. GraalVM native image compatible.
 */
public class InProcessMessageQueue implements MessageQueue {

    private final ConcurrentHashMap<String, ManagedQueue> queues = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ManagedTopic> topics = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, CompletableFuture<AgentMessage>> pendingRequests =
        new ConcurrentHashMap<>();
    private final ScheduledExecutorService ttlScheduler;
    private final Path persistenceDir;  // null if persistence is disabled

    /**
     * Internal queue wrapper with metadata and TTL support.
     */
    private record ManagedQueue(
        QueueConfig config,
        BlockingQueue<TimestampedMessage> queue,
        BlockingQueue<DeadLetterEntry> deadLetterQueue,  // null if no DLQ configured
        AtomicLong enqueuedCount,
        AtomicLong dequeuedCount,
        AtomicLong expiredCount,
        AtomicLong deadLetteredCount,
        Instant createdAt,
        AtomicReference<Instant> lastMessageAt
    ) {}

    /**
     * Message wrapper with timestamp for TTL enforcement.
     */
    private record TimestampedMessage(
        AgentMessage message,
        Instant enqueueTime,
        Duration ttl
    ) {
        boolean isExpired() {
            return ttl != null &&
                Duration.between(enqueueTime, Instant.now()).compareTo(ttl) > 0;
        }
    }

    /**
     * Internal topic wrapper with subscriber management.
     */
    private record ManagedTopic(
        TopicConfig config,
        List<TopicSubscriber> subscribers,
        Instant createdAt,
        AtomicReference<Instant> lastMessageAt,
        AtomicLong publishedCount
    ) {}

    /**
     * A subscriber to a topic with its own queue and virtual thread.
     */
    private record TopicSubscriber(
        String subscriberId,
        BlockingQueue<AgentMessage> queue,
        MessageHandler handler,
        Thread consumerThread,
        AtomicBoolean active
    ) {}

    public InProcessMessageQueue(Path persistenceDir) {
        this.persistenceDir = persistenceDir;
        this.ttlScheduler = Executors.newSingleThreadScheduledExecutor(
            Thread.ofVirtual().name("mq-ttl-expiry").factory()
        );
        // Schedule TTL expiration sweep every 10 seconds
        ttlScheduler.scheduleAtFixedRate(
            this::expireMessages, 10, 10, TimeUnit.SECONDS
        );
    }

    @Override
    public void send(String queueName, AgentMessage message) {
        var managed = queues.get(queueName);
        if (managed == null) throw new QueueNotFoundException(queueName);

        var timestamped = new TimestampedMessage(
            message, Instant.now(), managed.config().messageTtl()
        );

        try {
            managed.queue().put(timestamped); // Blocks if full = backpressure
            managed.enqueuedCount().incrementAndGet();
            managed.lastMessageAt().set(Instant.now());

            // Check if this is a reply to a pending request
            if (message.correlationId() != null) {
                var future = pendingRequests.remove(message.correlationId());
                if (future != null) {
                    future.complete(message);
                }
            }

            // Persist if configured
            if (managed.config().persistent() && persistenceDir != null) {
                persistMessage(queueName, message);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new MessageQueueException("Interrupted while sending to " + queueName, e);
        }
    }

    @Override
    public AgentMessage receive(String queueName, Duration timeout) {
        var managed = queues.get(queueName);
        if (managed == null) throw new QueueNotFoundException(queueName);

        try {
            while (true) {
                var timestamped = managed.queue().poll(
                    timeout.toMillis(), TimeUnit.MILLISECONDS
                );
                if (timestamped == null) return null; // Timeout

                if (timestamped.isExpired()) {
                    managed.expiredCount().incrementAndGet();
                    deadLetter(managed, timestamped, "Message expired (TTL exceeded)");
                    continue; // Skip expired, try next
                }

                managed.dequeuedCount().incrementAndGet();
                return timestamped.message();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    @Override
    public void publish(String topicName, AgentMessage message) {
        var managed = topics.get(topicName);
        if (managed == null) throw new TopicNotFoundException(topicName);

        managed.publishedCount().incrementAndGet();
        managed.lastMessageAt().set(Instant.now());

        for (var subscriber : managed.subscribers()) {
            if (subscriber.active().get()) {
                // Non-blocking offer; drops message if subscriber queue is full
                if (!subscriber.queue().offer(message)) {
                    logger.trace("Message dropped for subscriber {} on topic {} (queue full)",
                        subscriber.subscriberId(), topicName);
                }
            }
        }
    }

    @Override
    public MessageSubscription subscribe(String topicName, MessageHandler handler) {
        var managed = topics.get(topicName);
        if (managed == null) throw new TopicNotFoundException(topicName);

        var subscriberId = UUID.randomUUID().toString();
        var queue = new ArrayBlockingQueue<AgentMessage>(
            managed.config().subscriberQueueCapacity()
        );
        var active = new AtomicBoolean(true);

        var consumerThread = Thread.startVirtualThread(() -> {
            while (active.get()) {
                try {
                    var message = queue.take(); // Blocks cheaply on virtual thread
                    handler.handle(message);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    logger.warn("Subscriber {} handler failed on topic {}: {}",
                        subscriberId, topicName, e.getMessage());
                }
            }
        });

        var subscriber = new TopicSubscriber(
            subscriberId, queue, handler, consumerThread, active
        );
        managed.subscribers().add(subscriber);

        return new DefaultMessageSubscription(topicName, subscriberId, () -> {
            active.set(false);
            consumerThread.interrupt();
            managed.subscribers().remove(subscriber);
        });
    }

    @Override
    public CompletableFuture<AgentMessage> request(
            String queueName, AgentMessage message,
            String replyQueueName, Duration timeout) {

        var correlationId = message.correlationId() != null
            ? message.correlationId()
            : UUID.randomUUID().toString();

        var future = new CompletableFuture<AgentMessage>();
        pendingRequests.put(correlationId, future);

        // Send the request
        send(queueName, message);

        // Set timeout
        future.orTimeout(timeout.toMillis(), TimeUnit.MILLISECONDS)
            .whenComplete((result, error) -> pendingRequests.remove(correlationId));

        return future;
    }

    /**
     * Periodic sweep to expire messages that have exceeded their TTL.
     */
    private void expireMessages() {
        for (var entry : queues.entrySet()) {
            var managed = entry.getValue();
            managed.queue().removeIf(timestamped -> {
                if (timestamped.isExpired()) {
                    managed.expiredCount().incrementAndGet();
                    deadLetter(managed, timestamped, "Message expired (TTL exceeded)");
                    return true;
                }
                return false;
            });
        }
    }

    /**
     * Move a message to the dead letter queue.
     */
    private void deadLetter(ManagedQueue managed, TimestampedMessage msg, String reason) {
        if (managed.deadLetterQueue() != null) {
            managed.deadLetterQueue().offer(
                new DeadLetterEntry(msg.message(), reason, Instant.now(), 0)
            );
            managed.deadLetteredCount().incrementAndGet();
        }
    }

    /**
     * Persist a message to a JSONL file for session durability.
     */
    private void persistMessage(String queueName, AgentMessage message) {
        // Append-only JSONL file per queue: {persistenceDir}/{queueName}.jsonl
        var file = persistenceDir.resolve(queueName + ".jsonl");
        try (var writer = Files.newBufferedWriter(file, StandardOpenOption.CREATE,
                StandardOpenOption.APPEND)) {
            writer.write(Jackson.toJson(message));
            writer.newLine();
        } catch (IOException e) {
            logger.warn("Failed to persist message to {}: {}", file, e.getMessage());
        }
    }
}
```

### 13.7 Communication Patterns

#### Point-to-Point Queue (1:1 Task Assignment)

```java
// Team lead assigns a task to a specific agent
messageQueue.createQueue("agent-impl-inbox", QueueConfig.defaults("agent-impl-inbox"));

messageQueue.send("agent-impl-inbox", new AgentMessage.TaskAssignment(
    UUID.randomUUID().toString(),
    Instant.now(),
    null,                    // No correlation (one-way)
    "task-42",
    "agent-impl",
    "Implement the login form component",
    Map.of("priority", "high")
));

// Agent receives the task
var message = messageQueue.receive("agent-impl-inbox", Duration.ofSeconds(30));
```

#### Pub/Sub Topic (1:N Broadcast Events)

```java
// Create a team-wide events topic
messageQueue.createTopic("team-events", TopicConfig.defaults("team-events"));

// All teammates subscribe
messageQueue.subscribe("team-events", msg -> {
    if (msg instanceof AgentMessage.BroadcastMessage broadcast) {
        logger.info("Team announcement from {}: {}", broadcast.from(), broadcast.content());
    }
});

// Team lead broadcasts
messageQueue.publish("team-events", new AgentMessage.BroadcastMessage(
    UUID.randomUUID().toString(),
    Instant.now(),
    null,
    "team-lead",
    "All tests passing. Proceed to integration.",
    "my-team"
));
```

#### Request-Reply (Correlated Request/Response)

```java
// Team lead requests plan approval
var future = messageQueue.request(
    "agent-arch-inbox",
    new AgentMessage.TaskAssignment(
        UUID.randomUUID().toString(),
        Instant.now(),
        UUID.randomUUID().toString(), // Correlation ID
        "plan-review-1",
        "agent-arch",
        "Review and approve the architecture plan",
        Map.of()
    ),
    "team-lead-inbox",
    Duration.ofMinutes(5)
);

// Agent processes and replies
var reply = future.join(); // Blocks on virtual thread (cheap)
```

#### Dead Letter Queue (Failed/Expired Messages)

```java
// Configure a queue with DLQ
messageQueue.createQueue("critical-tasks", new QueueConfig(
    "critical-tasks", 512, Duration.ofMinutes(30), true,
    new DeadLetterConfig("dlq-critical-tasks", 256, Duration.ofDays(7))
));

// Later, inspect dead letters
var deadLetters = messageQueue.deadLetters("critical-tasks");
for (var entry : deadLetters) {
    logger.warn("Dead letter: {} reason: {}", entry.originalMessage(), entry.failureReason());
    // Optionally retry
    messageQueue.retryDeadLetter("critical-tasks", entry.originalMessage().messageId());
}
```

### 13.8 Persistence Model

Message persistence is optional and uses append-only JSONL files:

```
~/.chelava/teams/{team-name}/
  messages/
    agent-a-inbox.jsonl         # Point-to-point queue messages
    agent-b-inbox.jsonl
    team-events.jsonl           # Topic messages
    dlq-critical-tasks.jsonl    # Dead letter queue
```

Each line is a JSON-serialized `AgentMessage`:
```json
{"type":"TaskAssignment","messageId":"abc-123","timestamp":"2026-02-16T10:00:00Z","correlationId":null,"taskId":"task-42","assignee":"agent-impl","description":"Implement login form","metadata":{"priority":"high"}}
{"type":"ChatMessage","messageId":"def-456","timestamp":"2026-02-16T10:01:00Z","correlationId":null,"from":"agent-arch","to":"agent-impl","content":"Use React Hook Form for validation","priority":"NORMAL"}
```

### 13.9 Migration from Mailbox to MessageQueue

The current `Mailbox` interface is replaced by `MessageQueue`:

| Mailbox (Old) | MessageQueue (New) | Pattern |
|---|---|---|
| `mailbox.send(recipientId, content)` | `messageQueue.send(recipientId + "-inbox", chatMessage)` | Point-to-point |
| `mailbox.broadcast(content)` | `messageQueue.publish("team-events", broadcastMessage)` | Pub/sub |
| `mailbox.receive(teammateId)` | `messageQueue.receive(myId + "-inbox", timeout)` | Point-to-point |
| (Not supported) | `messageQueue.request(queue, msg, replyQueue, timeout)` | Request-reply |
| (Not supported) | `messageQueue.deadLetters(queue)` | Dead letter |
| (Not supported) | `messageQueue.subscribe(topic, handler)` | Topic subscription |

---

## 14. Session and Conversation Persistence

### 12.1 Session Storage

```
~/.chelava/
  projects/
    {project-hash}/
      sessions/
        {session-id}/
          transcript.jsonl     # Conversation messages
          metadata.json        # Session metadata (model, start time, etc.)
      subagents/
        agent-{id}.jsonl       # Subagent transcripts
      memory/
        MEMORY.md              # Auto-memory (first 200 lines loaded at startup)
        patterns.md
        debugging.md
```

### 12.2 Session Lifecycle

```java
public interface SessionManager {
    Session create(SessionConfig config);
    Session resume(String sessionId);
    List<SessionSummary> list(Path projectRoot);
    void cleanup(Duration olderThan); // Default: 30 days
}

public record Session(
    String id,
    Path projectRoot,
    Instant startTime,
    SessionConfig config,
    ConversationContext context
) {}
```

### 12.3 JSONL Transcript Format

Each line is a JSON object representing a message or event:

```json
{"type": "user", "id": "msg_1", "timestamp": "...", "content": "Fix the login bug"}
{"type": "assistant", "id": "msg_2", "timestamp": "...", "content": [...], "usage": {...}}
{"type": "tool_result", "id": "msg_3", "toolUseId": "tu_1", "content": "..."}
{"type": "compaction", "id": "msg_4", "summary": "...", "removedCount": 15}
```

---

## 13. Configuration Architecture

### 13.1 Configuration Hierarchy

```
(lowest priority)
1. Chelava defaults (built-in)
2. Global config:    ~/.chelava/config.json
3. Project config:   .chelava/config.json
4. Environment vars: CHELAVA_MODEL, CHELAVA_API_KEY, etc.
5. CLI flags:        --model, --max-turns, etc.
(highest priority)
```

### 13.2 Configuration Schema

```java
public record ChelavaConfig(
    AgentConfig agent,
    Map<String, LLMProviderConfig> providers,
    SecurityConfig security,
    MemoryConfig memory,
    UIConfig ui,
    Map<String, PluginConfig> plugins
) {
    public record AgentConfig(
        String defaultModel,
        int maxTurns,
        Duration timeout,
        ThinkingLevel thinkingLevel,
        List<String> enabledTools
    ) {}

    public record SecurityConfig(
        PermissionMode permissionMode, // auto, prompt, strict
        List<String> allowedPaths,
        List<String> blockedCommands,
        boolean sandboxEnabled
    ) {}

    public record MemoryConfig(
        boolean autoMemoryEnabled,
        int maxConversationTokens,
        CompactionStrategy compactionStrategy,
        Path memoryDirectory
    ) {}
}
```

---

## 14. Feasibility Analysis

### 14.1 Risk Assessment by Component

| Component | Risk Level | Key Risks | Mitigation |
|-----------|-----------|-----------|------------|
| Agent Loop (chelava-core) | **Low** | Straightforward state machine; well-understood pattern | Reference OpenClaw/Claude Code designs; extensive testing |
| LLM Client (chelava-llm) | **Low** | HTTP client + JSON; well-supported in Java | Use java.net.http (built-in); Jackson for JSON |
| Built-in Tools (chelava-tools) | **Low** | File I/O, process execution; Java strengths | Use NIO2, ProcessBuilder; comprehensive test suite |
| Virtual Thread Concurrency | **Low-Medium** | Pinning on synchronized blocks (fixed in Java 25); library compatibility | Use ReentrantLock where needed; test with -Djdk.tracePinnedThreads |
| Structured Concurrency | **Medium** | Still preview in Java 21-24; API may change | Abstract behind our own interface; update when stabilized |
| Context Compression | **Medium** | Token counting accuracy; summarization quality | Use tiktoken-java for counting; test compression ratios |
| GraalVM Native Image | **Medium** | Reflection config for JSON; binary size; build time | Build-time reflection registration; CI/CD build caching |
| Memory System | **Medium** | Embedding model integration; storage format | Start simple (file-based); add vector DB later |
| MCP Protocol | **Medium** | Protocol complexity; interop testing | Reference spec closely; test against OpenClaw MCP servers |
| Plugin System | **Medium-High** | Classloader isolation complexity; native image incompatibility | Hybrid approach (JVM subprocess for plugins); extensive testing |
| Terminal UI | **Medium** | Rich terminal rendering in Java; cross-platform | Use JLine 3 + Lanterna; test on macOS/Linux/Windows |
| Self-Learning | **High** | Pattern recognition accuracy; memory relevance decay | Start with simple heuristics; iterate on quality metrics |
| Sandbox (macOS/Linux) | **Medium-High** | Platform-specific isolation; security guarantees | macOS: sandbox-exec; Linux: seccomp/namespaces; Windows: Job objects |

### 14.2 Technology Risk Summary

**Java 21+ Features**:
- Virtual threads: **Production ready** (GA since Java 21)
- Structured concurrency: **Preview** (stabilizing in Java 25-26); low risk as API is simple
- Scoped values: **Preview** (stabilizing alongside structured concurrency); fallback to ThreadLocal
- Pattern matching for switch: **GA** since Java 21
- Records and sealed interfaces: **GA** since Java 17

**GraalVM**:
- Core agent loop: **Low risk** (no reflection in hot path)
- JSON serialization: **Medium risk** (needs reflection config, well-documented)
- Plugin loading: **High risk** (requires JVM fallback by design)

**Startup Time**:
- Native image: **50-150ms** (comparable to Node.js)
- JVM mode: **1-3 seconds** (acceptable for server mode, poor for CLI)
- CDS (Class Data Sharing): **300-800ms** (middle ground if native image has issues)

### 14.3 Competitive Advantages Over OpenClaw

| Advantage | Description |
|-----------|-------------|
| True parallelism | Virtual threads execute tools simultaneously; Node.js interleaves on one thread |
| Type safety | Compile-time guarantees for tool contracts, message formats, plugin APIs |
| Memory efficiency | JVM GC handles large context windows efficiently; no V8 heap limits |
| Startup speed | GraalVM native image matches Node.js; JVM mode can use CDS |
| Enterprise readiness | JVM ecosystem has battle-tested security, profiling, monitoring tools |
| Plugin ecosystem | Leverage existing Java/JVM libraries directly as tool implementations |
| Structured concurrency | Built-in lifecycle management for agent task trees; no orphaned promises |
| IDE integration | Natural fit for IntelliJ, Eclipse, VS Code via existing Java tooling |

### 14.4 Potential Disadvantages

| Disadvantage | Description | Mitigation |
|-------------|-------------|------------|
| Larger binary size | Native image ~60-120MB vs Node.js ~30-50MB | Acceptable; similar to Go binaries |
| Build complexity | GraalVM native image build is slow (2-5 min) | CI/CD caching; JVM mode for development |
| Ecosystem maturity | Node.js has more AI/LLM libraries | Java HTTP client is sufficient; build what's needed |
| Developer familiarity | AI/LLM community prefers Python/TypeScript | Target Java developers as primary audience |
| Structured concurrency preview | API not finalized until Java 25-26 | Abstract behind internal interfaces; easy to update |

---

## 15. Implementation Roadmap Recommendation

### Phase 1: Core Agent (Weeks 1-4)
- chelava-core: Agent loop, message protocol, turn management
- chelava-llm: Anthropic Claude provider (primary)
- chelava-tools: Read, Write, Edit, Bash, Glob, Grep (minimum viable toolset)
- chelava-cli: Basic REPL with streaming output
- chelava-security: Basic permission system (allow/prompt/deny)
- chelava-infra: Event bus (basic), graceful shutdown, retry policies

### Phase 2: Intelligence (Weeks 5-8)
- chelava-memory: Conversation compaction, project memory (CHELAVA.md)
- chelava-security: OS-level sandbox (macOS sandbox-exec, Linux seccomp)
- chelava-tools: Git tools, web search, notebook support
- chelava-llm: OpenAI, Gemini providers; circuit breaker; LLM failover
- chelava-core: Hook system (command type hooks)
- chelava-infra: Health monitor, scheduler (periodic maintenance tasks)
- Session persistence (JSONL transcripts, resume)

### Phase 3: Extensibility (Weeks 9-12)
- chelava-mcp: MCP client for external tool servers
- chelava-sdk: Plugin SPI, classloader isolation
- chelava-memory: Auto-memory, self-learning
- chelava-cli: Rich terminal UI, slash commands, basic skills system
- chelava-core: Subagent delegation (Explore, Plan, General-purpose)
- chelava-core: Hook system (prompt and agent type hooks)
- chelava-infra: Full event type hierarchy, scheduler with cron support
- chelava-infra: Message queue (point-to-point queues, pub/sub topics)
- Adaptive skills: Skill metrics tracking, skill outcome tracker, learning loop integration with auto-memory

### Phase 4: Multi-Agent and Distribution (Weeks 13-18)
- chelava-core: Agent teams (shared task list, message queue-based communication)
- chelava-infra: Full gateway with connection management, heartbeat system
- chelava-infra: Message queue (request-reply, dead letter queue, TTL expiration, JSONL persistence)
- Adaptive skills: Skill refinement engine (LLM-powered), auto-generated skills (pattern detection), version history with rollback
- GraalVM native image builds (macOS, Linux, Windows)
- chelava-server: HTTP/WebSocket for IDE integration
- chelava-mcp: MCP server mode (expose Chelava as MCP server)
- Plugin distribution and marketplace foundation
- Performance optimization, security hardening

---

## 16. Build System and Tooling

### 16.1 Build Tool: Gradle (Kotlin DSL)

```kotlin
// settings.gradle.kts
rootProject.name = "chelava"

include(
    "chelava-bom",
    "chelava-core",
    "chelava-llm",
    "chelava-tools",
    "chelava-memory",
    "chelava-security",
    "chelava-mcp",
    "chelava-cli",
    "chelava-sdk",
    "chelava-server",
    "chelava-test"
)
```

### 16.2 Key Dependencies

| Dependency | Purpose | Native Image Compatible |
|-----------|---------|------------------------|
| java.net.http (built-in) | HTTP client for LLM APIs | Yes |
| Jackson | JSON serialization | Yes (with config) |
| JLine 3 | Terminal input, completion | Yes |
| Lanterna | Rich terminal UI | Yes |
| Picocli | CLI argument parsing | Yes (annotation processor) |
| SLF4J + Logback | Logging | Yes |
| JUnit 5 + AssertJ | Testing | N/A |
| Testcontainers | Integration testing | N/A |

### 16.3 Java Version

**Target: Java 21 (LTS)** with forward-compatible design for Java 25+

- Virtual threads: GA in Java 21
- Records, sealed classes, pattern matching: GA in Java 21
- Structured concurrency: Preview in Java 21 (use with --enable-preview)
- Scoped values: Preview in Java 21 (use with --enable-preview)

When Java 25 LTS ships (September 2025), migrate to stabilized structured concurrency and scoped value APIs with minimal code changes (our abstractions insulate the rest of the codebase).

---

## 17. Gateway / Control Plane

### 17.1 Overview

The Gateway is Chelava's unified entry point and control plane for all client connections, inter-component communication, and lifecycle management. Inspired by OpenClaw's WebSocket gateway (`ws://127.0.0.1:18789`), Chelava implements a Java-native gateway using virtual threads and structured concurrency.

While OpenClaw's gateway is a WebSocket-based hub connecting CLI, WebChat, macOS app, and mobile nodes, Chelava's gateway is designed for a coding agent context: coordinating the CLI, IDE integrations, MCP servers, subagent instances, and agent team members.

### 17.2 Architecture

```
+---------------------------------------------------------------+
|                    Chelava Gateway (JVM Process)               |
|                                                                |
|  +-----------------+  +------------------+  +---------------+  |
|  | Connection Mgr  |  | Request Router   |  | Lifecycle Mgr |  |
|  | (virtual threads)|  | (sealed dispatch)|  | (shutdown)    |  |
|  +-----------------+  +------------------+  +---------------+  |
|         |                      |                    |          |
|  +-----------------+  +------------------+  +---------------+  |
|  | Health Monitor  |  | Event Bus        |  | Scheduler     |  |
|  | (heartbeat)     |  | (virtual threads)|  | (cron)        |  |
|  +-----------------+  +------------------+  +---------------+  |
|                                                                |
+---------------------------------------------------------------+
     |           |            |           |            |
     v           v            v           v            v
  CLI Client  IDE Plugin  MCP Server  Subagent    Teammate
  (stdin/out) (WebSocket) (stdio/SSE) (in-proc)  (in-proc/ext)
```

### 17.3 Core Abstractions

```java
// Gateway is the central control plane
public interface Gateway {
    /**
     * Start the gateway with the given configuration.
     * Initializes all subsystems: connection manager, event bus,
     * health monitor, scheduler, and lifecycle manager.
     */
    void start(GatewayConfig config);

    /**
     * Initiate graceful shutdown. Waits for in-flight requests
     * to complete, drains event queues, and stops all subsystems.
     */
    CompletableFuture<Void> shutdown(Duration timeout);

    /**
     * Get the health status of all managed components.
     */
    HealthReport health();

    // Component accessors
    ConnectionManager connections();
    EventBus eventBus();
    HealthMonitor healthMonitor();
    Scheduler scheduler();
}

// Gateway configuration
public record GatewayConfig(
    int serverPort,              // WebSocket/HTTP port for IDE clients (0 = disabled)
    Duration shutdownTimeout,    // Max time to wait for graceful shutdown
    Duration healthCheckInterval,// How often to check component health
    int maxConnections,          // Maximum concurrent client connections
    SchedulerConfig scheduler,   // Cron/scheduler configuration
    EventBusConfig eventBus      // Event bus configuration
) {
    public static GatewayConfig defaults() {
        return new GatewayConfig(
            0,                          // Server disabled by default (CLI mode)
            Duration.ofSeconds(30),
            Duration.ofSeconds(10),
            64,
            SchedulerConfig.defaults(),
            EventBusConfig.defaults()
        );
    }
}
```

### 17.4 Connection Manager

```java
// Manages all client connections to the gateway
public interface ConnectionManager {
    /**
     * Register a new client connection. Each connection runs
     * on its own virtual thread for independent lifecycle.
     */
    ConnectionHandle register(ClientConnection connection);

    /**
     * List all active connections with their health status.
     */
    List<ConnectionInfo> activeConnections();

    /**
     * Disconnect a specific client gracefully.
     */
    void disconnect(String connectionId, String reason);
}

// Client connection types
public sealed interface ClientConnection permits
    ClientConnection.StdioConnection,
    ClientConnection.WebSocketConnection,
    ClientConnection.InProcessConnection {

    record StdioConnection(
        InputStream input,
        OutputStream output,
        String clientId
    ) implements ClientConnection {}

    record WebSocketConnection(
        URI endpoint,
        String clientId,
        Map<String, String> headers
    ) implements ClientConnection {}

    record InProcessConnection(
        String clientId,
        BlockingQueue<GatewayMessage> inbox,
        BlockingQueue<GatewayMessage> outbox
    ) implements ClientConnection {}
}
```

### 17.5 Request Router

```java
// Routes incoming requests to the appropriate handler
public sealed interface GatewayRequest permits
    GatewayRequest.AgentRequest,
    GatewayRequest.ToolRequest,
    GatewayRequest.MCPRequest,
    GatewayRequest.HealthRequest,
    GatewayRequest.AdminRequest {

    String requestId();
    String sourceConnectionId();
    Instant timestamp();

    record AgentRequest(String requestId, String sourceConnectionId,
                        Instant timestamp, String prompt,
                        AgentConfig config) implements GatewayRequest {}

    record ToolRequest(String requestId, String sourceConnectionId,
                       Instant timestamp, String toolName,
                       JsonNode input) implements GatewayRequest {}

    record MCPRequest(String requestId, String sourceConnectionId,
                      Instant timestamp, String serverName,
                      JsonNode rpcMessage) implements GatewayRequest {}

    record HealthRequest(String requestId, String sourceConnectionId,
                         Instant timestamp) implements GatewayRequest {}

    record AdminRequest(String requestId, String sourceConnectionId,
                        Instant timestamp, AdminAction action) implements GatewayRequest {}
}

// Gateway processes requests using structured concurrency
public class GatewayRequestHandler {

    public GatewayResponse handle(GatewayRequest request) {
        // Each request handled in its own virtual thread scope
        return ScopedValue
            .where(AgentContext.REQUEST_ID, request.requestId())
            .call(() -> switch (request) {
                case AgentRequest r -> agentHandler.handle(r);
                case ToolRequest r -> toolHandler.handle(r);
                case MCPRequest r -> mcpHandler.handle(r);
                case HealthRequest r -> healthHandler.handle(r);
                case AdminRequest r -> adminHandler.handle(r);
            });
    }
}
```

---

## 18. Health Monitoring / Heartbeat System

### 18.1 Overview

The health monitoring system tracks the liveness and readiness of all Chelava components. OpenClaw uses heartbeat mechanisms to detect unresponsive agents and services. Chelava implements a Java-native health monitoring system using virtual threads for lightweight, non-blocking health checks.

### 18.2 Health Model

```java
// Component health status
public sealed interface HealthStatus permits
    HealthStatus.Healthy,
    HealthStatus.Degraded,
    HealthStatus.Unhealthy,
    HealthStatus.Unknown {

    Instant checkedAt();
    String componentName();

    record Healthy(String componentName, Instant checkedAt,
                   Map<String, Object> details) implements HealthStatus {}

    record Degraded(String componentName, Instant checkedAt,
                    String reason, Map<String, Object> details) implements HealthStatus {}

    record Unhealthy(String componentName, Instant checkedAt,
                     String reason, Throwable cause) implements HealthStatus {}

    record Unknown(String componentName, Instant checkedAt,
                   String reason) implements HealthStatus {}
}

// Aggregated health report
public record HealthReport(
    HealthStatus overall,
    Map<String, HealthStatus> components,
    Instant timestamp,
    Duration uptime
) {
    public boolean isHealthy() {
        return overall instanceof HealthStatus.Healthy;
    }
}
```

### 18.3 Health Check Interface

```java
// Every managed component implements this interface
public interface HealthCheckable {
    String componentName();

    /**
     * Perform a health check. Should complete within the timeout.
     * Runs on a virtual thread, so blocking I/O is fine.
     */
    HealthStatus checkHealth();

    /**
     * Timeout for health checks. Components that exceed this
     * are marked as Unhealthy.
     */
    default Duration healthCheckTimeout() {
        return Duration.ofSeconds(5);
    }
}
```

### 18.4 Health Monitor

```java
// Monitors all registered components on a schedule
public class HealthMonitor implements AutoCloseable {
    private final List<HealthCheckable> components;
    private final ScheduledExecutorService scheduler;
    private final EventBus eventBus;
    private volatile HealthReport lastReport;

    public HealthMonitor(List<HealthCheckable> components,
                         Duration checkInterval,
                         EventBus eventBus) {
        this.components = List.copyOf(components);
        this.eventBus = eventBus;
        // Use virtual thread executor for health checks
        this.scheduler = Executors.newSingleThreadScheduledExecutor(
            Thread.ofVirtual().name("health-monitor").factory()
        );
        scheduler.scheduleAtFixedRate(
            this::performHealthChecks,
            0, checkInterval.toMillis(), TimeUnit.MILLISECONDS
        );
    }

    private void performHealthChecks() {
        // Check all components in parallel using structured concurrency
        try (var scope = StructuredTaskScope.open()) {
            var checks = components.stream()
                .map(c -> scope.fork(() -> checkWithTimeout(c)))
                .toList();

            scope.join();

            var results = checks.stream()
                .collect(Collectors.toMap(
                    s -> s.get().componentName(),
                    StructuredTaskScope.Subtask::get
                ));

            var overall = computeOverall(results);
            lastReport = new HealthReport(
                overall, results, Instant.now(), getUptime()
            );

            // Publish health events for status transitions
            detectTransitions(lastReport).forEach(eventBus::publish);
        }
    }

    private HealthStatus checkWithTimeout(HealthCheckable component) {
        try {
            return CompletableFuture
                .supplyAsync(component::checkHealth,
                    Executors.newVirtualThreadPerTaskExecutor())
                .orTimeout(component.healthCheckTimeout().toMillis(),
                    TimeUnit.MILLISECONDS)
                .join();
        } catch (Exception e) {
            return new HealthStatus.Unhealthy(
                component.componentName(), Instant.now(),
                "Health check failed", e
            );
        }
    }
}
```

### 18.5 Heartbeat for Agent Teams

```java
// Heartbeat protocol for agent team members
public record Heartbeat(
    String agentId,
    String teamName,
    Instant timestamp,
    AgentState state,
    int currentTaskId,
    int completedTasks,
    TokenUsage sessionUsage
) {}

public enum AgentState {
    STARTING,     // Agent is initializing
    IDLE,         // Waiting for work
    WORKING,      // Processing a task
    BLOCKED,      // Waiting on a dependency
    SHUTTING_DOWN // Graceful shutdown in progress
}

// Heartbeat sender for teammates
public class HeartbeatSender implements AutoCloseable {
    private final ScheduledExecutorService scheduler;

    public HeartbeatSender(Gateway gateway, String agentId,
                           Duration interval) {
        this.scheduler = Executors.newSingleThreadScheduledExecutor(
            Thread.ofVirtual().name("heartbeat-" + agentId).factory()
        );
        scheduler.scheduleAtFixedRate(
            () -> gateway.eventBus().publish(
                new HeartbeatEvent(buildHeartbeat(agentId))
            ),
            0, interval.toMillis(), TimeUnit.MILLISECONDS
        );
    }
}
```

### 18.6 Built-in Health Checks

| Component | Health Check | Healthy Criteria |
|-----------|-------------|-----------------|
| LLM Client | API ping / model list | Response < 5s, valid model list |
| MCP Servers | JSON-RPC ping | Response < 3s per server |
| File System | Read/write test file | Writable project directory |
| Memory Store | Read test entry | Memory directory accessible |
| Sandbox | Test process execution | Sandbox binary available |
| Agent Team | Heartbeat freshness | Heartbeat < 2x interval |
| Context Window | Token count check | Usage < 90% capacity |

---

## 19. Scheduler / Cron System

### 19.1 Overview

The scheduler enables periodic task execution for maintenance, monitoring, and automation. OpenClaw uses cron-style scheduling for periodic tasks like memory consolidation and health checks. Chelava implements this using `ScheduledExecutorService` with virtual threads.

### 19.2 Architecture

```java
// Scheduler for periodic and one-shot tasks
public interface Scheduler extends AutoCloseable {

    /**
     * Schedule a periodic task with cron-like syntax.
     */
    ScheduledTaskHandle schedule(ScheduledTask task);

    /**
     * Schedule a one-shot delayed task.
     */
    ScheduledTaskHandle scheduleOnce(Runnable task, Duration delay);

    /**
     * List all scheduled tasks with their status.
     */
    List<ScheduledTaskInfo> listTasks();

    /**
     * Cancel a scheduled task.
     */
    boolean cancel(String taskId);
}

// Scheduled task definition
public record ScheduledTask(
    String id,
    String name,
    String description,
    Duration interval,          // Fixed-rate interval
    Duration initialDelay,      // Delay before first execution
    Runnable action,
    SchedulePolicy policy       // What to do on failure
) {}

public enum SchedulePolicy {
    CONTINUE_ON_FAILURE,    // Log error and continue scheduling
    STOP_ON_FAILURE,        // Cancel task on first failure
    RETRY_WITH_BACKOFF      // Retry with exponential backoff
}

// Task execution info
public record ScheduledTaskInfo(
    String id,
    String name,
    Instant lastExecution,
    Instant nextExecution,
    int executionCount,
    int failureCount,
    Duration averageDuration,
    TaskState state
) {}
```

### 19.3 Implementation

```java
public class VirtualThreadScheduler implements Scheduler {
    private final ScheduledExecutorService executor;
    private final Map<String, ScheduledFuture<?>> tasks = new ConcurrentHashMap<>();
    private final Map<String, ScheduledTaskInfo> taskInfo = new ConcurrentHashMap<>();
    private final EventBus eventBus;

    public VirtualThreadScheduler(EventBus eventBus) {
        this.eventBus = eventBus;
        // Single scheduler thread that dispatches to virtual threads
        this.executor = Executors.newScheduledThreadPool(1,
            Thread.ofVirtual().name("scheduler-", 0).factory()
        );
    }

    @Override
    public ScheduledTaskHandle schedule(ScheduledTask task) {
        var future = executor.scheduleAtFixedRate(
            () -> executeWithErrorHandling(task),
            task.initialDelay().toMillis(),
            task.interval().toMillis(),
            TimeUnit.MILLISECONDS
        );
        tasks.put(task.id(), future);
        return new ScheduledTaskHandle(task.id(), future);
    }

    private void executeWithErrorHandling(ScheduledTask task) {
        var start = Instant.now();
        try {
            // Execute on a virtual thread for non-blocking behavior
            Thread.startVirtualThread(task.action()).join();
            updateTaskInfo(task.id(), start, true);
            eventBus.publish(new ScheduledTaskCompletedEvent(task.id()));
        } catch (Exception e) {
            updateTaskInfo(task.id(), start, false);
            eventBus.publish(new ScheduledTaskFailedEvent(task.id(), e));

            switch (task.policy()) {
                case STOP_ON_FAILURE -> cancel(task.id());
                case RETRY_WITH_BACKOFF -> scheduleRetry(task);
                case CONTINUE_ON_FAILURE -> { /* Already logged */ }
            }
        }
    }
}
```

### 19.4 Built-in Scheduled Tasks

| Task | Default Interval | Purpose |
|------|-----------------|---------|
| Health check | 10 seconds | Monitor component health |
| Memory consolidation | 5 minutes | Merge and optimize auto-memory |
| Session cleanup | 1 hour | Remove expired session data |
| Context monitoring | 30 seconds | Track context window usage |
| MCP server ping | 30 seconds | Verify MCP server connectivity |
| Audit log rotation | 1 hour | Rotate and compress audit logs |
| Heartbeat (teams) | 5 seconds | Agent team member heartbeat |

---

## 20. Event Bus

### 20.1 Overview

The Event Bus enables loosely-coupled internal communication between Chelava components. OpenClaw uses event-driven patterns for connecting its gateway, agent runtime, and platform integrations. Chelava implements a type-safe, in-process event bus using `BlockingQueue` per subscriber with virtual threads for asynchronous delivery, and sealed interfaces for event types.

### 20.2 Event Type Hierarchy

```java
// All events in the system are typed via sealed interfaces
public sealed interface ChelavaEvent permits
    ChelavaEvent.AgentEvent,
    ChelavaEvent.ToolEvent,
    ChelavaEvent.HealthEvent,
    ChelavaEvent.SessionEvent,
    ChelavaEvent.TeamEvent,
    ChelavaEvent.SchedulerEvent,
    ChelavaEvent.SystemEvent {

    String eventId();
    Instant timestamp();
    String source();

    // Agent lifecycle events
    sealed interface AgentEvent extends ChelavaEvent permits
        TurnStarted, TurnCompleted, AgentError,
        SubagentSpawned, SubagentCompleted {}

    record TurnStarted(String eventId, Instant timestamp, String source,
                       String sessionId, int turnNumber) implements AgentEvent {}

    record TurnCompleted(String eventId, Instant timestamp, String source,
                         String sessionId, int turnNumber, TokenUsage usage,
                         StopReason reason) implements AgentEvent {}

    record AgentError(String eventId, Instant timestamp, String source,
                      String sessionId, String error,
                      Throwable cause) implements AgentEvent {}

    record SubagentSpawned(String eventId, Instant timestamp, String source,
                           String subagentId, String subagentType) implements AgentEvent {}

    record SubagentCompleted(String eventId, Instant timestamp, String source,
                             String subagentId, TokenUsage usage) implements AgentEvent {}

    // Tool execution events
    sealed interface ToolEvent extends ChelavaEvent permits
        ToolExecutionStarted, ToolExecutionCompleted,
        ToolExecutionFailed, ToolPermissionRequested {}

    record ToolExecutionStarted(String eventId, Instant timestamp, String source,
                                String toolName, String toolUseId) implements ToolEvent {}

    record ToolExecutionCompleted(String eventId, Instant timestamp, String source,
                                  String toolName, String toolUseId,
                                  Duration duration) implements ToolEvent {}

    record ToolExecutionFailed(String eventId, Instant timestamp, String source,
                               String toolName, String toolUseId,
                               String error) implements ToolEvent {}

    record ToolPermissionRequested(String eventId, Instant timestamp, String source,
                                   String toolName, PermissionDecision decision) implements ToolEvent {}

    // Health events
    sealed interface HealthEvent extends ChelavaEvent permits
        HealthCheckCompleted, ComponentDown, ComponentRecovered,
        HeartbeatReceived, HeartbeatMissed {}

    record HealthCheckCompleted(String eventId, Instant timestamp, String source,
                                HealthReport report) implements HealthEvent {}

    record ComponentDown(String eventId, Instant timestamp, String source,
                         String componentName, String reason) implements HealthEvent {}

    record ComponentRecovered(String eventId, Instant timestamp, String source,
                              String componentName) implements HealthEvent {}

    record HeartbeatReceived(String eventId, Instant timestamp, String source,
                             Heartbeat heartbeat) implements HealthEvent {}

    record HeartbeatMissed(String eventId, Instant timestamp, String source,
                           String agentId, Duration missedDuration) implements HealthEvent {}

    // Session events
    sealed interface SessionEvent extends ChelavaEvent permits
        SessionStarted, SessionEnded, ContextCompacted {}

    record SessionStarted(String eventId, Instant timestamp, String source,
                          String sessionId, String model) implements SessionEvent {}

    record SessionEnded(String eventId, Instant timestamp, String source,
                        String sessionId, String reason) implements SessionEvent {}

    record ContextCompacted(String eventId, Instant timestamp, String source,
                            String sessionId, int removedTokens) implements SessionEvent {}

    // Team events
    sealed interface TeamEvent extends ChelavaEvent permits
        TeammateJoined, TeammateLeft, TaskAssigned,
        TaskCompleted, MessageSent {}

    record TeammateJoined(String eventId, Instant timestamp, String source,
                          String teamName, String teammateId) implements TeamEvent {}

    record TeammateLeft(String eventId, Instant timestamp, String source,
                        String teamName, String teammateId) implements TeamEvent {}

    record TaskAssigned(String eventId, Instant timestamp, String source,
                        String taskId, String assigneeId) implements TeamEvent {}

    record TaskCompleted(String eventId, Instant timestamp, String source,
                         String taskId, String completedBy) implements TeamEvent {}

    record MessageSent(String eventId, Instant timestamp, String source,
                       String fromId, String toId) implements TeamEvent {}

    // Scheduler events
    sealed interface SchedulerEvent extends ChelavaEvent permits
        ScheduledTaskCompletedEvent, ScheduledTaskFailedEvent {}

    record ScheduledTaskCompletedEvent(String eventId, Instant timestamp,
                                       String source,
                                       String taskId) implements SchedulerEvent {}

    record ScheduledTaskFailedEvent(String eventId, Instant timestamp,
                                     String source, String taskId,
                                     Throwable error) implements SchedulerEvent {}

    // System events
    sealed interface SystemEvent extends ChelavaEvent permits
        GatewayStarted, GatewayShuttingDown, ConfigReloaded {}

    record GatewayStarted(String eventId, Instant timestamp,
                          String source) implements SystemEvent {}

    record GatewayShuttingDown(String eventId, Instant timestamp,
                               String source, String reason) implements SystemEvent {}

    record ConfigReloaded(String eventId, Instant timestamp,
                          String source) implements SystemEvent {}
}
```

### 20.3 Event Bus Interface

```java
// Type-safe event bus using BlockingQueue per subscriber + virtual threads
public interface EventBus extends AutoCloseable {

    /**
     * Publish an event to all subscribers of that event type.
     * Non-blocking: uses offer() to enqueue to each subscriber's queue.
     * If a subscriber's queue is full, the event is dropped for that
     * subscriber (natural backpressure via queue capacity).
     */
    void publish(ChelavaEvent event);

    /**
     * Subscribe to events of a specific type.
     * Creates a BlockingQueue for this subscriber and starts a virtual
     * thread that loops on queue.take() to process events.
     * Returns a subscription handle for unsubscribing.
     */
    <E extends ChelavaEvent> Subscription subscribe(
        Class<E> eventType,
        EventHandler<E> handler
    );

    /**
     * Subscribe with a custom queue capacity for backpressure control.
     * Larger queues absorb bursts; smaller queues drop events sooner.
     */
    <E extends ChelavaEvent> Subscription subscribe(
        Class<E> eventType,
        EventHandler<E> handler,
        int queueCapacity
    );

    /**
     * Subscribe to all events (for logging, metrics, etc.).
     */
    Subscription subscribeAll(EventHandler<ChelavaEvent> handler);
}

@FunctionalInterface
public interface EventHandler<E extends ChelavaEvent> {
    void handle(E event);
}

public interface Subscription extends AutoCloseable {
    void unsubscribe();
    boolean isActive();
}
```

### 20.4 Implementation

```java
public class InProcessEventBus implements EventBus {
    private static final int DEFAULT_QUEUE_CAPACITY = 256;

    // Each subscriber has its own queue and virtual thread
    private final List<SubscriberEntry<?>> subscribers = new CopyOnWriteArrayList<>();
    private final List<SubscriberEntry<ChelavaEvent>> globalSubscribers =
        new CopyOnWriteArrayList<>();

    // A subscriber entry: queue + virtual thread + handler
    private record SubscriberEntry<E>(
        Class<E> eventType,
        BlockingQueue<E> queue,
        EventHandler<E> handler,
        Thread consumerThread,
        AtomicBoolean active
    ) {}

    @Override
    public void publish(ChelavaEvent event) {
        // Offer to type-specific subscriber queues (non-blocking)
        for (var subscriber : subscribers) {
            if (subscriber.eventType().isInstance(event)) {
                // offer() returns false if queue is full = event dropped
                // This provides natural backpressure via queue capacity
                @SuppressWarnings("unchecked")
                var queue = (BlockingQueue<ChelavaEvent>) subscriber.queue();
                if (!queue.offer(event)) {
                    logger.trace("Event dropped for subscriber (queue full): {}",
                        event.getClass().getSimpleName());
                }
            }
        }

        // Offer to global subscriber queues
        for (var subscriber : globalSubscribers) {
            if (!subscriber.queue().offer(event)) {
                logger.trace("Event dropped for global subscriber (queue full): {}",
                    event.getClass().getSimpleName());
            }
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public <E extends ChelavaEvent> Subscription subscribe(
            Class<E> eventType, EventHandler<E> handler) {
        return subscribe(eventType, handler, DEFAULT_QUEUE_CAPACITY);
    }

    @Override
    public <E extends ChelavaEvent> Subscription subscribe(
            Class<E> eventType, EventHandler<E> handler, int queueCapacity) {
        var queue = new ArrayBlockingQueue<E>(queueCapacity);
        var active = new AtomicBoolean(true);

        // Start a virtual thread that loops on queue.take()
        var consumerThread = Thread.startVirtualThread(() -> {
            while (active.get()) {
                try {
                    E event = queue.take(); // blocks cheaply on virtual thread
                    handler.handle(event);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    // Log but don't stop the subscriber
                    logger.warn("Event handler failed for {}: {}",
                        eventType.getSimpleName(), e.getMessage());
                }
            }
        });

        var entry = new SubscriberEntry<>(eventType, queue, handler,
            consumerThread, active);
        subscribers.add(entry);

        return new DefaultSubscription(() -> {
            active.set(false);
            consumerThread.interrupt();
            subscribers.remove(entry);
        });
    }

    @Override
    public Subscription subscribeAll(EventHandler<ChelavaEvent> handler) {
        var queue = new ArrayBlockingQueue<ChelavaEvent>(DEFAULT_QUEUE_CAPACITY);
        var active = new AtomicBoolean(true);

        var consumerThread = Thread.startVirtualThread(() -> {
            while (active.get()) {
                try {
                    var event = queue.take();
                    handler.handle(event);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    logger.warn("Global event handler failed: {}",
                        e.getMessage());
                }
            }
        });

        var entry = new SubscriberEntry<>(ChelavaEvent.class, queue, handler,
            consumerThread, active);
        globalSubscribers.add(entry);

        return new DefaultSubscription(() -> {
            active.set(false);
            consumerThread.interrupt();
            globalSubscribers.remove(entry);
        });
    }

    @Override
    public void close() {
        // Shut down all subscriber threads
        for (var subscriber : subscribers) {
            subscriber.active().set(false);
            subscriber.consumerThread().interrupt();
        }
        for (var subscriber : globalSubscribers) {
            subscriber.active().set(false);
            subscriber.consumerThread().interrupt();
        }
    }
}
```

### 20.5 Event Bus vs Direct Method Calls

| Aspect | Event Bus | Direct Calls |
|--------|-----------|-------------|
| Coupling | Loose - publisher doesn't know subscribers | Tight - caller knows callee |
| Use when | Cross-cutting concerns, monitoring, hooks | Core agent loop, tool execution |
| Error isolation | Handler failures don't propagate | Exceptions propagate to caller |
| Performance | Slight overhead from dispatch | Zero overhead |
| Testability | Easy to mock subscribers | Easy to mock individual dependencies |

**Design rule**: Use the event bus for *observability* (logging, metrics, health updates) and *cross-cutting concerns* (hooks, audit). Use direct method calls for the *core agent loop* and *tool execution pipeline*.

---

## 21. Graceful Shutdown

### 21.1 Overview

Graceful shutdown ensures that Chelava cleanly terminates all operations, persists state, and releases resources when stopping. This is critical for preventing data loss (unsaved memory, incomplete transcripts) and resource leaks (orphaned MCP server processes, dangling virtual threads).

### 21.2 Shutdown Phases

```
Signal Received (SIGTERM, SIGINT, or /exit command)
    |
    v
Phase 1: Stop accepting new work
    |-- Stop agent loop (finish current turn)
    |-- Reject new client connections
    |-- Pause scheduler (no new task executions)
    |
    v
Phase 2: Drain in-flight work
    |-- Wait for current tool executions to complete (with timeout)
    |-- Wait for LLM streaming responses to finish
    |-- Flush event bus queues
    |
    v
Phase 3: Persist state
    |-- Save conversation transcript
    |-- Flush auto-memory changes
    |-- Write audit log entries
    |
    v
Phase 4: Release resources
    |-- Disconnect MCP servers
    |-- Shutdown agent team members
    |-- Close HTTP client connections
    |-- Stop scheduler
    |-- Close event bus
    |
    v
Phase 5: Final cleanup
    |-- Fire SessionEnd hooks
    |-- Write final audit entry
    |-- Exit process
```

### 21.3 Implementation

```java
public class GracefulShutdownManager {
    private final List<ShutdownParticipant> participants;
    private final EventBus eventBus;
    private final Duration timeout;
    private final AtomicBoolean shuttingDown = new AtomicBoolean(false);

    /**
     * Register JVM shutdown hook and signal handlers.
     */
    public void install() {
        Runtime.getRuntime().addShutdownHook(
            Thread.ofVirtual().unstarted(() -> {
                try {
                    shutdown().get(timeout.toMillis(), TimeUnit.MILLISECONDS);
                } catch (Exception e) {
                    logger.error("Shutdown did not complete cleanly", e);
                }
            })
        );

        // Also handle Ctrl+C gracefully
        Signal.handle(new Signal("INT"), signal -> {
            if (!shuttingDown.get()) {
                shutdown();
            }
        });
    }

    /**
     * Execute graceful shutdown in order.
     * Each participant is given the remaining time budget.
     */
    public CompletableFuture<Void> shutdown() {
        if (!shuttingDown.compareAndSet(false, true)) {
            return CompletableFuture.completedFuture(null); // Already shutting down
        }

        eventBus.publish(new GatewayShuttingDown(
            UUID.randomUUID().toString(), Instant.now(),
            "shutdown-manager", "graceful shutdown initiated"
        ));

        return CompletableFuture.runAsync(() -> {
            var deadline = Instant.now().plus(timeout);

            for (var participant : participants) {
                var remaining = Duration.between(Instant.now(), deadline);
                if (remaining.isNegative()) {
                    logger.warn("Shutdown timeout reached; forcing remaining participants");
                    break;
                }
                try {
                    participant.onShutdown(remaining);
                } catch (Exception e) {
                    logger.warn("Shutdown participant {} failed: {}",
                        participant.name(), e.getMessage());
                }
            }
        }, Executors.newVirtualThreadPerTaskExecutor());
    }
}

// Components that need cleanup implement this interface
public interface ShutdownParticipant {
    String name();
    int priority();  // Lower values shut down first
    void onShutdown(Duration remainingTime) throws Exception;
}
```

### 21.4 Shutdown Order

| Priority | Component | Action |
|----------|-----------|--------|
| 0 | Agent Loop | Stop accepting new turns, finish current |
| 1 | Connection Manager | Reject new connections, drain existing |
| 2 | Scheduler | Cancel pending tasks, wait for running |
| 3 | Tool Executor | Wait for in-flight executions |
| 4 | Session Manager | Persist transcript and state |
| 5 | Memory System | Flush auto-memory to disk |
| 6 | Audit Logger | Flush remaining audit events |
| 7 | MCP Client | Disconnect all MCP servers |
| 8 | Agent Team | Send shutdown to teammates |
| 9 | Event Bus | Drain and close |
| 10 | HTTP Server | Stop listening, close connections |

---

## 22. Error Recovery / Circuit Breakers

### 22.1 Overview

Error recovery mechanisms ensure Chelava degrades gracefully when components fail. Inspired by patterns from resilient distributed systems, Chelava uses circuit breakers for external service calls (LLM APIs, MCP servers) and recovery strategies for internal failures.

### 22.2 Circuit Breaker

```java
// Circuit breaker for external service calls
public class CircuitBreaker<T> {
    private final String name;
    private final int failureThreshold;      // Failures before opening
    private final Duration resetTimeout;      // Time before half-open
    private final AtomicInteger failureCount = new AtomicInteger(0);
    private volatile CircuitState state = CircuitState.CLOSED;
    private volatile Instant lastFailure = Instant.MIN;
    private final EventBus eventBus;

    public enum CircuitState {
        CLOSED,     // Normal operation
        OPEN,       // Failures exceeded threshold; reject calls
        HALF_OPEN   // Testing if service has recovered
    }

    /**
     * Execute an operation through the circuit breaker.
     * Throws CircuitOpenException if the circuit is open.
     */
    public T execute(Callable<T> operation) throws Exception {
        if (state == CircuitState.OPEN) {
            if (shouldAttemptReset()) {
                state = CircuitState.HALF_OPEN;
            } else {
                throw new CircuitOpenException(name, lastFailure);
            }
        }

        try {
            T result = operation.call();
            onSuccess();
            return result;
        } catch (Exception e) {
            onFailure(e);
            throw e;
        }
    }

    private void onSuccess() {
        failureCount.set(0);
        if (state == CircuitState.HALF_OPEN) {
            state = CircuitState.CLOSED;
            eventBus.publish(new ComponentRecovered(
                UUID.randomUUID().toString(), Instant.now(),
                "circuit-breaker", name
            ));
        }
    }

    private void onFailure(Exception e) {
        lastFailure = Instant.now();
        if (failureCount.incrementAndGet() >= failureThreshold) {
            state = CircuitState.OPEN;
            eventBus.publish(new ComponentDown(
                UUID.randomUUID().toString(), Instant.now(),
                "circuit-breaker", name,
                "Circuit opened after " + failureThreshold + " failures"
            ));
        }
    }

    private boolean shouldAttemptReset() {
        return Duration.between(lastFailure, Instant.now())
            .compareTo(resetTimeout) > 0;
    }
}
```

### 22.3 LLM Provider Failover

```java
// Automatic failover between LLM providers
public class FailoverLLMClient implements LLMClient {
    private final List<LLMClientWithCircuitBreaker> providers;

    public record LLMClientWithCircuitBreaker(
        LLMClient client,
        CircuitBreaker<AssistantMessage> circuitBreaker
    ) {}

    @Override
    public AssistantMessage complete(LLMRequest request) {
        Exception lastException = null;

        for (var provider : providers) {
            try {
                return provider.circuitBreaker().execute(
                    () -> provider.client().complete(request)
                );
            } catch (CircuitOpenException e) {
                logger.info("Skipping {} - circuit open",
                    provider.client().providerId());
                continue;
            } catch (Exception e) {
                lastException = e;
                logger.warn("Provider {} failed, trying next: {}",
                    provider.client().providerId(), e.getMessage());
            }
        }

        throw new AllProvidersFailedException(
            "All LLM providers exhausted", lastException
        );
    }
}
```

### 22.4 Recovery Strategies

```java
// Configurable retry with exponential backoff
public class RetryPolicy<T> {
    private final int maxRetries;
    private final Duration initialDelay;
    private final double backoffMultiplier;
    private final Set<Class<? extends Exception>> retryableExceptions;

    public T executeWithRetry(Callable<T> operation) throws Exception {
        int attempt = 0;
        Duration delay = initialDelay;

        while (true) {
            try {
                return operation.call();
            } catch (Exception e) {
                attempt++;
                if (attempt >= maxRetries ||
                    !retryableExceptions.contains(e.getClass())) {
                    throw e;
                }
                Thread.sleep(delay.toMillis());
                delay = Duration.ofMillis(
                    (long) (delay.toMillis() * backoffMultiplier)
                );
            }
        }
    }
}

// Pre-configured retry policies
public class RetryPolicies {
    public static <T> RetryPolicy<T> llmApi() {
        return new RetryPolicy<>(3, Duration.ofSeconds(1), 2.0,
            Set.of(RateLimitException.class, TimeoutException.class,
                   ServerErrorException.class));
    }

    public static <T> RetryPolicy<T> mcpServer() {
        return new RetryPolicy<>(2, Duration.ofMillis(500), 1.5,
            Set.of(ConnectionException.class, TimeoutException.class));
    }

    public static <T> RetryPolicy<T> fileOperation() {
        return new RetryPolicy<>(2, Duration.ofMillis(100), 2.0,
            Set.of(IOException.class));
    }
}
```

### 22.5 Error Recovery Matrix

| Component | Failure Mode | Recovery Strategy |
|-----------|-------------|-------------------|
| LLM API | Rate limit (429) | Exponential backoff, then failover |
| LLM API | Server error (5xx) | Retry 3x, then failover |
| LLM API | Network timeout | Retry 2x with increased timeout |
| MCP Server | Connection lost | Reconnect with backoff, circuit breaker |
| MCP Server | Invalid response | Log, return error to agent |
| File System | Permission denied | Return error to agent (no retry) |
| File System | Disk full | Return error, suggest cleanup |
| Sandbox | Process timeout | Kill process, return timeout error |
| Sandbox | Escape attempt | Block, log audit event, alert user |
| Memory Store | Corrupted file | HMAC check fails, discard and rebuild |
| Agent Team | Teammate unresponsive | Heartbeat timeout, reassign tasks |
| Context Window | Exceeded capacity | Auto-compact, then retry turn |

---

## 23. Module Updates

### 23.1 New Module: chelava-infra

The infrastructure components (gateway, health monitoring, scheduler, event bus, circuit breakers, graceful shutdown) are grouped into a new `chelava-infra` module:

```
chelava/
  chelava-bom/              # Bill of Materials (dependency management)
  chelava-core/             # Agent loop, tool system, LLM client abstractions
  chelava-infra/            # Gateway, event bus, health, scheduler, shutdown  [NEW]
  chelava-llm/              # LLM provider implementations (Anthropic, OpenAI, etc.)
  chelava-tools/            # Built-in tools (file, bash, search, web, git)
  chelava-memory/           # Context management, auto-memory, self-learning
  chelava-security/         # Permission system, sandbox, audit logging
  chelava-mcp/              # MCP protocol client/server implementation
  chelava-cli/              # Terminal UI, CLI parsing, REPL
  chelava-sdk/              # Extension API for plugins and custom tools
  chelava-server/           # HTTP/WebSocket server for IDE integrations
  chelava-test/             # Test utilities and fixtures
```

### 23.2 Updated Module Dependency Graph

```
chelava-cli ──> chelava-core ──> chelava-sdk (API contracts)
     |               |                  ^
     |               |                  |
     v               v                  |
chelava-server  chelava-llm        chelava-tools
     |               |                  |
     v               v                  v
chelava-mcp    chelava-memory     chelava-security
                     |
                     v
              chelava-infra (gateway, events, health, scheduler)
                     ^
                     |
              chelava-core (depends on infra for lifecycle)
```

### 23.3 chelava-infra Module Contents

| Package | Components | Responsibility |
|---------|-----------|---------------|
| `com.chelava.infra.gateway` | Gateway, ConnectionManager, RequestRouter | Central control plane |
| `com.chelava.infra.health` | HealthMonitor, HealthCheckable, Heartbeat | Component health tracking |
| `com.chelava.infra.scheduler` | Scheduler, ScheduledTask, SchedulePolicy | Periodic task execution |
| `com.chelava.infra.events` | EventBus, ChelavaEvent, EventHandler | Internal event communication |
| `com.chelava.infra.resilience` | CircuitBreaker, RetryPolicy, FailoverLLMClient | Error recovery |
| `com.chelava.infra.lifecycle` | GracefulShutdownManager, ShutdownParticipant | Clean process termination |
| `com.chelava.infra.messaging` | MessageQueue, AgentMessage, MessageHandler, QueueConfig, TopicConfig | Inter-agent message queue |

### 23.4 Design Principles

1. **Zero external dependencies**: All infrastructure components use only `java.base` and `java.net.http` - no external libraries needed (including the message queue - no Kafka/RabbitMQ required)
2. **Virtual thread native**: Every component is designed for virtual threads; no thread pools with fixed sizes
3. **Virtual threads over reactive streams**: All streaming, event delivery, and message consumption uses simple blocking patterns (BlockingQueue + virtual threads) instead of Flow API / reactive streams. This yields simpler, more debuggable code with natural backpressure via queue capacity. Flow API is available for external library interop but is not used internally
4. **Sealed type safety**: All event types, health statuses, circuit states, message types, and skill states use sealed interfaces for compile-time exhaustiveness
5. **GraalVM compatible**: No reflection, no dynamic proxies, no class generation - all components compile cleanly to native image
6. **Opt-in complexity**: CLI mode uses minimal infrastructure (no HTTP server, no scheduler for team heartbeats); server mode enables full infrastructure
7. **Consistent patterns**: Event bus, message queue, and streaming all use the same BlockingQueue-per-consumer + virtual thread pattern for uniformity and developer familiarity

---

## 24. Conclusion

Chelava's architecture leverages Java's modern capabilities to deliver a coding agent that is:

1. **Faster at parallel tasks**: Virtual threads enable true concurrent tool execution, parallel memory retrieval, and in-process agent teams
2. **Safer by design**: Type system, structured concurrency, permission system, OS-level sandbox, and hook-based quality gates prevent entire classes of bugs
3. **More extensible**: SPI-based plugin system with classloader isolation, MCP client/server, adaptive skills system, and hook lifecycle events support a rich ecosystem
4. **Enterprise-ready**: JVM profiling, monitoring, security tools, and typed APIs are unmatched
5. **Competitive on startup**: GraalVM native image eliminates Java's traditional startup penalty
6. **Self-improving**: Adaptive skills system with learning loops, auto-generated skills, skill refinement engine, and auto-memory integration enables continuous improvement across sessions
7. **Collaborative**: Agent teams with decoupled message queue communication (point-to-point, pub/sub, request-reply, dead letter) and shared task lists enable complex multi-agent workflows
8. **Resilient**: Gateway control plane, circuit breakers, health monitoring, and graceful shutdown ensure production-grade reliability
9. **Observable**: Type-safe event bus with virtual thread-based subscribers enables comprehensive monitoring and debugging

The architecture is designed to be built incrementally across 4 phases (18 weeks), with a working agent in Phase 1 and progressive enhancement through Phase 4. The 12 modules (including the new `chelava-infra`) have clear boundaries and well-defined interfaces, enabling parallel development and independent testing. The infrastructure layer (gateway, event bus, message queue, health monitoring, scheduler, circuit breakers, graceful shutdown) provides the operational backbone that distinguishes a production system from a prototype.

The design deliberately mirrors proven patterns from Claude Code and OpenClaw while exploiting Java's unique strengths in concurrency, type safety, and enterprise tooling. Where OpenClaw uses a WebSocket gateway with TypeScript event emitters, Chelava uses virtual thread-powered connection management with BlockingQueue-based event delivery. Where Claude Code and OpenClaw use direct Mailbox-style messaging for agent teams, Chelava uses an in-process message queue with topic-based pub/sub, dead letter queues, and request-reply patterns for truly decoupled inter-agent communication. Where OpenClaw uses a static skill registry (ClawHub), Chelava implements an adaptive skills system that learns from usage, auto-generates skills from detected patterns, and refines skills based on failure analysis. Every infrastructure component is designed for zero external dependencies, GraalVM native image compatibility, and sealed type safety.
