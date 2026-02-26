# Anti-Pattern Pre-Execution Gate

## Goal
Convert learned anti-patterns from passive prompt hints into enforced runtime behavior so the agent automatically avoids repeating known bad paths.

## Scope
- In scope:
  - Pre-execution gate before each tool invocation.
  - Rule model for anti-pattern match and actions.
  - Deterministic override and cooldown behavior.
  - Observability and replay validation.
- Out of scope:
  - Cross-machine synchronization of gate state.
  - Restoring opaque external process handles.

## Why
Current learning artifacts are mostly advisory. The model can still retry known failing paths.
This gate adds execution constraints so repeated non-progressing failures are blocked or penalized before tool execution.

## Architecture
1. Rule Store
- Input: promoted candidates + anti-pattern signals.
- Output: normalized gate rules.
- Storage: JSONL with atomic rewrite.

2. Rule Schema
- `rule_id`
- `scope` (tool/category/tags)
- `match`:
  - `tool_name`
  - `failure_class` (dependency_missing, capability_mismatch, path, etc.)
  - `context_tags` (optional)
  - `min_repeat`
- `action`:
  - `BLOCK`
  - `PENALIZE`
- `fallback`:
  - required next strategy/tool preference guidance
- `cooldown_until`
- `created_at`, `updated_at`

3. Runtime Gate Hook
- Hook point: immediately before `tool.execute(...)`.
- Flow:
  - gather call context
  - evaluate matching rules
  - if `BLOCK`: skip execution and return structured gate result
  - if `PENALIZE`: allow execution with explicit warning and fallback hint attached
  - emit gate events

4. Override Policy
- Explicit user override command allowed for current task only.
- Override is audited with reason and expires at task end.

5. Lifecycle
- Rule promotion: candidate pipeline -> promoted anti-pattern -> active gate rule.
- Rule decay: cooldown and optional expiration after long healthy windows.
- Rule rollback: automatic on high false-positive rate.

## Observability
Emit machine-readable events:
- `gate.detected`
- `gate.blocked`
- `gate.penalized`
- `gate.override`
- `gate.expired`

Expose in CLI status:
- active gate count
- latest blocked rule id (short)

## Hard Acceptance Criteria
1. Repeated known bad path is blocked before execution in >=95% replay cases.
2. First blocked response includes:
- reason class
- matched rule id
- fallback next action
3. False-positive block rate remains under agreed threshold in replay.
4. Atomic persistence validated under interruption.
5. Override flow is deterministic and auditable.

## Test Plan
- Unit
  - matcher correctness
  - action selection (`BLOCK`/`PENALIZE`)
  - cooldown/expiry
  - override scoping
- Integration
  - repeated failure -> block on next attempt
  - penalty path adds warning but executes
  - user override unblocks only for current task
  - daemon restart preserves gate rules
- Replay
  - track hit rate, prevented-retry rate, false-positive rate

## Rollout
1. Phase 1: `PENALIZE` default, metrics-only for block candidates.
2. Phase 2: enable `BLOCK` for high-confidence classes (`dependency_missing`, `capability_mismatch`, `path`) with kill-switch.
3. Phase 3: full policy with CI replay threshold gate.

## Implementation Checklist
- [ ] Add anti-pattern rule model and persistence.
- [ ] Add pre-execution gate evaluator in agent loop.
- [ ] Add structured blocked response payload.
- [ ] Add override command and audit log.
- [ ] Add observability events and CLI status display.
- [ ] Add unit/integration/replay tests.
