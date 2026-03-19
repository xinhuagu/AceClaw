# Continuous Learning Operations Runbook

This runbook covers runtime controls, rollback, and configuration persistence for the current governed-learning pipeline.

## Scope

- Candidate lifecycle: `shadow -> promoted -> demoted/rejected`
- Prompt injection: promoted-only candidates
- Runtime controls: kill-switch, token budget updates, manual rollback
- Outcome enforcement: injected candidate outcome writeback, deterministic gate timing, lifecycle maintenance
- Autonomous draft validation: deterministic `pass/hold/block` gate with machine-readable reason codes
- Automated skill release: `shadow -> canary -> active` rollout with guardrail-based auto-rollback
- Governance baseline: process/safety/rollback rules plus status-event protocol

Related document:
- [Continuous-Learning Governance](docs/continuous-learning-governance.md)

## Configuration

Config keys in `.aceclaw/config.json`:

- `candidateInjectionEnabled` (`bool`)
- `candidatePromotionEnabled` (`bool`)
- `candidatePromotionMinEvidence` (`int`)
- `candidatePromotionMinScore` (`double`)
- `candidatePromotionMaxFailureRate` (`double`)
- `candidateInjectionMaxCount` (`int`)
- `candidateInjectionMaxTokens` (`int`)
- `skillDraftValidationEnabled` (`bool`, default `true`)
- `skillDraftValidationStrictMode` (`bool`, default `false`)
- `skillDraftValidationReplayRequired` (`bool`, default `true`)
- `skillDraftValidationReplayReport` (`string`, default `.aceclaw/metrics/continuous-learning/replay-latest.json`)
- `skillDraftValidationMaxTokenEstimationErrorRatio` (`double`, default `0.65`)
- `skillAutoReleaseEnabled` (`bool`, default `true`)
- `skillAutoReleaseMinCandidateScore` (`double`, default `0.80`)
- `skillAutoReleaseMinEvidence` (`int`, default `3`)
- `skillAutoReleaseCanaryMinAttempts` (`int`, default `20`)
- `skillAutoReleaseCanaryMaxFailureRate` (`double`, default `0.10`)
- `skillAutoReleaseCanaryMaxTimeoutRate` (`double`, default `0.20`)
- `skillAutoReleaseCanaryMaxPermissionBlockRate` (`double`, default `0.20`)
- `skillAutoReleaseCanaryDwellHours` (`int`, default `24` — minimum hours at CANARY before promotion to ACTIVE)
- `skillAutoReleaseRollbackMaxFailureRate` (`double`, default `0.20`)
- `skillAutoReleaseRollbackMaxTimeoutRate` (`double`, default `0.20`)
- `skillAutoReleaseRollbackMaxPermissionBlockRate` (`double`, default `0.20`)
- `skillAutoReleaseActiveMaxFailureRate` (`double`, legacy alias for rollback failure threshold)
- `skillAutoReleaseHealthLookbackHours` (`int`, default `168`)

Environment overrides:

- `ACECLAW_CANDIDATE_INJECTION`
- `ACECLAW_CANDIDATE_PROMOTION`
- `ACECLAW_CANDIDATE_INJECTION_MAX_TOKENS`
- `ACECLAW_SKILL_DRAFT_VALIDATION`
- `ACECLAW_SKILL_DRAFT_VALIDATION_STRICT_MODE`
- `ACECLAW_SKILL_DRAFT_VALIDATION_REPLAY_REQUIRED`
- `ACECLAW_REPLAY_REPORT_PATH`
- `ACECLAW_SKILL_DRAFT_VALIDATION_MAX_TOKEN_ESTIMATION_ERROR_RATIO`
- `ACECLAW_SKILL_AUTO_RELEASE`
- `ACECLAW_SKILL_AUTO_RELEASE_MIN_SCORE`
- `ACECLAW_SKILL_AUTO_RELEASE_MIN_EVIDENCE`
- `ACECLAW_SKILL_AUTO_RELEASE_CANARY_MIN_ATTEMPTS`
- `ACECLAW_SKILL_AUTO_RELEASE_CANARY_MAX_FAILURE_RATE`
- `ACECLAW_SKILL_AUTO_RELEASE_CANARY_MAX_TIMEOUT_RATE`
- `ACECLAW_SKILL_AUTO_RELEASE_CANARY_MAX_PERMISSION_BLOCK_RATE`
- `ACECLAW_SKILL_AUTO_RELEASE_ROLLBACK_MAX_FAILURE_RATE`
- `ACECLAW_SKILL_AUTO_RELEASE_ROLLBACK_MAX_TIMEOUT_RATE`
- `ACECLAW_SKILL_AUTO_RELEASE_ROLLBACK_MAX_PERMISSION_BLOCK_RATE`
- `ACECLAW_SKILL_AUTO_RELEASE_ACTIVE_MAX_FAILURE_RATE`
- `ACECLAW_SKILL_AUTO_RELEASE_HEALTH_LOOKBACK_HOURS`
- `ACECLAW_SKILL_AUTO_RELEASE_CANARY_DWELL_HOURS`

