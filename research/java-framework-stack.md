# AceClaw - Java Framework Stack & Implementation Plan

## Executive Summary

This document defines the Java technology stack for AceClaw, a Java-native AI coding agent inspired by OpenClaw. The stack is optimized for CLI tool performance (sub-50ms startup), minimal memory footprint (<100MB idle), and excellent developer experience. The recommended stack is **Picocli + JLine3 + Virtual Threads + GraalVM Native Image** with minimal framework overhead, using Gradle as the build system.

---

## 1. GraalVM Native Image

### Overview

GraalVM Native Image compiles Java applications ahead-of-time (AOT) into standalone executables. This eliminates JVM startup overhead, making Java competitive with Go and Rust for CLI tools.

### Performance Benchmarks

| Metric | JVM (OpenJDK 21) | GraalVM Native Image |
|--------|-------------------|----------------------|
| Startup time | ~1-4 seconds | **~10-50ms** |
| Memory (idle CLI) | ~150-300MB | **~30-80MB** |
| Binary size | JRE + JAR | **~50-80MB single binary** |
| Peak throughput | Higher (JIT optimized) | Lower (AOT only) |

For CLI applications specifically, benchmarks show native executables starting in ~45ms vs ~8 seconds for equivalent JAR applications. The `-O2` optimization level in GraalVM 21+ provides 10-15% faster compile times and is tuned for common Java workloads.

### Key Limitations & Workarounds

| Limitation | Impact | Workaround |
|------------|--------|------------|
| Reflection | High - many Java libs use reflection | Use `@RegisterForReflection`, agent-based metadata collection, or compile-time annotation processors |
| Dynamic class loading | Medium - plugin systems affected | Use ServiceLoader with metadata, or compile plugins ahead of time |
| JNI | Low - most code is pure Java | Register JNI methods in config; prefer pure Java alternatives |
| Serialization | Medium - Jackson needs config | Use `reflect-config.json` or framework annotations |
| Incomplete classpath | High - all reachable code must be known | Use closed-world assumption; agent tracing for discovery |

### Build Pipeline

```
Source Code
    |
    v
Gradle Build (compile + test on JVM)
    |
    v
GraalVM Native Image Agent (optional: collect metadata)
    |
    v
Native Image Compilation (AOT)
    |
    v
Platform-specific Binary (macOS/Linux/Windows)
    |
    v
Distribution (GitHub Releases, Homebrew, SDKMAN!)
```

### Reflection Configuration Strategy

1. **Picocli annotation processor**: Generates `reflect-config.json` at compile time for all `@Command`, `@Option`, `@Parameters` annotations
2. **Jackson module**: Use `jackson-module-afterburner` or register types with `@RegisterForReflection`
3. **GraalVM tracing agent**: Run integration tests on JVM with `-agentlib:native-image-agent` to discover remaining reflection calls
4. **Manual configuration**: `META-INF/native-image/` resource configs for edge cases

---

## 2. Framework Evaluation

### Option A: Quarkus (with Picocli Extension)

**Pros:**
- First-class picocli integration via `quarkus-picocli` extension
- Command Mode designed for CLI applications
- Excellent GraalVM native image support with build-time optimizations
- CDI for dependency injection
- Live coding / dev mode for rapid development
- Strong ecosystem (HTTP clients, JSON, config management)
- Native startup: ~49ms

**Cons:**
- Significant framework overhead for a CLI tool (~15-25MB added to binary)
- CDI container initialization adds latency even in command mode
- Opinionated - harder to customize low-level behavior
- Extension ecosystem designed for server apps, not CLI tools
- Build time with native image: 3-5 minutes

### Option B: Micronaut

**Pros:**
- Compile-time dependency injection (no runtime reflection for DI)
- Minimal reflection by design - excellent GraalVM compatibility
- Lightweight compared to Quarkus for CLI use cases
- Native startup: ~50ms
- Good AOT processing

