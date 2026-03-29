# Design Philosophy

Why AceClaw is built in Java, from scratch, without any AI framework.

## What Is an Agent Harness?

An agent harness is not the AI itself. It is the runtime that manages the AI.

Think of it like a pit crew for a race car. The driver (the LLM) does the driving. The crew handles timing, safety, and recovery.

A harness runs the ReAct loop, executes tools, controls permissions, watches budgets, detects bad loops, and learns from past runs. Projects like Claude Code, LangChain, and OpenClaw all solve parts of this problem. AceClaw chose Java because it makes this kind of runtime easier to build in a clean and reliable way.

## Big Model vs. Big Harness

This debate traces back to Rich Sutton's [The Bitter Lesson](http://incompleteideas.net/IncIdeas/BitterLesson.html) (2019): methods that scale with compute usually beat hand-built tricks. Make the model bigger. Train it longer. Give it more data. Let the model do the work.

For a while, many pushed that idea to the extreme. If models keep getting better, maybe you only need the model. The harness — the code around it — looks like a temporary crutch.

But products like Cursor, Manus, Claude Code, and OpenClaw showed something important. With today's models, you still need a real runtime around them. Someone still has to manage the ReAct loop, run tools safely, handle permissions, detect loops, and learn from past sessions. The model does not do all of that by itself.

Two things are now clear:

1. **The harness is real.** It is not a temporary hack.
2. **The harness will change shape over time.** As models improve, they will absorb more reasoning and some orchestration work, but the need for infrastructure does not go away. Some parts — context management, safety, execution control — may become even more important. The harness does not disappear; it becomes more focused on controlling the system rather than compensating for the model.

## Why No AI Framework

This is the biggest lesson behind AceClaw: **an agent harness is not just an AI app. It is infrastructure.**

At the model layer, only a thin adapter is needed — a streaming HTTP client and a JSON parser for tool calls. Everything else — permissions, safety, tool execution, learning, context management — is application logic.

**Frameworks do not remove this complexity. They just move it around.**

They can be useful for prototyping, but many of the problems they try to solve — prompt flow, memory, orchestration — are exactly where models are improving the fastest. This ties back to The Bitter Lesson: larger context windows do not replace real memory, but they do make some framework abstractions less critical.

In this kind of system, the core problem is not missing abstractions, but making behavior predictable.

## Why Java

Beyond being a thin, no-framework harness, this layer is where the system stays stable while everything else changes. Agent harnesses are concurrent, long-running, and safety-critical systems. Viewed through those constraints, Java starts to make a lot of sense.

### Failure Boundaries: Controlling How the System Fails

Many scripting environments rely heavily on conventions and runtime checks. Permissions and safety decisions are often represented as loosely typed data, so missing cases may only surface when a specific execution path is hit.

That trade-off works well for short-lived workflows, but in a long-running agent it creates a fragile failure mode.

AceClaw models core concepts as closed sets, where adding a new variant forces the compiler to revisit every dependent path. **This is a form of security left shift: a class of errors moves from runtime surprises into compile-time checks.** Java's sealed types and exhaustive pattern matching make this possible:

```java
// Adding a new variant here is a compile error
// until every switch statement is updated
sealed interface PermissionDecision
    permits Approved, Denied, NeedsUserApproval {}

sealed interface ContentBlock
    permits Text, ToolUse, ToolResult {}
```

### Concurrency: Keeping the Agent Responsive

In real long-running tasks, an agent often needs to read several files, inspect logs, probe services, or gather evidence from different places before it can reason well. If the harness does all of that one step at a time, the agent spends too much time waiting.

AceClaw runs independent tool calls in parallel within a single ReAct turn, where failures stop the rest cleanly, and the whole runtime stays understandable.

Java's **virtual threads** make parallel I/O straightforward without turning the codebase into async control flow. More importantly, **virtual threads allow writing concurrent systems without turning concurrency into a control-flow problem.**

In many scripting environments, the same level of concurrency relies on async/await patterns or event loops, where concurrency management becomes part of the application logic and makes the system harder to reason about as it grows.

### Runtime Modeling: Making the System Explicit

An agent harness depends on closed runtime models: content blocks, stream events, tool results, safety verdicts, permission decisions. These are not random objects — they are core parts of the runtime.

In many scripting environments, these concepts are often represented as loosely structured data (dicts, JSON payloads, ad-hoc objects), where the system relies on conventions and runtime checks to stay consistent.

Java sealed interfaces model those states explicitly as closed sets and make missing cases easier to catch. When the model evolves, missing cases surface as compilation failures instead of becoming latent runtime bugs.

## Infrastructure Mindset: Keeping the Core Predictable

What ties all of this together is **control**.

An agent harness is not just coordinating API calls. It is managing execution, safety, state, and learning over time. Small inconsistencies do not fail fast — they build up.

That is why AceClaw treats itself as infrastructure.

In this kind of system, abstraction is not always helpful. If the system hides how it runs, how state changes, or how it fails, it becomes much harder to reason about when something goes wrong.

**The goal is not less complexity. It is control over where that complexity lives.**

The core concepts are visible. The execution flow is clear. Failures are controlled by design, not patched at runtime. **The goal is not just to make the system work. It is to make its behavior predictable.**

## The Honest Trade-offs

Java is not perfect for this job.

- **Ecosystem**: The AI ecosystem defaults to Python. Embedding models, vector stores, and new research tools are usually Python-first. In Java, these are typically accessed via REST APIs.
- **Verbosity**: A short Python script can become much longer in Java. But many of those extra lines buy type safety, error handling, and thread safety.
- **Startup time**: JVM startup is slower than Node.js, which matters for short-lived scripts. But for a daemon that runs for hours, that cost is negligible.

These are real trade-offs. For the kind of system AceClaw is — long-running, concurrent, safety-critical — they are worth it.

## Key Takeaway

The Bitter Lesson says: bet on compute, not hand-built tricks. Products like Cursor, Claude Code, and Manus show that the harness is real. The direction of the field suggests the harness will get thinner as models improve.

If the harness gets thinner, every remaining line matters more. That is a strong argument for a language with strong types, strong concurrency, and good runtime discipline.

If the harness gets thinner, heavy frameworks become more risky. The more abstraction you add, the more future cleanup you may need.

**The thinner the harness gets, the more the language matters — and the less you need a heavy framework.**

## References

- Rich Sutton, [The Bitter Lesson](http://incompleteideas.net/IncIdeas/BitterLesson.html)
- Anthropic, [Effective harnesses for long-running agents](https://www.anthropic.com/engineering/effective-harnesses-for-long-running-agents)
- OpenAI, [Harness engineering: leveraging Codex in an agent-first world](https://openai.com/index/harness-engineering/)