JVM/system properties:

- `aceclaw.candidate.injection.tokenHeadroomFactor` (`0 < value <= 1`, default `0.85`)
  - Applies a conservative headroom multiplier before token-budget enforcement to reduce estimator undercount risk.

Tokenizer hard constraint:

- Replay cases now record dual-track token signals per mode:
  - `estimated_tokens` (ContextEstimator estimate)
  - `provider_tokens` (provider `outputTokens` when available)
  - `estimation_error_ratio = |estimated - provider| / provider`
- Replay report computes `token_estimation_error_ratio_max` using a dataset-level calibration factor
  (`avg(provider/estimated)`) and evaluates the calibrated residual.
- Pre-merge gate enforces:
  - `token_estimation_error_ratio_max <= 0.25`
  - Metric status must be `measured` (not `pending`)
  - In strict mode, manifest verification (`source_manifest.verified=true`) is also mandatory.
  - Replay performance budgets:
    - `replay_token_delta <= 200`
    - `replay_latency_delta_ms <= 500` (warning-only by default; blocking when `replayFailOnLatency=true`)

## Outcome Enforcement (`#62`)

- Runtime turn completion now writes back outcomes for `injectedCandidateIds` into candidate evidence.
- Writeback includes success/failure and severity signals, then triggers `evaluateAll()` for automatic promotion/demotion checks.
- Candidate state-machine gates use injected `Clock` for deterministic cooldown/lookback behavior in tests.
- Candidate maintenance now includes:
  - stale candidate cleanup (retention policy)
  - score decay for inactive candidates (half-life policy)
- Candidate pipeline now has dedicated concurrency tests for parallel upsert/outcome/evaluate flows.

Operational note:
- Maintenance defaults are intentionally conservative (`retention=90d`, `decayHalfLife=30d`, `decayGrace=7d`, maintenance interval `24h`).

## Runtime RPC Controls

### 1) Kill-switch (runtime only)

Disable injection immediately (no restart):

```json
{
  "method": "candidate.injection.set",
  "params": {
    "enabled": false
  }
}
```

### 2) Kill-switch + persistent config write

Disable and persist to project config:

```json
{
  "method": "candidate.injection.set",
  "params": {
    "enabled": false,
    "persist": true,
    "scope": "project"
  }
}
```

Parameters:

- `persist`: `true` to write config file
- `scope`: `project` or `global` (default: `project`)
- `maxTokens`: optional injection token budget update

### 3) Manual rollback of a promoted candidate

```json
{
  "method": "candidate.rollback",
  "params": {
    "candidateId": "CANDIDATE_ID",
    "reason": "manual rollback after regression"
  }
}
```

### 4) Anti-pattern gate temporary override

```json
{
  "method": "antiPatternGate.override.set",
  "params": {
    "sessionId": "SESSION_ID",
    "tool": "bash",
    "action": "ALLOW",
    "scope": "session",
    "reason": "false positive while investigating replay drift"
  }
}
```

Parameters:
- `sessionId` (required): target session id.
- `tool` (required): tool name to override (for example `bash`, `web_search`).
- `action` (optional): `ALLOW` | `PENALIZE` | `BLOCK`.
- `scope` (optional): `session` | `tool` | `global`.
- `reason` (optional): human-readable audit reason.

### 5) Generate skill drafts from promoted candidates

```json
{
  "method": "skill.draft.generate",
  "params": {}
}
```

Behavior:
- Reads `PROMOTED` candidates from candidate store.
- Generates drafts at `.aceclaw/skills-drafts/<skill-name>/SKILL.md`.
- Generated drafts always include `disable-model-invocation: true`.
- Writes generation audit to:
  `.aceclaw/metrics/continuous-learning/skill-draft-audit.jsonl`.
- If draft validation is enabled, generation also returns a validation summary.

### 6) Validate draft skills (autonomous gate)

```json
{
  "method": "skill.draft.validate",
  "params": {
    "trigger": "manual"
  }
}
```

