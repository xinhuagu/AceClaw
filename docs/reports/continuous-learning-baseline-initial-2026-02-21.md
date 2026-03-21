# Continuous Learning Baseline Report

## Run Metadata
- Date: 2026-02-21
- Environment: local macOS development machine
- Branch: main (collection run)
- Commit: `90fd421`
- Collector command: `./scripts/collect-continuous-learning-baseline.sh --run-tests --output /tmp/aceclaw-baseline-with-tests.json`
- Output JSON path: `/tmp/aceclaw-baseline-with-tests.json`

## Summary
- Overall baseline status: partial (measurement schema established; multiple metrics pending instrumentation)
- Test collection status: passed
- Instrumentation coverage: partial

## Key Metrics
| Metric | Value | Target | Status | Notes |
|---|---:|---:|---|---|
| task_success_rate | null | 0.85 | pending_instrumentation | to be wired from runtime summaries |
| first_try_success_rate | null | 0.70 | pending_instrumentation | to be wired from retry tracking |
| retry_count_per_task | null | 0.60 max | pending_instrumentation | to be wired from turn history |
| tool_execution_success_rate | null | 0.95 | pending_instrumentation | data source exists, export pending |
| tool_error_rate | null | 0.05 max | pending_instrumentation | data source exists, export pending |
| permission_block_rate | null | 0.05 max | pending_instrumentation | needs unified permission block counters |
| timeout_rate | null | 0.03 max | pending_instrumentation | needs timeout event normalization |
| learning_hit_rate | null | 0.40 | pending_instrumentation | turn success rate on injected turns; read from injection-audit-summary.json |
| promotion_precision | null | 0.80 | pending_instrumentation | depends on auto-promotion pipeline |
| false_learning_rate | null | 0.10 max | pending_instrumentation | depends on demotion/rejection pipeline |
| time_to_promote_p50_hours | null | 72h max | pending_instrumentation | depends on candidate lifecycle timestamps |
| regression_rate_after_learning | null | 0.10 max | pending_instrumentation | depends on replay gates + rollout data |
| auto_rollback_rate | null | 0.10 max | pending_instrumentation | depends on auto-enforcement pipeline |

## Offline Replay A/B Deltas (`learning=on` vs `learning=off`)
| Metric | Value | Target | Status | Notes |
|---|---:|---:|---|---|
| replay_success_rate_delta | null | >= 0.00 | pending_instrumentation | replay runner tracked in issue #63 |
| replay_token_delta | null | <= 0 (or budget) | pending_instrumentation | replay runner tracked in issue #63 |
| replay_latency_delta_ms | null | <= 0 (or budget) | pending_instrumentation | replay runner tracked in issue #63 |
| replay_failure_distribution_delta | null | <= 0.15 | pending_instrumentation | replay runner tracked in issue #63 |

## Delta vs Previous Baseline
- Previous baseline reference: none (initial baseline run)
- Notable improvements: baseline schema, collector command, and report process established
- Notable regressions: none observed in this initialization run

## Risks
1. Low instrumentation coverage can hide real regressions until runtime counters are wired.
2. Replay deltas remain unmeasured until issue #63 is implemented.
3. Promotion-quality metrics remain unavailable until issues #60-#62 are implemented.

## Recommended Actions
1. Complete issue #53 to export tool metrics into baseline fields (`tool_execution_success_rate`, `tool_error_rate`).
2. Complete issue #54 to normalize permission/timeout failure signals.
3. Complete issue #63 to populate replay A/B delta metrics.
