# Continuous-Learning Quality Gates

This document defines the quality gate metrics used by CI (`preMergeCheck`) for self-learning changes.

## Canonical Verdict: BenchmarkScorecard

The canonical CI verdict is produced by `benchmarkScorecard` (via `BenchmarkScorecard.evaluate()`), which evaluates 8 metrics across 3 categories:

### Effectiveness (must pass)
- `replay_success_rate_delta` (≥ -0.10): learning must not significantly regress success rate (tolerance for small-sample noise).
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

The `replayQualityGate` task runs alongside `benchmarkScorecard` in `preMergeCheck`. It covers checks that the scorecard does not yet validate:

- `promotion_rate` (`min`): promotions / total candidate transitions.
- `demotion_rate` (`max`): demotions / total candidate transitions.
- `anti_pattern_false_positive_rate` (`max`): weighted anti-pattern false-positive rate.
- `rollback_rate` (`max`): rollback transitions / promotions.

Additional checks in `replayQualityGate` not yet in scorecard:
- Manifest provenance verification (SHA-256 checksum linkage)
- Token estimation calibration (`token_estimation_error_ratio_max`)
- Anti-pattern false-positive rate thresholds

## Gate Behavior

`./gradlew preMergeCheck` runs both `replayQualityGate` and `benchmarkScorecard`. Either gate can fail the build independently.

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

- If candidate transition artifacts are missing or empty, lifecycle metrics (`promotion_rate`, `demotion_rate`, `rollback_rate`, `promotion_precision`, `false_learning_rate`) are emitted with `status: "no_data"` and `value: null`. The quality gate skips these checks when status is explicitly `no_data`; missing or malformed metrics still fail the gate.
- `promotion_precision` and `false_learning_rate` are computed from candidate transition data when promotions exist; otherwise `no_data`. Transitions are produced by the daemon's `SelfImprovementEngine` during real sessions, not by CI replay runs.