Optional single-draft validation:

```json
{
  "method": "skill.draft.validate",
  "params": {
    "draftPath": ".aceclaw/skills-drafts/retry-safe/SKILL.md",
    "trigger": "manual-single"
  }
}
```

Gate behavior:
- Verdicts: `pass`, `hold`, `block`
- Policy packs: `static`, `dry-run`, `replay`, `safety`
- Machine-readable reason payload: `{ gate, code, outcome, message }`
- Validation audit log:
  `.aceclaw/metrics/continuous-learning/skill-draft-validation-audit.jsonl`
- Auto re-evaluation trigger:
  candidate evidence/score updates trigger background re-validation (`trigger=evidence-update`).

### 7) Evaluate automated release controller

```json
{
  "method": "skill.release.evaluate",
  "params": {
    "trigger": "manual"
  }
}
```

Rollout policy:
- Stage order: `shadow -> canary -> active`
- `canary` publishes skill with `disable-model-invocation: true` (limited traffic)
- `active` publishes skill with `disable-model-invocation: false`
- Auto-rollback to `shadow` on validation failure or guardrail breach

Release audit/state:
- State snapshot: `.aceclaw/metrics/continuous-learning/skill-release-state.json`
- Transition audit: `.aceclaw/metrics/continuous-learning/skill-release-audit.jsonl`

### 8) Emergency override commands

Pause automatic progression:

```json
{
  "method": "skill.release.pause",
  "params": {
    "skillName": "retry-skill",
    "reason": "investigating regression"
  }
}
```

Force rollback:

```json
{
  "method": "skill.release.forceRollback",
  "params": {
    "skillName": "retry-skill",
    "reason": "manual containment"
  }
}
```

Force promote:

```json
{
  "method": "skill.release.forcePromote",
  "params": {
    "skillName": "retry-skill",
    "targetStage": "active",
    "reason": "manual release approval"
  }
}
```

## Benchmark-Driven Rollout Policy

### Stage Definitions

| Stage | Behavior | Entry Criteria | Exit Criteria |
|-------|----------|---------------|---------------|
| **SHADOW** | Candidates scored but not injected broadly | Automatic on first observation | Validation gate PASS + candidate score ≥ 0.80 + evidence ≥ 3 |
| **CANARY** | Injected with `disable-model-invocation: true` | Promoted from SHADOW | ≥ 20 attempts + ≤ 10% failure + ≤ 20% timeout + 24h dwell time |
| **ACTIVE** | Full injection, model invocation enabled | Promoted from CANARY | Auto-rollback if rollback guardrails breached |

### Benchmark Scorecard Gates

Before any CANARY → ACTIVE promotion, the benchmark scorecard (`BenchmarkScorecard`) must show:

**Effectiveness (must pass):**
- `replay_success_rate_delta` ≥ 0.00 (learning must not regress success)
- `first_try_success_rate_delta` ≥ 0.00
- `retry_count_per_task_delta` ≤ 0.00 (fewer retries = better)

**Efficiency (informational, warning only):**
- `replay_token_delta` ≤ 10% increase
- `replay_latency_delta_ms` ≤ 500ms increase

**Safety (must pass):**
- `promotion_precision` ≥ 0.80 (promoted candidates that stay healthy)
- `false_learning_rate` ≤ 0.10 (candidates later demoted/rejected)
- `rollback_rate` ≤ 0.20

All metrics require minimum sample size n ≥ 10. Metrics with insufficient data show `INSUFFICIENT_DATA` status and do not block promotion.

### Auto-Rollback Triggers

A skill is automatically rolled back to SHADOW when:
- Failure rate > 20% (over 7-day lookback window)
- Timeout rate > 20%
- Permission block rate > 20%
- Validation gate verdict changes to HOLD or BLOCK

### Emergency Procedures

| Situation | Action | Command |
|-----------|--------|---------|
| Single bad skill | Rollback specific skill | `skill.release.forceRollback` |
| Broad regression | Pause all promotions | `candidate.injection.set(enabled=false)` |
| 3+ rollbacks in 7 days | Manual investigation required | Review `skill-release-audit.jsonl` |

### Baseline Refresh Process

1. Run full replay suite monthly (or after significant code changes)
2. Compare new results against current baseline
3. If metrics improved: update baseline thresholds in `learning-quality-gate-baseline.json`
4. If metrics regressed: investigate before updating — regression may indicate a real problem
5. Document rationale for any threshold changes in PR description

### Threshold Tuning

