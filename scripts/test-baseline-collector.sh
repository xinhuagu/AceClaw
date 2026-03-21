#!/usr/bin/env bash
# Integration tests for collect-continuous-learning-baseline.sh and
# export-injection-audit-summary.sh.
#
# Covers:
#   1. Stale injection summary removed when audit log is missing
#   2. Baseline collector priority: override > runtime > replay > injection > pending
#   3. Replay/lifecycle/source fields land correctly
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
TMPDIR="$(mktemp -d)"
trap 'rm -rf "$TMPDIR"' EXIT

ERRORS=0

assert_eq() {
  local label="$1" actual="$2" expected="$3"
  if [[ "$actual" == "$expected" ]]; then
    echo "PASS: $label = $actual"
  else
    echo "FAIL: $label = $actual (expected $expected)" >&2
    ERRORS=$((ERRORS + 1))
  fi
}

assert_file_missing() {
  local label="$1" path="$2"
  if [[ ! -f "$path" ]]; then
    echo "PASS: $label — file does not exist"
  else
    echo "FAIL: $label — file still exists: $path" >&2
    ERRORS=$((ERRORS + 1))
  fi
}

baseline_metric() {
  local file="$1" metric="$2" field="$3"
  jq -r ".metrics.\"$metric\".\"$field\"" "$file"
}

# ============================================================
# Test 1: Stale injection summary removed when audit log missing
# ============================================================
echo ""
echo "=== Test 1: stale summary cleanup ==="

PROJ1="$TMPDIR/test1"
mkdir -p "$PROJ1/.aceclaw/metrics/continuous-learning"
echo '{"metrics":{"learning_hit_rate":{"value":0.99,"target":0.40,"status":"measured"}}}' \
  > "$PROJ1/.aceclaw/metrics/continuous-learning/injection-audit-summary.json"

bash "$SCRIPT_DIR/export-injection-audit-summary.sh" \
  --project-root "$PROJ1" \
  --audit-path "$TMPDIR/nonexistent.jsonl" \
  --output "$PROJ1/.aceclaw/metrics/continuous-learning/injection-audit-summary.json" \
  2>/dev/null

assert_file_missing "stale summary removed" \
  "$PROJ1/.aceclaw/metrics/continuous-learning/injection-audit-summary.json"

# ============================================================
# Test 2: Baseline collector priority chain
# ============================================================
echo ""
echo "=== Test 2: priority chain ==="

PROJ2="$TMPDIR/test2"
mkdir -p "$PROJ2/.aceclaw/metrics/continuous-learning"

# Set up all three artifact files with distinct values for learning_hit_rate
echo '{"metrics":{"learning_hit_rate":{"value":0.50,"target":0.40,"status":"measured"}}}' \
  > "$PROJ2/.aceclaw/metrics/continuous-learning/runtime-latest.json"
echo '{"metrics":{"learning_hit_rate":{"value":0.60,"target":0.40,"status":"measured"}}}' \
  > "$PROJ2/.aceclaw/metrics/continuous-learning/replay-latest.json"
echo '{"metrics":{"learning_hit_rate":{"value":0.70,"target":0.40,"status":"measured"}}}' \
  > "$PROJ2/.aceclaw/metrics/continuous-learning/injection-audit-summary.json"

# 2a: override beats everything
bash "$SCRIPT_DIR/collect-continuous-learning-baseline.sh" \
  --project-root "$PROJ2" \
  --output "$TMPDIR/baseline-2a.json" \
  --metric learning_hit_rate=0.99 2>/dev/null

assert_eq "override > all: value" \
  "$(baseline_metric "$TMPDIR/baseline-2a.json" learning_hit_rate value)" "0.99"
assert_eq "override > all: source" \
  "$(baseline_metric "$TMPDIR/baseline-2a.json" learning_hit_rate source)" "manual_override"

# 2b: runtime beats replay and injection
bash "$SCRIPT_DIR/collect-continuous-learning-baseline.sh" \
  --project-root "$PROJ2" \
  --output "$TMPDIR/baseline-2b.json" 2>/dev/null

