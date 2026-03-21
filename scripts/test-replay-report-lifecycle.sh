#!/usr/bin/env bash
# Integration test for generate-replay-report.sh lifecycle formula correctness.
# Verifies: double-counting fix, sample_size propagation, promotion_precision/false_learning_rate.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
TMPDIR="$(mktemp -d)"
trap 'rm -rf "$TMPDIR"' EXIT

# --- Setup: create minimal replay cases ---
cat > "$TMPDIR/replay-cases.json" << 'EOF'
{
  "cases": [
    {"id":"c1","off":{"success":true,"tokens":100,"latency_ms":50,"failure_type":null,"estimated_tokens":100,"provider_tokens":100,"estimation_error_ratio":0.0},"on":{"success":true,"tokens":90,"latency_ms":45,"failure_type":null,"estimated_tokens":90,"provider_tokens":90,"estimation_error_ratio":0.0}},
    {"id":"c2","off":{"success":false,"tokens":200,"latency_ms":100,"failure_type":"timeout","estimated_tokens":200,"provider_tokens":200,"estimation_error_ratio":0.0},"on":{"success":true,"tokens":150,"latency_ms":80,"failure_type":null,"estimated_tokens":150,"provider_tokens":150,"estimation_error_ratio":0.0}}
  ]
}
EOF

# --- Setup: create candidate transitions with known counts ---
# 5 promotions, 2 demotions (non-rollback), 1 rollback (MANUAL_ROLLBACK with toState=DEMOTED)
cat > "$TMPDIR/transitions.jsonl" << 'JSONL'
{"candidateId":"a","fromState":"SHADOW","toState":"PROMOTED","reasonCode":"GATE_CHECK_PASSED","timestamp":"2026-03-01T00:00:00Z"}
{"candidateId":"b","fromState":"SHADOW","toState":"PROMOTED","reasonCode":"GATE_CHECK_PASSED","timestamp":"2026-03-01T00:00:01Z"}
{"candidateId":"c","fromState":"SHADOW","toState":"PROMOTED","reasonCode":"GATE_CHECK_PASSED","timestamp":"2026-03-01T00:00:02Z"}
{"candidateId":"d","fromState":"SHADOW","toState":"PROMOTED","reasonCode":"GATE_CHECK_PASSED","timestamp":"2026-03-01T00:00:03Z"}
{"candidateId":"e","fromState":"SHADOW","toState":"PROMOTED","reasonCode":"GATE_CHECK_PASSED","timestamp":"2026-03-01T00:00:04Z"}
{"candidateId":"a","fromState":"PROMOTED","toState":"DEMOTED","reasonCode":"SCORE_DECAY","timestamp":"2026-03-02T00:00:00Z"}
{"candidateId":"b","fromState":"PROMOTED","toState":"DEMOTED","reasonCode":"EVIDENCE_FAILURE","timestamp":"2026-03-02T00:00:01Z"}
{"candidateId":"c","fromState":"PROMOTED","toState":"DEMOTED","reasonCode":"MANUAL_ROLLBACK","timestamp":"2026-03-02T00:00:02Z"}
JSONL

# --- Run report generation ---
bash "$SCRIPT_DIR/generate-replay-report.sh" \
  --input "$TMPDIR/replay-cases.json" \
  --candidate-transitions "$TMPDIR/transitions.jsonl" \
  --output "$TMPDIR/report.json" \
  --anti-pattern-feedback /dev/null \
  --manifest /dev/null

if [[ ! -f "$TMPDIR/report.json" ]]; then
  echo "FAIL: report.json not generated" >&2
  exit 1
fi

ERRORS=0

assert_metric() {
  local metric="$1" field="$2" expected="$3"
  local actual
  actual="$(jq -r ".metrics.\"$metric\".\"$field\"" "$TMPDIR/report.json")"
  if [[ "$actual" != "$expected" ]]; then
    echo "FAIL: $metric.$field = $actual (expected $expected)" >&2
    ERRORS=$((ERRORS + 1))
  else
    echo "PASS: $metric.$field = $actual"
  fi
}

assert_metric_approx() {
  local metric="$1" field="$2" expected="$3" tolerance="$4"
  local actual
  actual="$(jq -r ".metrics.\"$metric\".\"$field\"" "$TMPDIR/report.json")"
  local diff
  diff="$(echo "$actual - $expected" | bc -l 2>/dev/null | tr -d '-' || echo "999")"
  if (( $(echo "$diff > $tolerance" | bc -l) )); then
    echo "FAIL: $metric.$field = $actual (expected ~$expected ±$tolerance)" >&2
    ERRORS=$((ERRORS + 1))
  else
    echo "PASS: $metric.$field = $actual (~$expected)"
  fi
}

echo "=== Replay metrics sample_size ==="
assert_metric "replay_success_rate_delta" "sample_size" "2"
assert_metric "replay_token_delta" "sample_size" "2"
assert_metric "replay_latency_delta_ms" "sample_size" "2"
assert_metric "replay_failure_distribution_delta" "sample_size" "2"

echo ""
echo "=== Lifecycle metrics ==="
# 5 promotions, 2 demotions (non-rollback), 1 rollback
# promotion_precision = (5 - 2 - 1) / 5 = 0.4
assert_metric_approx "promotion_precision" "value" "0.4" "0.01"
assert_metric "promotion_precision" "status" "measured"
assert_metric "promotion_precision" "sample_size" "5"

# false_learning_rate = (2 + 1) / 5 = 0.6
assert_metric_approx "false_learning_rate" "value" "0.6" "0.01"
assert_metric "false_learning_rate" "status" "measured"
assert_metric "false_learning_rate" "sample_size" "5"

# rollback_rate = 1 / 5 = 0.2
assert_metric_approx "rollback_rate" "value" "0.2" "0.01"
assert_metric "rollback_rate" "sample_size" "5"

echo ""
echo "=== Double-counting guard ==="
# demotion_count should be 2 (SCORE_DECAY + EVIDENCE_FAILURE), NOT 3
# If rollback was double-counted, promotion_precision would be (5-3-1)/5=0.2 instead of 0.4
demotion_diag="$(jq -r '.diagnostics.learning_lifecycle_demotions' "$TMPDIR/report.json")"
if [[ "$demotion_diag" == "2" ]]; then
  echo "PASS: demotion count = 2 (rollback not double-counted)"
else
  echo "FAIL: demotion count = $demotion_diag (expected 2, rollback may be double-counted)" >&2
  ERRORS=$((ERRORS + 1))
fi

echo ""
if [[ "$ERRORS" -gt 0 ]]; then
  echo "FAILED: $ERRORS assertion(s) failed"
  exit 1
else
  echo "ALL PASSED"
fi