Thresholds can be tuned via config (no recompile needed):

```json
{
  "skillAutoReleaseCanaryMinAttempts": 20,
  "skillAutoReleaseCanaryMaxFailureRate": 0.10,
  "skillAutoReleaseCanaryDwellHours": 24,
  "skillAutoReleaseRollbackMaxFailureRate": 0.20
}
```

Or via environment variables for CI/deployment overrides:
```bash
ACECLAW_SKILL_AUTO_RELEASE_CANARY_MAX_FAILURE_RATE=0.05  # stricter for production
ACECLAW_SKILL_AUTO_RELEASE_CANARY_DWELL_HOURS=48         # longer observation
```

## Observability Panel (`#64`)

The interactive CLI prompt status panel includes a continuous-learning summary line
when project-local learning artifacts are present.

- Replay quality status:
  - source: `.aceclaw/metrics/continuous-learning/replay-latest.json`
  - display: `replay=<status>:<token_estimation_error_ratio_max>` (or `pending` / `read-error`)
- Candidate pipeline status:
  - source: `.aceclaw/memory/candidates.jsonl`
  - display: total candidates and state counters (`PROMOTED`, `DEMOTED`)
- Auto-release rollout status:
  - source: `.aceclaw/metrics/continuous-learning/skill-release-state.json`
  - display: stage counters (`SHADOW`, `CANARY`, `ACTIVE`)

Notes:
- This panel is read-only observability; it does not mutate runtime or config.
- The summary is cached briefly and refreshed periodically to avoid per-keystroke IO.

## Incident Playbook

### Prompt regression suspected

1. Execute `candidate.injection.set(enabled=false)` for immediate containment.
2. If regression confirmed, persist with `persist=true`.
3. For specific bad candidate, run `candidate.rollback`.
4. Review `memory/candidate-transitions.jsonl` for reason codes and window metrics.

### Promotion quality drift

1. Keep injection enabled if needed, but disable promotion via config/env.
2. Increase strictness:
   - `candidatePromotionMinEvidence`
   - `candidatePromotionMinScore`
   - lower `candidatePromotionMaxFailureRate`
3. Re-enable promotion after stabilization.

### Anti-pattern gate false-positive drift

1. Inspect `.aceclaw/metrics/continuous-learning/anti-pattern-gate-feedback.json`:
   - weighted false-positive rate
   - top offending `ruleId`
2. If a rule is clearly over-blocking, apply temporary override for active session/tool:
   - `antiPatternGate.override.set`
3. Confirm success path with replay and runtime events (`stream.gate`).
4. Let automatic rollback/downgrade run (default policy), only use manual `candidate.rollback` when urgent.
5. Record root cause and threshold tuning decision in PR notes.

## Verification

Smoke checks:

1. `candidate.injection.set(enabled=false)` should remove injected section in next turn.
2. `candidate.injection.set(enabled=true,maxTokens=...)` should apply within one turn.
3. `candidate.rollback` should transition `PROMOTED -> DEMOTED` and append transition log.
4. Ensure `skill.draft.generate` creates at least one `.aceclaw/skills-drafts/<skill-name>/SKILL.md` with `disable-model-invocation: true` and appends one line to `.aceclaw/metrics/continuous-learning/skill-draft-audit.jsonl`.
5. When running `skill.draft.validate`, verify deterministic `pass/hold/block` verdicts and appended lines in `.aceclaw/metrics/continuous-learning/skill-draft-validation-audit.jsonl`.
6. `skill.release.evaluate` should emit stage transitions and persist release state/audit files.

CI guardrail job:

- `.github/workflows/ci.yml` job `pre-merge-check`
- Executes `./gradlew preMergeCheck`, which includes:
  - full `build`
  - `continuousLearningSmoke` focused tests (daemon/memory/core)
  - `replayQualityGate` → `generateReplayReport` → `generateReplayCases` (chained)

Task dependency chain (enforced by Gradle):
```
preMergeCheck
├── build
├── continuousLearningSmoke
└── replayQualityGate
    └── generateReplayReport
        └── generateReplayCases
            ├── validateReplaySuite
            └── :aceclaw-cli:runReplayCases
```

This ensures CI always evaluates freshly generated replay artifacts — never stale or sample data.

Replay gate configuration:

- Default report path: `.aceclaw/metrics/continuous-learning/replay-latest.json`
- Default replay cases input: `.aceclaw/metrics/continuous-learning/replay-cases.json` (generated, not sample)
- Default baseline thresholds file: `docs/reports/samples/learning-quality-gate-baseline.json`
- Strict mode is the default for `preMergeCheck` (missing report fails the build).