assert_eq "runtime > replay > injection: value" \
  "$(baseline_metric "$TMPDIR/baseline-2b.json" learning_hit_rate value)" "0.50"
assert_eq "runtime > replay > injection: source" \
  "$(baseline_metric "$TMPDIR/baseline-2b.json" learning_hit_rate source)" "runtime-latest.json"

# 2c: remove runtime → replay wins
rm "$PROJ2/.aceclaw/metrics/continuous-learning/runtime-latest.json"
bash "$SCRIPT_DIR/collect-continuous-learning-baseline.sh" \
  --project-root "$PROJ2" \
  --output "$TMPDIR/baseline-2c.json" 2>/dev/null

assert_eq "replay > injection: value" \
  "$(baseline_metric "$TMPDIR/baseline-2c.json" learning_hit_rate value)" "0.60"
assert_eq "replay > injection: source" \
  "$(baseline_metric "$TMPDIR/baseline-2c.json" learning_hit_rate source)" "replay-latest.json"

# 2d: remove replay → injection wins
rm "$PROJ2/.aceclaw/metrics/continuous-learning/replay-latest.json"
bash "$SCRIPT_DIR/collect-continuous-learning-baseline.sh" \
  --project-root "$PROJ2" \
  --output "$TMPDIR/baseline-2d.json" 2>/dev/null

assert_eq "injection only: value" \
  "$(baseline_metric "$TMPDIR/baseline-2d.json" learning_hit_rate value)" "0.70"
assert_eq "injection only: source" \
  "$(baseline_metric "$TMPDIR/baseline-2d.json" learning_hit_rate source)" "injection-audit-summary.json"

# 2e: remove injection → pending
rm "$PROJ2/.aceclaw/metrics/continuous-learning/injection-audit-summary.json"
bash "$SCRIPT_DIR/collect-continuous-learning-baseline.sh" \
  --project-root "$PROJ2" \
  --output "$TMPDIR/baseline-2e.json" 2>/dev/null

assert_eq "no artifacts: status" \
  "$(baseline_metric "$TMPDIR/baseline-2e.json" learning_hit_rate status)" "pending_instrumentation"
assert_eq "no artifacts: source" \
  "$(baseline_metric "$TMPDIR/baseline-2e.json" learning_hit_rate source)" "none"

# ============================================================
# Test 3: Replay and lifecycle metrics sourced correctly
# ============================================================
echo ""
echo "=== Test 3: replay/lifecycle source fields ==="

PROJ3="$TMPDIR/test3"
mkdir -p "$PROJ3/.aceclaw/metrics/continuous-learning"

cat > "$PROJ3/.aceclaw/metrics/continuous-learning/replay-latest.json" << 'EOF'
{
  "metrics": {
    "replay_success_rate_delta": {"value": 0.05, "target": 0.00, "status": "measured"},
    "replay_token_delta": {"value": -15.3, "target": 200.00, "status": "measured"},
    "replay_latency_delta_ms": {"value": -42.0, "target": 500.00, "status": "measured"},
    "replay_failure_distribution_delta": {"value": 0.08, "target": 0.15, "status": "measured"},
    "promotion_precision": {"value": 0.85, "target": 0.80, "status": "measured"},
    "false_learning_rate": {"value": 0.08, "target": 0.10, "status": "measured"},
    "rollback_rate": {"value": 0.10, "target": 0.20, "status": "measured"},
    "promotion_rate": {"value": 0.35, "target": 0.00, "status": "measured"},
    "demotion_rate": {"value": 0.12, "target": 0.35, "status": "measured"}
  }
}
EOF

bash "$SCRIPT_DIR/collect-continuous-learning-baseline.sh" \
  --project-root "$PROJ3" \
  --output "$TMPDIR/baseline-3.json" 2>/dev/null

# Replay metrics
for metric in replay_success_rate_delta replay_token_delta replay_latency_delta_ms replay_failure_distribution_delta; do
  assert_eq "$metric source" \
    "$(baseline_metric "$TMPDIR/baseline-3.json" "$metric" source)" "replay-latest.json"
  assert_eq "$metric status" \
    "$(baseline_metric "$TMPDIR/baseline-3.json" "$metric" status)" "measured"