**Cons:**
- CLI support less mature than Quarkus picocli integration
- Compile-time DI generates code that increases binary size
- Less community momentum for CLI-specific use cases
- Annotation processing can slow compilation

### Option C: Plain Java + Picocli (Recommended)

**Pros:**
- **Smallest binary size** (~30-50MB native image)
- **Fastest startup** (~10-30ms native, no framework init overhead)
- **Full control** over initialization, lifecycle, and resource management
- **Simplest GraalVM configuration** - fewer libraries = fewer reflection issues
- **Minimal dependencies** - reduces supply chain risk
- Java 21+ virtual threads work without framework wrappers
- Direct use of `java.net.http.HttpClient`, `java.util.ServiceLoader`
- Fastest build times (both JVM and native)

**Cons:**
- No built-in dependency injection (use manual wiring or a micro-DI library)
- Must implement configuration management manually
- No live reload during development
- More boilerplate for cross-cutting concerns

### Recommendation: **Option C - Plain Java + Picocli**

**Justification:**

For a CLI tool like AceClaw, framework overhead is the primary enemy. Every millisecond of startup matters, and every megabyte of memory counts. The key reasons:

1. **Startup performance**: Plain Java + Picocli achieves 10-30ms startup natively, beating Quarkus (49ms) and Micronaut (50ms). For an interactive CLI tool invoked hundreds of times per session, this difference is significant.

2. **Binary size**: A lean native image (~30-50MB) is critical for distribution. Framework overhead adds 15-25MB unnecessarily.

3. **GraalVM simplicity**: Fewer dependencies = fewer reflection registration headaches. Picocli's annotation processor handles its own GraalVM config. Plain Java stdlib has excellent native image support.

4. **Virtual threads don't need a framework**: Java 21+ virtual threads are a language/runtime feature, not a framework feature. They work perfectly without Quarkus or Micronaut wrappers.

5. **Maintainability**: Fewer moving parts, fewer version conflicts, fewer breaking changes on upgrades. The Java platform itself is the "framework."

