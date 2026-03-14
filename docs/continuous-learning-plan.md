# Continuous Learning Baseline Plan

## Purpose
This document defines the measurement framework for AceClaw continuous learning. The goal is to establish reproducible effectiveness metrics for the self-learning system that now exists on `main`, especially the downstream promotion, validation, and rollout path for learned behavior.

This plan aligns with the current self-learning implementation:

- phase 1: behavior extraction, session retrospectives, historical indexing, patterns, trends, adaptive skills
- phase 2: explainability, validation semantics, observability, noise control, runtime governance, rebuild recovery, human review

Operational controls and rollback procedures are documented in `docs/continuous-learning-operations.md`.

## Where This Fits

```mermaid
flowchart LR
  a["Behavior signals"] --> b["Learning stores"]
  b --> c["Candidate / draft / runtime governance"]
  c --> d["Validation and release"]
  d --> e["Quality gates and effectiveness metrics"]
```

This document focuses on the last step: how to decide whether the learning system is actually improving behavior.

## Scope
In scope:
- Metric dictionary with formulas and thresholds.
- Collection points and output schema.
- A local baseline collection command.
- A reporting template for before/after comparisons.

Out of scope:
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
| `tool_error_rate` | Tool-call error ratio | `tool_error_count / total_tool_invocations` | `ToolMetricsCollector` | 7 days | `<= 0.05` |
| `permission_block_rate` | Permission-related blocked actions ratio | `permission_blocks / action_attempts` | Permission manager events | 7 days | `<= 0.05` |
| `timeout_rate` | Timeout ratio across tools/subagents/tasks | `timeouts / action_attempts` | Runtime failure events | 7 days | `<= 0.03` |
| `learning_hit_rate` | Promoted learning entries that are relevant to turns | `relevant_learning_hits / learning_injections` | Prompt injection logs | 7 days | `>= 0.40` |
| `promotion_precision` | Promoted candidates that stay healthy post-release | `healthy_promotions / total_promotions` | Candidate lifecycle + outcome feedback | 14 days | `>= 0.80` |
| `false_learning_rate` | Promoted candidates later demoted/rejected as harmful | `harmful_promotions / total_promotions` | Candidate lifecycle engine | 14 days | `<= 0.10` |
| `time_to_promote_p50_hours` | Median time from first seen to promoted | `p50(promoted_at - first_seen_at)` | Candidate store timestamps | 14 days | `<= 72h` |
| `regression_rate_after_learning` | Regressions introduced by learning rollouts | `regressed_rollouts / total_rollouts` | Replay + runtime enforcement | 14 days | `<= 0.10` |
| `auto_rollback_rate` | Rollouts that required automatic rollback | `auto_rollbacks / total_rollouts` | Auto-release enforcement events | 14 days | `<= 0.10` |

## Offline Replay Metrics (A/B)
The baseline requires direct `learning=off` vs `learning=on` comparison on the same replay dataset.

| Metric | Definition | Formula | Target |
|---|---|---|---|
| `replay_success_rate_delta` | Success-rate impact of learning on replay | `success_rate_on - success_rate_off` | `>= 0.00` |
| `replay_token_delta` | Token cost impact of learning | `avg_tokens_on - avg_tokens_off` | `<= 200` (budget) |
| `replay_latency_delta_ms` | Latency impact of learning | `avg_latency_ms_on - avg_latency_ms_off` | `<= 500` (budget) |
| `replay_failure_distribution_delta` | Change in failure-type distribution | `L1(failure_dist_on, failure_dist_off)` | `<= 0.15` |
| `token_estimation_error_ratio_max` | Worst calibrated estimator/provider deviation | `max(|estimated*calibration-provider|/provider)` | `<= 0.25` |

Notes:
- `failure_dist_*` is computed over normalized failure buckets (permission, timeout, tool-error, other).
- Replay cases include dual-track token fields (`estimated_tokens`, `provider_tokens`=`usage.outputTokens`) and derived `estimation_error_ratio`.
- `token_estimation_error_ratio_max` must be `measured` and pass threshold for pre-merge acceptance.
- Replay metric aggregation is implemented via `scripts/generate-replay-report.sh`.
- Replay case execution is available via `:aceclaw-cli:runReplayCases` / `./gradlew generateReplayCases`.
- Replay suite schema + category coverage validation is enforced via `validateReplaySuite`.
- CI-scale replay dataset growth and ownership governance (larger suite lifecycle) is still an ongoing integration track.

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

## CI Gate Workflow
1. Generate replay report from A/B replay cases:
   - `./gradlew generateReplayCases -PreplayPromptsInput=... -PreplayCasesOutput=...`
   - `./gradlew generateReplayReport -PreplayCasesInput=... -PreplayReport=...`
2. Run hard pre-merge gate:
   - `./gradlew preMergeCheck -PreplayGateStrict=true -PreplayReport=...`
3. Gate fails if replay report is missing, metrics are non-measured, source manifest verification fails, or thresholds are violated.

## Known Gaps
- Some metrics are not fully instrumented yet and will be emitted as `pending_instrumentation`.
- Replay case/reports are automated; remaining work is stronger dataset governance at scale.
- Outcome enforcement closure (`#62`) is now implemented for candidate-level outcome writeback,
  deterministic time-gating (`Clock`), lifecycle cleanup/decay, and concurrency hardening.
- Automated release-controller and policy-pack layers still require issues `#60`-`#61`.

## Versioning
- Document version: `v1.0`
- Last updated: `2026-02-24`