done

# Lifecycle metrics
for metric in promotion_precision false_learning_rate rollback_rate promotion_rate demotion_rate; do
  assert_eq "$metric source" \
    "$(baseline_metric "$TMPDIR/baseline-3.json" "$metric" source)" "replay-latest.json"
  assert_eq "$metric status" \
    "$(baseline_metric "$TMPDIR/baseline-3.json" "$metric" status)" "measured"
done

# Verify specific values
assert_eq "replay_success_rate_delta value" \
  "$(baseline_metric "$TMPDIR/baseline-3.json" replay_success_rate_delta value)" "0.05"
assert_eq "promotion_precision value" \
  "$(baseline_metric "$TMPDIR/baseline-3.json" promotion_precision value)" "0.85"
assert_eq "rollback_rate value" \
  "$(baseline_metric "$TMPDIR/baseline-3.json" rollback_rate value)" "0.10"

# Metrics without artifacts should still be pending
assert_eq "time_to_promote_p50_hours pending" \
  "$(baseline_metric "$TMPDIR/baseline-3.json" time_to_promote_p50_hours status)" "pending_instrumentation"

# Collector version should be v2
assert_eq "collector_version" \
  "$(jq -r '.metadata.collector_version' "$TMPDIR/baseline-3.json")" "v2"

# ============================================================
# Test 4: Export script produces correct hit rate
# ============================================================
echo ""
echo "=== Test 4: injection export hit rate computation ==="

PROJ4="$TMPDIR/test4"
mkdir -p "$PROJ4/.aceclaw/memory" "$PROJ4/.aceclaw/metrics/continuous-learning"

# 1 injection, 3 outcomes (2 success, 1 failure) → hit_rate = 0.6667
printf '%s\n' \
  '{"type":"injection","data":{"candidatesInjected":3,"timestamp":"2026-03-01T00:00:00Z"}}' \
  '{"type":"outcome","data":{"success":true,"timestamp":"2026-03-01T00:01:00Z"}}' \
  '{"type":"outcome","data":{"success":true,"timestamp":"2026-03-01T00:02:00Z"}}' \
  '{"type":"outcome","data":{"success":false,"timestamp":"2026-03-01T00:03:00Z"}}' \
  > "$PROJ4/.aceclaw/memory/injection-audit.jsonl"

bash "$SCRIPT_DIR/export-injection-audit-summary.sh" \
  --project-root "$PROJ4" \
  --output "$PROJ4/.aceclaw/metrics/continuous-learning/injection-audit-summary.json" 2>/dev/null

SUMMARY="$PROJ4/.aceclaw/metrics/continuous-learning/injection-audit-summary.json"

assert_eq "export status" \
  "$(jq -r '.metrics.learning_hit_rate.status' "$SUMMARY")" "measured"
assert_eq "export sample_size" \
  "$(jq -r '.metrics.learning_hit_rate.sample_size' "$SUMMARY")" "3"
assert_eq "export total_injections" \
  "$(jq -r '.diagnostics.total_injections' "$SUMMARY")" "1"
assert_eq "export total_candidates_injected" \
  "$(jq -r '.diagnostics.total_candidates_injected' "$SUMMARY")" "3"

# Check hit rate is approximately 0.6667
HIT_RATE="$(jq -r '.metrics.learning_hit_rate.value' "$SUMMARY")"
EXPECTED="0.6666"
if [[ "$(echo "$HIT_RATE > $EXPECTED" | bc -l)" == "1" && "$(echo "$HIT_RATE < 0.6668" | bc -l)" == "1" ]]; then
  echo "PASS: export hit_rate ≈ 0.6667 (actual: $HIT_RATE)"
else
  echo "FAIL: export hit_rate = $HIT_RATE (expected ≈ 0.6667)" >&2
  ERRORS=$((ERRORS + 1))
fi

# ============================================================
# Summary
# ============================================================
echo ""
if [[ "$ERRORS" -gt 0 ]]; then
  echo "FAILED: $ERRORS assertion(s) failed."
  exit 1
else
  echo "ALL TESTS PASSED."
fi