Local exploratory runs (bypasses artifact generation):
```bash
# Skip fresh generation, use existing/sample artifacts:
./gradlew replayQualityGate -PreplayCasesInput=docs/reports/samples/replay-cases-sample.json -PreplayGateStrict=false
```

Canonical CI runs (enforces fresh generation):
```bash
# Full pipeline — generates cases, report, then gates:
./gradlew preMergeCheck -PreplayGateStrict=true
```
- CI default enforces anti-pattern false-positive gate:
  - `ACECLAW_REPLAY_ENFORCE_ANTI_PATTERN_FP_RATE=true`
  - threshold `ACECLAW_REPLAY_MAX_ANTI_PATTERN_FP_RATE=0.50`
- Canonical hard-gate metrics:
  - `promotion_rate` (min)
  - `demotion_rate` (max)
  - `anti_pattern_false_positive_rate` (max)
  - `rollback_rate` (max)
- See `docs/continuous-learning-quality-gates.md` for formulas and baseline update process.
- replay cases input/output path:
  - `ACECLAW_REPLAY_INPUT_PATH` (recommended), or
  - default `.aceclaw/metrics/continuous-learning/replay-cases.json`
- replay cases manifest path:
  - `ACECLAW_REPLAY_MANIFEST_PATH` (default: `.aceclaw/metrics/continuous-learning/replay-cases.manifest.json`)
- CI replay runner inputs:
  - `ACECLAW_REPLAY_FULL_MODE` (default: `false`)
  - `ACECLAW_REPLAY_PROMPTS_PATH` default:
    - full mode (`true`): `docs/reports/samples/replay-prompts-sample.json`
    - default (`false`): `docs/reports/samples/replay-prompts-ci-short.json` (5 short cases)
  - `ACECLAW_REPLAY_SUITE_MIN_PER_CATEGORY` default:
    - full mode (`true`): `5`
    - default (`false`): `1`
  - `ACECLAW_REPLAY_TIMEOUT_MS` (default: `180000`)
  - `ACECLAW_REPLAY_AUTO_APPROVE_PERMISSIONS` (default: `true`)
  - `ACECLAW_REPLAY_MAX_TOKEN_ESTIMATION_ERROR_RATIO` (default by event):
    - full mode (`true`): `0.25` (full-sample strict)
    - default (`false`): `0.65` (small-sample tolerance)
- Generate replay cases from real prompt executions (off/on modes):
  - `./gradlew generateReplayCases -PreplayPromptsInput=/path/to/replay-prompts.json -PreplayCasesOutput=/path/to/replay-cases.json -PreplayCasesManifestOutput=/path/to/replay-cases.manifest.json`
  - Prompt suite must pass schema + category coverage validation (`validateReplaySuite`)
  - default prompt sample: `docs/reports/samples/replay-prompts-sample.json`
  - prompt case optional field: `timeout_ms` (per-case timeout override)
  - output schema is compatible with `scripts/generate-replay-report.sh`
- Generate replay report from A/B cases:
  - `./gradlew generateReplayReport -PreplayCasesInput=/path/to/replay-cases.json -PreplayCasesManifestInput=/path/to/replay-cases.manifest.json -PreplayReport=/path/to/replay-latest.json`
- Input schema: `docs/reports/samples/replay-cases-sample.json`
- Required fields per case:
  - `off.success`, `off.tokens`, `off.latency_ms`, `off.failure_type`
  - `on.success`, `on.tokens`, `on.latency_ms`, `on.failure_type`
- Optional token dual-track fields per mode:
  - `estimated_tokens` (number)
  - `provider_tokens` (number or null)
  - `estimation_error_ratio` (number or null)
- New replay gate metric:
  - `token_estimation_error_ratio_max` (target/default threshold: `<= 0.25`)
- Anti-pattern gate replay metrics:
  - `anti_pattern_gate_false_positive_rate_weighted`
  - `anti_pattern_gate_false_positive_rate_max`

Default pre-merge pass criteria (CI):

1. `replay_success_rate_delta` measured and above threshold.
2. `replay_token_delta` measured and below threshold.
3. `replay_failure_distribution_delta` is measured and remains below threshold.
4. `token_estimation_error_ratio_max` does not exceed threshold.
5. `anti_pattern_gate_false_positive_rate_weighted` is measured and `<= 0.50`.
6. `anti_pattern_gate_false_positive_rate_max` is measured and `<= 0.50`.