For dependency injection, we use **manual constructor injection** with a simple `AppContext` class. This is sufficient for a CLI application and avoids all DI framework overhead. If the codebase grows complex enough to warrant DI, consider adding [Dagger 2](https://dagger.dev/) (compile-time DI, zero runtime overhead, GraalVM-friendly).

---

## 3. Concurrency Model

### Java 21+ Virtual Threads

Virtual threads (JEP 444, final in Java 21) are the primary concurrency mechanism for AceClaw.

**Use Cases in AceClaw:**
- Parallel tool execution (file search, code analysis, test running)
- Concurrent API calls to LLM providers
- Background file watching and indexing
- Parallel dependency resolution

**Example: Parallel Tool Execution**

```java
try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
    var fileFuture = executor.submit(() -> searchFiles(pattern));
    var grepFuture = executor.submit(() -> grepContent(query));
    var gitFuture = executor.submit(() -> getGitStatus());

    var files = fileFuture.get();
    var matches = grepFuture.get();
    var status = gitFuture.get();
}
```

### Structured Concurrency (JEP 453/505)

**Status**: Still in preview as of JDK 25 (JEP 505, Fifth Preview). API has been refined - `StructuredTaskScope` now uses static factory methods (`StructuredTaskScope.open()`) instead of public constructors.

**Recommendation**: Use structured concurrency with `--enable-preview` flag. The API is stable enough for our use case, and the benefits for agent task management are significant:

- Automatic cancellation of subtasks when parent scope exits
- Clean error propagation from child tasks
- Observable task hierarchies for debugging

**Example: Agent Task Management**

```java
try (var scope = StructuredTaskScope.open()) {
    var codeAnalysis = scope.fork(() -> analyzeCode(files));
    var contextGathering = scope.fork(() -> gatherContext(query));

    scope.join(); // Wait for all subtasks

    return buildPrompt(codeAnalysis.get(), contextGathering.get());
}
```

### Scoped Values (JEP 446/487)

Scoped values replace ThreadLocal for context propagation across virtual threads. Use for:

- Current conversation/session context
- API credentials and configuration
- Request tracing and logging context

```java
private static final ScopedValue<ConversationContext> CONTEXT = ScopedValue.newInstance();

ScopedValue.where(CONTEXT, conversationCtx).run(() -> {
    // All virtual threads in this scope can access CONTEXT
    agent.processQuery(query);
});
```

### Concurrency Architecture

```
Main Thread (CLI input loop)
    |
    +-- Virtual Thread: LLM API call (streaming)
    |       |
    |       +-- Virtual Thread: Token processing
    |       +-- Virtual Thread: UI rendering
    |
    +-- Virtual Thread: Tool execution scope
    |       |
    |       +-- VT: File search
    |       +-- VT: Code grep
    |       +-- VT: Git operations
    |
    +-- Virtual Thread: Background indexing
```

---

## 4. Reactive Streams for LLM Streaming

### Options Evaluation

| Library | Pros | Cons | GraalVM Support |
|---------|------|------|-----------------|
| **java.util.concurrent.Flow** | Zero dependencies, JDK built-in | Minimal API, no operators | Excellent |
| **Project Reactor** | Rich operators, backpressure | Heavy dependency (~3MB), Spring-oriented | Good (needs config) |
| **SmallRye Mutiny** | Simple API (Uni/Multi), event-driven | Quarkus-oriented, smaller community | Excellent (Quarkus) |
| **Virtual Threads + BlockingQueue** | Simplest, no reactive overhead | Not "reactive", manual backpressure | Excellent |

### Recommendation: **java.util.concurrent.Flow + Virtual Threads**

For LLM streaming responses, we do NOT need the full power of reactive streams. The pattern is simple:
1. Open HTTP connection to LLM API (SSE stream)
2. Read tokens as they arrive
3. Render tokens to terminal in real-time
4. Accumulate full response for tool extraction

This is a single-producer, single-consumer pattern that virtual threads handle elegantly:

```java
// Producer: reads SSE stream in a virtual thread
// Consumer: renders to terminal on the main thread
var tokenQueue = new LinkedBlockingQueue<StreamToken>(256);

Thread.startVirtualThread(() -> {
    try (var stream = llmClient.streamCompletion(request)) {
        stream.forEach(token -> tokenQueue.put(new StreamToken.Data(token)));
    }
    tokenQueue.put(StreamToken.END);
});

// Consumer loop (main thread or dedicated virtual thread)
while (true) {
    var token = tokenQueue.take();
    if (token == StreamToken.END) break;
    terminalRenderer.renderToken(token);
}
```

If richer stream processing is needed later (e.g., multi-model orchestration, complex pipelines), the `java.util.concurrent.Flow` API provides Publisher/Subscriber interfaces that third-party libraries can implement. This keeps the door open without adding dependencies now.

---

## 5. Key Libraries

### CLI Framework: Picocli 4.x + JLine3

**Picocli** (picocli.info):
- Annotation-based command/option parsing
- Automatic help generation, color support
- Built-in GraalVM annotation processor (`picocli-codegen`)
- Subcommand support for multi-command CLI
- Type conversion, validation, completion scripts

```java
@Command(name = "aceclaw", mixinStandardHelpOptions = true,
         version = "0.1.0",
         description = "AI-powered coding agent for Java developers")
public class AceClawCommand implements Callable<Integer> {

    @Option(names = {"-m", "--model"}, description = "LLM model to use")
    private String model = "claude-sonnet-4-5-20250929";

    @Option(names = {"--no-stream"}, description = "Disable streaming output")
    private boolean noStream;

    @Parameters(description = "Initial prompt or command")
    private String[] prompt;

    @Override
    public Integer call() {
        // Main entry point
    }
}
```

**JLine3** (jline.org):
- Terminal detection and raw mode handling
- Line editing (Emacs/Vi modes)
- History with search (Ctrl+R)
- Tab completion
- Syntax highlighting
- Multi-line input
- ConsoleUI components (prompts, checkboxes, selections)
- Cross-platform (Windows, macOS, Linux)

### HTTP Client: java.net.http.HttpClient

**Rationale**: Built into JDK, zero dependencies, good GraalVM support, supports HTTP/2, async operations.

```java
var client = HttpClient.newBuilder()
    .connectTimeout(Duration.ofSeconds(10))
    .build();

// SSE streaming for LLM
var request = HttpRequest.newBuilder()
    .uri(URI.create(apiEndpoint))
    .header("Authorization", "Bearer " + apiKey)
    .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
    .build();

// Streaming response with virtual threads
client.sendAsync(request, HttpResponse.BodyHandlers.ofLines())
    .thenAccept(response -> response.body().forEach(this::processSSELine));
```

**Known limitation**: `jdk.httpclient.allowRestrictedHeaders` system property is not respected in native images. Workaround: set headers at build time or use alternative header names.

**Alternative (if needed)**: Vert.x HTTP client offers better native image support and non-blocking I/O, but adds significant dependency weight. Reserve as fallback.

### JSON: Jackson 2.x

**Rationale**: Industry standard, best performance, extensive type support, GraalVM-compatible with configuration.

**GraalVM Configuration Strategy**:
1. Use `jackson-module-parameter-names` to avoid reflection for constructor parameters
2. Register model classes with `@RegisterForReflection` or in `reflect-config.json`
3. Use Jackson's `@JsonCreator` / `@JsonProperty` annotations (processed by picocli-codegen compatible tools)
4. Run GraalVM tracing agent during integration tests to capture remaining reflection

```java
var mapper = new ObjectMapper()
    .registerModule(new ParameterNamesModule())
    .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
```

**Alternative considered**: Gson is simpler but slower, and Jackson has better streaming support (critical for parsing large LLM responses). The jsoniter library offers zero-reflection mode but has a smaller community.

### Terminal UI: JLine3 + Custom ANSI Renderer

**Architecture:**
```
User Input -> JLine3 LineReader -> Command Parser -> Agent
Agent Response -> Markdown Parser -> ANSI Renderer -> Terminal Output
```

**Markdown Terminal Rendering:**

Neither commonmark-java nor flexmark-java provides built-in ANSI terminal rendering. We will build a custom renderer using **commonmark-java** (lighter weight, sufficient for our needs) with ANSI escape codes:

| Markdown Element | Terminal Rendering |
|------------------|--------------------|
| `# Header` | Bold + color |
| `**bold**` | ANSI bold |
| `*italic*` | ANSI italic (if supported) or dim |
| `` `code` `` | Reversed or colored background |
| ```` ```code block``` ```` | Syntax highlighted (if possible), boxed |
| `> quote` | Indented + colored bar |
| `- list` | Bullet with indent |
| `[link](url)` | Underlined + colored |

Library: **commonmark-java** 0.23+ for parsing, custom `NodeRenderer` for ANSI output.

### Testing

| Library | Purpose |
|---------|---------|
| **JUnit 5** | Test framework |
| **Mockito 5** | Mocking (GraalVM-compatible with inline mock maker) |
| **Testcontainers** | Integration tests (e.g., git repos, file systems) |
| **WireMock** | HTTP API mocking (LLM API simulation) |
| **JLine3 test terminal** | Terminal interaction testing |
| **ArchUnit** | Architecture rule enforcement |

---

## 6. Build System: Gradle (Kotlin DSL)

### build.gradle.kts

```kotlin
plugins {
    java
    application
    id("org.graalvm.buildtools.native") version "0.11.1"
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

application {
    mainClass.set("com.aceclaw.Main")
}

dependencies {
    // CLI
    implementation("info.picocli:picocli:4.7.6")
    annotationProcessor("info.picocli:picocli-codegen:4.7.6")

    // Terminal
    implementation("org.jline:jline:3.27.1")

    // JSON
    implementation("com.fasterxml.jackson.core:jackson-databind:2.18.2")
    implementation("com.fasterxml.jackson.module:jackson-module-parameter-names:2.18.2")

    // Markdown parsing
    implementation("org.commonmark:commonmark:0.23.0")

    // Testing
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testImplementation("org.mockito:mockito-core:5.14.2")
    testImplementation("org.wiremock:wiremock:3.10.0")
    testImplementation("org.testcontainers:testcontainers:1.20.4")
    testImplementation("org.testcontainers:junit-jupiter:1.20.4")
    testImplementation("com.tngtech.archunit:archunit-junit5:1.3.0")
}

graalvmNative {
    binaries {
        named("main") {
            imageName.set("aceclaw")
            mainClass.set("com.aceclaw.Main")
            buildArgs.addAll(
                "-O2",
                "--no-fallback",
                "--enable-preview",
                "--initialize-at-build-time",
                "-H:+ReportExceptionStackTraces"
            )
            javaLauncher.set(javaToolchains.launcherFor {
                languageVersion.set(JavaLanguageVersion.of(21))
                vendor.set(JvmVendorSpec.GRAALVM_CE)
            })
        }
    }
    agent {
        defaultMode.set("standard")
        builtinCallerFilter.set(true)
        builtinHeuristicFilter.set(true)
        enableExperimentalPredefinedClasses.set(false)
        enableExperimentalUnsafeAllocationTracing.set(false)
        trackReflectionMetadata.set(true)
    }
}

tasks.withType<JavaCompile> {
    options.compilerArgs.addAll(listOf(
        "-Aproject=${project.group}/${project.name}",
        "--enable-preview"
    ))
}

tasks.test {
    useJUnitPlatform()
    jvmArgs("--enable-preview")
}
```

### Build Commands

```bash
# Development (JVM mode, fast iteration)
./gradlew run --args="'your prompt here'"

# Run tests
./gradlew test

# Build native image
./gradlew nativeCompile

# Run native image tests
./gradlew nativeTest

# Collect GraalVM metadata via agent
./gradlew -Pagent run --args="'test prompt'"
./gradlew metadataCopy --task=run --dir=src/main/resources/META-INF/native-image

# Distribution
./gradlew nativeCompile
# Binary at: build/native/nativeCompile/aceclaw
```

---

## 7. Performance Targets

| Metric | Target | Strategy |
|--------|--------|----------|
| **Startup time** | < 50ms (native) | GraalVM native image, minimal initialization, lazy loading |
| **Memory (idle)** | < 100MB | No framework overhead, stream processing, off-heap where possible |
| **Memory (active)** | < 300MB | Streaming LLM responses, bounded caches, virtual thread efficiency |
| **First token latency** | < 200ms (excl. network) | Pre-warmed HTTP connections, minimal prompt construction |
| **Binary size** | < 80MB | Minimal dependencies, native image optimizations |
| **Build time (JVM)** | < 10s | Gradle build cache, incremental compilation |
| **Build time (native)** | < 3min | GraalVM build cache, profile-guided optimization |

### Measurement Plan

```java
// Startup timing (embedded in Main.java)
public static void main(String[] args) {
    long startNanos = ProcessHandle.current().info().startInstant()
        .map(i -> Duration.between(i, Instant.now()).toNanos())
        .orElse(0L);
    // ... application init ...
    if (System.getenv("ACECLAW_DEBUG") != null) {
        System.err.printf("Startup: %.1fms%n", startNanos / 1_000_000.0);
    }
}
```

---

## 8. Project Module Structure

```
aceclaw/
  build.gradle.kts
  settings.gradle.kts
  src/
    main/
      java/
        com/aceclaw/
          Main.java                    # Entry point
          cli/                         # Picocli commands
            AceClawCommand.java
            InitCommand.java
            ConfigCommand.java
          agent/                       # AI agent core
            Agent.java
            ConversationManager.java
            ToolExecutor.java
          llm/                         # LLM provider abstraction
            LlmClient.java
            AnthropicClient.java
            OpenAiClient.java
            StreamingResponseHandler.java
          tool/                        # Tool implementations
            Tool.java
            FileTool.java
            GrepTool.java
            BashTool.java
            GitTool.java
          context/                     # Context management
            ContextBuilder.java
            FileIndexer.java
            ProjectAnalyzer.java
          terminal/                    # Terminal UI
            TerminalRenderer.java
            MarkdownRenderer.java
            SpinnerWidget.java
            DiffRenderer.java
          config/                      # Configuration
            AceClawConfig.java
            ConfigLoader.java
          security/                    # Security sandbox
            PermissionManager.java
            SandboxExecutor.java
      resources/
        META-INF/
          native-image/
            reflect-config.json
            resource-config.json
    test/
      java/
        com/aceclaw/
          ...                          # Mirror of main structure
      resources/
        fixtures/                      # Test fixtures
```

---

## 9. Dependency Summary

| Dependency | Version | Size | Purpose | GraalVM Ready |
|-----------|---------|------|---------|---------------|
| picocli | 4.7.6 | ~400KB | CLI parsing | Yes (annotation processor) |
| jline | 3.27.1 | ~1.5MB | Terminal handling | Yes |
| jackson-databind | 2.18.2 | ~1.8MB | JSON serialization | Yes (with config) |
| commonmark | 0.23.0 | ~200KB | Markdown parsing | Yes |
| **Total runtime** | | **~4MB** | | |

Zero-framework approach keeps total dependency weight under 5MB, resulting in a lean native image.

---

## 10. Risk Assessment

| Risk | Probability | Impact | Mitigation |
|------|------------|--------|------------|
| GraalVM reflection issues at runtime | Medium | High | Comprehensive agent tracing in CI, integration test coverage |
| Preview API changes (Structured Concurrency) | Medium | Medium | Abstract behind internal API, easy to update |
| JLine3 native image issues | Low | Medium | JLine is pure Java, well-tested with GraalVM |
| Jackson serialization edge cases | Medium | Low | Extensive test coverage, consider adding jsoniter as fallback |
| Binary size exceeds target | Low | Low | Dependency audit, unused code elimination with native-image |
| Startup exceeds 50ms target | Low | High | Profiling, lazy initialization, reduce initialized classes |

---

## References

- [GraalVM Native Image Documentation](https://www.graalvm.org/latest/reference-manual/native-image/)
- [GraalVM Native Image: Java's Answer to Rust's Startup Speed](https://www.javacodegeeks.com/2026/02/graalvm-native-image-javas-answer-to-rusts-startup-speed.html)
- [Picocli on GraalVM](https://picocli.info/picocli-on-graalvm.html)
- [Build Great Native CLI Apps in Java with GraalVM and Picocli](https://www.infoq.com/articles/java-native-cli-graalvm-picocli/)
- [JLine3 Documentation](https://jline.org/)
- [JEP 505: Structured Concurrency (Fifth Preview)](https://openjdk.org/jeps/505)
- [Gradle Plugin for GraalVM Native Image](https://graalvm.github.io/native-build-tools/latest/gradle-plugin.html)
- [Spring Boot vs Quarkus vs Micronaut: The Ultimate 2026 Showdown](https://www.javacodegeeks.com/2025/12/spring-boot-vs-quarkus-vs-micronaut-the-ultimate-2026-showdown.html)
- [GraalVM Libraries and Frameworks Compatibility](https://www.graalvm.org/native-image/libraries-and-frameworks/)
- [Building a CLI with Quarkus, Kotlin and GraalVM](https://maarten.mulders.it/2025/07/building-a-cli-with-quarkus-kotlin-and-graalvm/)
- [commonmark-java](https://github.com/commonmark/commonmark-java)
