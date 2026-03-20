# Continuous-Learning Quality Gates

This document defines the quality gate metrics used by CI (`preMergeCheck`) for self-learning changes.

## Canonical Verdict: BenchmarkScorecard

The canonical CI verdict is produced by `benchmarkScorecard` (via `BenchmarkScorecard.evaluate()`), which evaluates 8 metrics across 3 categories:

### Effectiveness (must pass)
- `replay_success_rate_delta` (≥ 0.00): learning must not regress success rate.
- `first_try_success_rate_delta` (≥ 0.00): pending — needs A/B per-case retry tracking.
- `retry_count_per_task_delta` (≤ 0.00): pending — needs A/B per-case retry tracking.

### Efficiency (informational)
- `replay_token_delta` (≤ 0.10): token cost increase from learning.
- `replay_latency_delta_ms` (≤ 500): latency increase from learning.

### Safety (must pass)
- `promotion_precision` (≥ 0.80): promoted candidates that stayed healthy / total promoted.
- `false_learning_rate` (≤ 0.10): promoted candidates later demoted or rolled back / total promoted.
- `rollback_rate` (≤ 0.20): rollback transitions / promotions.

Metrics with `sample_size < 10` are reported as `INSUFFICIENT_DATA` and do not block.

## Legacy Replay Quality Gate

The `replayQualityGate` task is retained for backward compatibility and standalone use but is **no longer in the `preMergeCheck` path**. It checks a narrower set of metrics:

- `promotion_rate` (`min`): promotions / total candidate transitions.
- `demotion_rate` (`max`): demotions / total candidate transitions.
- `anti_pattern_false_positive_rate` (`max`): weighted anti-pattern false-positive rate.
- `rollback_rate` (`max`): rollback transitions / promotions.

Standalone usage: `./gradlew replayQualityGate`

## Gate Behavior

`./gradlew preMergeCheck` runs `benchmarkScorecard` and fails when the scorecard verdict is FAIL (exit code 1).

Default threshold source:
- `docs/reports/samples/learning-quality-gate-baseline.json`

## Baseline Update Process

1. Collect replay results and generate report:
   - `./gradlew generateReplayCases generateReplayReport`
2. Observe 7-day main-branch trend for scorecard metrics.
3. Update targets in `docs/reports/samples/learning-quality-gate-baseline.json`.
4. Run full gate locally:
   - `./gradlew preMergeCheck`
5. Include threshold-change rationale in PR description.

## Notes

- If candidate transition artifacts are missing, report generation uses conservative zero-value lifecycle defaults and marks source diagnostics.
- `promotion_precision` and `false_learning_rate` are computed from candidate transition data when available; otherwise marked `pending_instrumentation`.
