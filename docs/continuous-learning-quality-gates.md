# Continuous-Learning Quality Gates

This document defines the hard quality gate metrics used by CI (`preMergeCheck`) for self-learning changes.

These gates sit at the end of the governed-learning path:

```mermaid
flowchart LR
  a["Replay cases"] --> b["Replay report"]
  b --> c["Quality-gate metrics"]
  c --> d["preMergeCheck"]
  d --> e["Allow or block merge"]
```

## Canonical Metrics

These metrics are emitted in `.aceclaw/metrics/continuous-learning/replay-latest.json` under `metrics`.

- `promotion_rate` (`min`): promotions / total candidate transitions.
- `demotion_rate` (`max`): demotions / total candidate transitions.
- `anti_pattern_false_positive_rate` (`max`): weighted anti-pattern false-positive rate.
- `rollback_rate` (`max`): rollback transitions / promotions.

## Gate Behavior

`./gradlew preMergeCheck` runs `replayQualityGate` and fails when any threshold is violated.

Default threshold source:
- `docs/reports/samples/learning-quality-gate-baseline.json`

Gradle overrides:
- `-PreplayBaseline=/path/to/baseline.json`
- `-PreplayMinPromotionRate=...`
- `-PreplayMaxDemotionRate=...`
- `-PreplayMaxAntiPatternFalsePositiveRate=...`
- `-PreplayMaxRollbackRate=...`

## Baseline Update Process

1. Collect replay results and generate report:
   - `./gradlew generateReplayCases generateReplayReport`
2. Observe 7-day main-branch trend for the 4 canonical metrics.
3. Update targets in `docs/reports/samples/learning-quality-gate-baseline.json`.
4. Run strict gate locally:
   - `./gradlew preMergeCheck -PreplayGateStrict=true`
5. Include threshold-change rationale in PR description.

## Notes

- If candidate transition artifacts are missing, report generation uses conservative zero-value lifecycle defaults and marks source diagnostics.
- Anti-pattern false-positive gate is hard-enforced by default in `preMergeCheck`.
