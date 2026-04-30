# Plan → Execute → Replan

<sub>Supported by research: <a href="https://arxiv.org/abs/2502.01390">Plan-Then-Execute (CHI 2025)</a></sub>

Most AI coding agents ([Claude Code](https://docs.anthropic.com/en/docs/claude-code/overview), [OpenClaw](https://github.com/openclaw), [Codex CLI](https://github.com/openai/codex)) rely on a flat **ReAct loop** — the model reasons and acts one step at a time. Effective for short tasks; offers no explicit plan visibility and no structured failure recovery for long-running work.

AceClaw layers an **explicit planning pipeline on top of ReAct**. Each step is still executed by the same ReAct loop (reason → act → observe), which remains the best mechanism for single-step tool use. The difference: AceClaw wraps those steps in a higher-order plan that provides direction, budget control, and structured recovery.

```
Task → Complexity Estimator → Plan Generation (LLM) → Sequential Execution → Inline Replan
                                     │                        │                      │
                                     ▼                        ▼                      ▼
                              Structured JSON plan     Per-step iteration     On failure: executor
                              streamed to user         budgets                retries with fallback
                                                                              prompt or skips step
```

## Components

| Component | What it does |
|-----------|-------------|
| `ComplexityEstimator` | Scores task complexity; only triggers planning above a configurable threshold |
| `LLMTaskPlanner` | Generates a structured JSON plan with ordered, named steps |
| `SequentialPlanExecutor` | Executes steps one by one with per-step iteration budgets, fallback support, and cancellation between steps |

## Why this matters for long tasks

- **Visibility** — The user sees "Step 3/7: Refactor authentication module" in real time, not a stream of opaque tool calls.
- **Structured recovery** — When step N fails, the executor retries with a fallback prompt that includes the failure reason and remaining plan context.
- **Budget control** — Each step has its own iteration budget, preventing any single step from consuming the entire session.

## Planned (not yet implemented)

Crash-safe plan checkpointing to disk, cross-session plan resumption, and wall-clock per-step budgets.
