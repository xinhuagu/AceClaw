# Continuous Learning Baseline Plan

## Purpose
This document defines the v1 measurement framework for AceClaw continuous learning. The goal is to establish reproducible baseline metrics before enabling aggressive autonomous promotion and rollback policies.

This plan aligns with PRD success metrics and the continuous-learning implementation track (`#52`-`#65`).

## Scope
In scope for baseline v1:
- Metric dictionary with formulas and thresholds.
- Collection points and output schema.
- A local baseline collection command.
- A reporting template for before/after comparisons.

Out of scope for baseline v1:
- Real-time dashboarding.
- Provider-specific optimization.
- Automatic threshold tuning.

## Data Sources
- Agent turn outcomes (`StreamingAgentHandler`, session lifecycle).
- Tool runtime statistics (`ToolMetricsCollector`).
- Learning events (candidate updates, promotions, demotions, rollbacks).
- Validation/replay gate outputs.

## Metric Dictionary (v1)
The table below defines the canonical baseline metrics.

| Metric | Definition | Formula | Collection Point | Window | Initial Target |
|---|---|---|---|---|---|
| `task_success_rate` | Tasks completed successfully | `successful_tasks / completed_tasks` | Session end summary | 7 days | `>= 0.85` |
| `first_try_success_rate` | Tasks that succeed without retries | `first_try_successes / completed_tasks` | Turn + retry tracking | 7 days | `>= 0.70` |
| `retry_count_per_task` | Mean retries per completed task | `total_retries / completed_tasks` | Turn history | 7 days | `<= 0.60` |
| `tool_execution_success_rate` | Successful tool calls ratio | `tool_success_count / total_tool_invocations` | `ToolMetricsCollector` | 7 days | `>= 0.95` |
| `permission_block_rate` | Permission-related blocked actions ratio | `permission_blocks / action_attempts` | Permission manager events | 7 days | `<= 0.05` |
| `timeout_rate` | Timeout ratio across tools/subagents/tasks | `timeouts / action_attempts` | Runtime failure events | 7 days | `<= 0.03` |
| `learning_hit_rate` | Promoted learning entries that are relevant to turns | `relevant_learning_hits / learning_injections` | Prompt injection logs | 7 days | `>= 0.40` |
| `promotion_precision` | Promoted candidates that stay healthy post-release | `healthy_promotions / total_promotions` | Candidate lifecycle + outcome feedback | 14 days | `>= 0.80` |
| `false_learning_rate` | Promoted candidates later demoted/rejected as harmful | `harmful_promotions / total_promotions` | Candidate lifecycle engine | 14 days | `<= 0.10` |
| `time_to_promote_p50_hours` | Median time from first seen to promoted | `p50(promoted_at - first_seen_at)` | Candidate store timestamps | 14 days | `<= 72h` |
| `regression_rate_after_learning` | Regressions introduced by learning rollouts | `regressed_rollouts / total_rollouts` | Replay + runtime enforcement | 14 days | `<= 0.10` |
| `auto_rollback_rate` | Rollouts that required automatic rollback | `auto_rollbacks / total_rollouts` | Auto-release enforcement events | 14 days | `<= 0.10` |

## Collection Output Schema
Baseline output should be written to:
- `.aceclaw/metrics/continuous-learning/baseline-<UTC timestamp>.json`

Top-level JSON sections:
- `metadata`: repo, commit, branch, collection timestamp, command version.
- `collection`: whether tests were run, status, and optional failure details.
- `metrics`: dictionary keyed by canonical metric names.
- `notes`: freeform comments and instrumentation gaps.

## Baseline Command
Use the repository script:

```bash
./scripts/collect-continuous-learning-baseline.sh --run-tests
```

Useful flags:

```bash
# Custom output path
./scripts/collect-continuous-learning-baseline.sh \
  --output /tmp/aceclaw-baseline.json

# Add measured values as overrides (repeatable)
./scripts/collect-continuous-learning-baseline.sh \
  --metric task_success_rate=0.88 \
  --metric tool_execution_success_rate=0.97
```

## Baseline Workflow
1. Ensure clean workspace and stable test environment.
2. Run the baseline collection script with `--run-tests`.
3. If measured values are available from logs/replay, pass them through `--metric` overrides.
4. Fill `docs/reports/continuous-learning-baseline-template.md` with generated output.
5. Attach the report to the relevant issue/PR.

## Known Gaps (v1)
- Some metrics are not fully instrumented yet and will be emitted as `pending_instrumentation`.
- Replay-comparison fields are defined but require issue `#63` implementation.
- Automated promotion/rollback quality metrics require issues `#60`-`#62`.

## Versioning
- Document version: `v1.0`
- Last updated: `2026-02-21`
