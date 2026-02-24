# Continuous Learning Operations Runbook

This runbook covers runtime controls, rollback, and configuration persistence for the candidate pipeline introduced in issue `#78`.

## Scope

- Candidate lifecycle: `shadow -> promoted -> demoted/rejected`
- Prompt injection: promoted-only candidates
- Runtime controls: kill-switch, token budget updates, manual rollback
- Outcome enforcement closure (`#62`): injected candidate outcome writeback, deterministic gate timing, lifecycle maintenance
- Autonomous draft validation (`#60`): deterministic `pass/hold/block` gate with machine-readable reason codes

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

Environment overrides:

- `ACECLAW_CANDIDATE_INJECTION`
- `ACECLAW_CANDIDATE_PROMOTION`
- `ACECLAW_CANDIDATE_INJECTION_MAX_TOKENS`
- `ACECLAW_SKILL_DRAFT_VALIDATION`
- `ACECLAW_SKILL_DRAFT_VALIDATION_STRICT_MODE`
- `ACECLAW_SKILL_DRAFT_VALIDATION_REPLAY_REQUIRED`
- `ACECLAW_REPLAY_REPORT_PATH`
- `ACECLAW_SKILL_DRAFT_VALIDATION_MAX_TOKEN_ESTIMATION_ERROR_RATIO`

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

### 4) Generate skill drafts from promoted candidates

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

### 5) Validate draft skills (autonomous gate)

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

## Verification

Smoke checks:

1. `candidate.injection.set(enabled=false)` should remove injected section in next turn.
2. `candidate.injection.set(enabled=true,maxTokens=...)` should apply within one turn.
3. `candidate.rollback` should transition `PROMOTED -> DEMOTED` and append transition log.
4. Ensure `skill.draft.generate` creates at least one `.aceclaw/skills-drafts/<skill-name>/SKILL.md` with `disable-model-invocation: true` and appends one line to `.aceclaw/metrics/continuous-learning/skill-draft-audit.jsonl`.
5. When running `skill.draft.validate`, verify deterministic `pass/hold/block` verdicts and appended lines in `.aceclaw/metrics/continuous-learning/skill-draft-validation-audit.jsonl`.

CI guardrail job:

- `.github/workflows/ci.yml` job `pre-merge-check`
- Executes `./gradlew preMergeCheck`, which includes:
  - full `build`
  - `continuousLearningSmoke` focused tests (daemon/memory/core)
  - `replayQualityGate` thresholds check (`scripts/replay-quality-gate.sh`)

Replay gate configuration:

- Default report path: `.aceclaw/metrics/continuous-learning/replay-latest.json`
- Strict mode is the default for `preMergeCheck` (missing report fails the build).
- Local override (only for exploratory runs): `./gradlew preMergeCheck -PreplayGateStrict=false`
- Custom report path: `./gradlew preMergeCheck -PreplayReport=/path/to/replay.json`
- CI uses strict mode and can override report path with `ACECLAW_REPLAY_REPORT_PATH`.
- CI first runs `generateReplayCases`, then `generateReplayReport`.
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
