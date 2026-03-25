#!/usr/bin/env bash
set -euo pipefail

REPORT=""
BASELINE=""
BASELINE_EXPLICIT=0
STRICT=0

MIN_SUCCESS_RATE_DELTA="0.00"
MAX_TOKEN_DELTA="200.00"
MAX_LATENCY_DELTA_MS="500.00"
MAX_FAILURE_DISTRIBUTION_DELTA="0.15"
MAX_TOKEN_ESTIMATION_ERROR_RATIO="0.25"
MIN_PROMOTION_RATE="0.00"
MAX_DEMOTION_RATE="0.35"
MAX_ROLLBACK_RATE="0.20"
FAIL_ON_LATENCY="false"
ENFORCE_ANTI_PATTERN_FP_RATE="false"
MAX_ANTI_PATTERN_FP_RATE="0.50"
MIN_PROMOTION_RATE_CLI=0
MAX_DEMOTION_RATE_CLI=0
MAX_ROLLBACK_RATE_CLI=0
MAX_ANTI_PATTERN_FP_RATE_CLI=0

usage() {
  cat <<USAGE
Usage: ./scripts/replay-quality-gate.sh [options]

Options:
  --report <path>                        Replay report JSON path.
  --strict                               Fail when report is missing.
  --baseline <path>                      Optional baseline JSON path for target defaults.
  --min-success-rate-delta <number>      Default: 0.00
  --max-token-delta <number>             Default: 200.00
  --max-latency-delta-ms <number>        Default: 500.00
  --fail-on-latency <true|false>         Default: false
  --max-failure-dist-delta <number>      Default: 0.15
  --max-token-estimation-error-ratio <number>  Default: 0.25
  --min-promotion-rate <number>          Default: 0.00
  --max-demotion-rate <number>           Default: 0.35
  --max-rollback-rate <number>           Default: 0.20
  --enforce-anti-pattern-fp-rate <true|false>  Default: false
  --max-anti-pattern-fp-rate <number>     Default: 0.50
  --help                                 Show this help.
USAGE
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --report)
      REPORT="$2"
      shift 2
      ;;
    --strict)
      STRICT=1
      shift
      ;;
    --baseline)
      BASELINE="$2"
      BASELINE_EXPLICIT=1
      shift 2
      ;;
    --min-success-rate-delta)
      MIN_SUCCESS_RATE_DELTA="$2"
      shift 2
      ;;
    --max-token-delta)
      MAX_TOKEN_DELTA="$2"
      shift 2
      ;;
    --max-latency-delta-ms)
      MAX_LATENCY_DELTA_MS="$2"
      shift 2
      ;;
    --fail-on-latency)
      FAIL_ON_LATENCY="$2"
      shift 2
      ;;
    --max-failure-dist-delta)
      MAX_FAILURE_DISTRIBUTION_DELTA="$2"
      shift 2
      ;;
    --max-token-estimation-error-ratio)
      MAX_TOKEN_ESTIMATION_ERROR_RATIO="$2"
      shift 2
      ;;
    --min-promotion-rate)
      MIN_PROMOTION_RATE="$2"
      MIN_PROMOTION_RATE_CLI=1
      shift 2
      ;;
    --max-demotion-rate)
      MAX_DEMOTION_RATE="$2"
      MAX_DEMOTION_RATE_CLI=1
      shift 2
      ;;
    --max-rollback-rate)
      MAX_ROLLBACK_RATE="$2"
      MAX_ROLLBACK_RATE_CLI=1
      shift 2
      ;;
    --enforce-anti-pattern-fp-rate)
      ENFORCE_ANTI_PATTERN_FP_RATE="$2"
      shift 2
      ;;
    --max-anti-pattern-fp-rate)
      MAX_ANTI_PATTERN_FP_RATE="$2"
      MAX_ANTI_PATTERN_FP_RATE_CLI=1
      shift 2
      ;;
    --help)
      usage
      exit 0
      ;;
    *)
      echo "Unknown option: $1" >&2
      usage
      exit 1
      ;;
  esac
done

if [[ -z "$REPORT" ]]; then
  REPORT=".aceclaw/metrics/continuous-learning/replay-latest.json"
fi
if [[ -z "$BASELINE" ]]; then
  BASELINE="docs/reports/samples/learning-quality-gate-baseline.json"
fi
if [[ "$BASELINE_EXPLICIT" -eq 1 && ! -f "$BASELINE" ]]; then
  echo "Replay quality gate failed: missing baseline at $BASELINE" >&2
  exit 1
fi

if ! command -v jq >/dev/null 2>&1; then
  echo "replay-quality-gate requires jq but it was not found in PATH." >&2
  exit 1
fi

if [[ -f "$BASELINE" ]]; then
  baseline_target() {
    local key="$1"
    jq -er --arg key "$key" '.metrics[$key].target' "$BASELINE" 2>/dev/null || true
  }
  baseline_min_promotion_rate="$(baseline_target "promotion_rate")"
  baseline_max_demotion_rate="$(baseline_target "demotion_rate")"
  baseline_max_anti_pattern_fp_rate="$(baseline_target "anti_pattern_false_positive_rate")"
  baseline_max_rollback_rate="$(baseline_target "rollback_rate")"

  if [[ "$MIN_PROMOTION_RATE_CLI" -eq 0 && -n "$baseline_min_promotion_rate" ]]; then
    MIN_PROMOTION_RATE="$baseline_min_promotion_rate"
  fi
  if [[ "$MAX_DEMOTION_RATE_CLI" -eq 0 && -n "$baseline_max_demotion_rate" ]]; then
    MAX_DEMOTION_RATE="$baseline_max_demotion_rate"
  fi
  if [[ "$MAX_ANTI_PATTERN_FP_RATE_CLI" -eq 0 && -n "$baseline_max_anti_pattern_fp_rate" ]]; then
    MAX_ANTI_PATTERN_FP_RATE="$baseline_max_anti_pattern_fp_rate"
  fi
  if [[ "$MAX_ROLLBACK_RATE_CLI" -eq 0 && -n "$baseline_max_rollback_rate" ]]; then
    MAX_ROLLBACK_RATE="$baseline_max_rollback_rate"
  fi
fi

if [[ ! -f "$REPORT" ]]; then
  if [[ "$STRICT" -eq 1 ]]; then
    echo "Replay quality gate failed: missing report at $REPORT" >&2
    exit 1
  fi
  echo "Replay report not found at $REPORT, gate skipped (non-strict mode)."
  exit 0
fi

if [[ "$STRICT" -eq 1 ]]; then
  manifest_verified="$(jq -r '.source_manifest.verified // false' "$REPORT")"
  if [[ "$manifest_verified" != "true" ]]; then
    echo "Replay quality gate failed: source_manifest.verified must be true in strict mode." >&2
    exit 1
  fi
fi

read_metric_field() {
  local key="$1"
  local field="$2"
  jq -er --arg key "$key" --arg field "$field" '.metrics[$key][$field]' "$REPORT"
}

ensure_measured_metric() {
  local key="$1"
  local value
  local status
  value="$(read_metric_field "$key" "value")"
  status="$(read_metric_field "$key" "status")"
  if [[ "$value" == "null" ]]; then
    echo "Replay quality gate failed: metric '$key' has null value." >&2
    exit 1
  fi
  if [[ "$status" != "measured" ]]; then
    echo "Replay quality gate failed: metric '$key' status is '$status', expected 'measured'." >&2
    exit 1
  fi
  printf '%s\n' "$value"
}

compare() {
  local expression="$1"
  awk "BEGIN { exit(!($expression)) }"
}

success_rate_delta="$(ensure_measured_metric "replay_success_rate_delta")"
token_delta="$(ensure_measured_metric "replay_token_delta")"
latency_delta_ms="$(ensure_measured_metric "replay_latency_delta_ms")"
failure_dist_delta="$(ensure_measured_metric "replay_failure_distribution_delta")"
token_estimation_error_ratio_p95="$(ensure_measured_metric "token_estimation_error_ratio_p95")"
# Lifecycle metrics may be no_data when no candidate transitions exist (e.g. CI replay).
# Only skip gate checks when the report explicitly says status=no_data.
# Missing metrics or malformed reports still fail the gate.
read_lifecycle_metric() {
  local key="$1"
  local value status
  value="$(jq -r --arg key "$key" '.metrics[$key].value // "null"' "$REPORT")"
  status="$(jq -r --arg key "$key" '.metrics[$key].status // "missing"' "$REPORT")"
  if [[ "$status" == "no_data" ]]; then
    echo "null"
  elif [[ "$status" == "measured" && "$value" != "null" ]]; then
    echo "$value"
  else
    echo "Replay quality gate failed: metric '$key' has status='$status', value='$value' (expected measured or no_data)." >&2
    exit 1
  fi
}
promotion_rate="$(read_lifecycle_metric "promotion_rate")"
demotion_rate="$(read_lifecycle_metric "demotion_rate")"
rollback_rate="$(read_lifecycle_metric "rollback_rate")"
anti_pattern_fp_rate_weighted="$(read_metric_field "anti_pattern_gate_false_positive_rate_weighted" "value" 2>/dev/null || echo "null")"
anti_pattern_fp_rate_weighted_status="$(read_metric_field "anti_pattern_gate_false_positive_rate_weighted" "status" 2>/dev/null || echo "pending")"
anti_pattern_fp_rate_max="$(read_metric_field "anti_pattern_gate_false_positive_rate_max" "value" 2>/dev/null || echo "null")"
anti_pattern_fp_rate_max_status="$(read_metric_field "anti_pattern_gate_false_positive_rate_max" "status" 2>/dev/null || echo "pending")"
canonical_anti_pattern_fp_rate="$(read_metric_field "anti_pattern_false_positive_rate" "value" 2>/dev/null || echo "null")"
canonical_anti_pattern_fp_status="$(read_metric_field "anti_pattern_false_positive_rate" "status" 2>/dev/null || echo "pending")"

if ! compare "$success_rate_delta >= $MIN_SUCCESS_RATE_DELTA"; then
  echo "Replay quality gate failed: replay_success_rate_delta=$success_rate_delta < $MIN_SUCCESS_RATE_DELTA" >&2
  exit 1
fi
if ! compare "$token_delta <= $MAX_TOKEN_DELTA"; then
  echo "Replay quality gate failed: replay_token_delta=$token_delta > $MAX_TOKEN_DELTA" >&2
  exit 1
fi
if ! compare "$latency_delta_ms <= $MAX_LATENCY_DELTA_MS"; then
  if [[ "$FAIL_ON_LATENCY" == "true" ]]; then
    echo "Replay quality gate failed: replay_latency_delta_ms=$latency_delta_ms > $MAX_LATENCY_DELTA_MS" >&2
    exit 1
  fi
  echo "Replay quality gate warning: replay_latency_delta_ms=$latency_delta_ms > $MAX_LATENCY_DELTA_MS (non-blocking)." >&2
fi
if ! compare "$failure_dist_delta <= $MAX_FAILURE_DISTRIBUTION_DELTA"; then
  echo "Replay quality gate failed: replay_failure_distribution_delta=$failure_dist_delta > $MAX_FAILURE_DISTRIBUTION_DELTA" >&2
  exit 1
fi
if ! compare "$token_estimation_error_ratio_p95 <= $MAX_TOKEN_ESTIMATION_ERROR_RATIO"; then
  echo "Replay quality gate failed: token_estimation_error_ratio_p95=$token_estimation_error_ratio_p95 > $MAX_TOKEN_ESTIMATION_ERROR_RATIO" >&2
  exit 1
fi
if [[ "$promotion_rate" != "null" ]]; then
  if ! compare "$promotion_rate >= $MIN_PROMOTION_RATE"; then
    echo "Replay quality gate failed: promotion_rate=$promotion_rate < $MIN_PROMOTION_RATE" >&2
    exit 1
  fi
fi
if [[ "$demotion_rate" != "null" ]]; then
  if ! compare "$demotion_rate <= $MAX_DEMOTION_RATE"; then
    echo "Replay quality gate failed: demotion_rate=$demotion_rate > $MAX_DEMOTION_RATE" >&2
    exit 1
  fi
fi
if [[ "$rollback_rate" != "null" ]]; then
  if ! compare "$rollback_rate <= $MAX_ROLLBACK_RATE"; then
    echo "Replay quality gate failed: rollback_rate=$rollback_rate > $MAX_ROLLBACK_RATE" >&2
    exit 1
  fi
fi
if [[ "$ENFORCE_ANTI_PATTERN_FP_RATE" == "true" ]]; then
  effective_anti_pattern_fp_rate="null"
  if [[ "$canonical_anti_pattern_fp_status" == "measured" && "$canonical_anti_pattern_fp_rate" != "null" ]]; then
    effective_anti_pattern_fp_rate="$canonical_anti_pattern_fp_rate"
  elif [[ "$anti_pattern_fp_rate_weighted_status" == "measured" && "$anti_pattern_fp_rate_weighted" != "null" ]]; then
    effective_anti_pattern_fp_rate="$anti_pattern_fp_rate_weighted"
  fi
  if [[ "$effective_anti_pattern_fp_rate" == "null" ]]; then
    echo "Replay quality gate failed: anti_pattern_false_positive_rate is not measured." >&2
    exit 1
  fi
  if ! compare "$effective_anti_pattern_fp_rate <= $MAX_ANTI_PATTERN_FP_RATE"; then
    echo "Replay quality gate failed: anti_pattern_false_positive_rate=$effective_anti_pattern_fp_rate > $MAX_ANTI_PATTERN_FP_RATE" >&2
    exit 1
  fi
fi

echo "Replay quality gate passed:"
echo "  replay_success_rate_delta=$success_rate_delta (min $MIN_SUCCESS_RATE_DELTA)"
echo "  replay_token_delta=$token_delta (max $MAX_TOKEN_DELTA)"
echo "  replay_latency_delta_ms=$latency_delta_ms (max $MAX_LATENCY_DELTA_MS)"
echo "  replay_failure_distribution_delta=$failure_dist_delta (max $MAX_FAILURE_DISTRIBUTION_DELTA)"
echo "  token_estimation_error_ratio_p95=$token_estimation_error_ratio_p95 (max $MAX_TOKEN_ESTIMATION_ERROR_RATIO)"
if [[ "$promotion_rate" != "null" ]]; then
  echo "  promotion_rate=$promotion_rate (min $MIN_PROMOTION_RATE)"
else
  echo "  promotion_rate=no_data (skipped — no candidate transitions)"
fi
if [[ "$demotion_rate" != "null" ]]; then
  echo "  demotion_rate=$demotion_rate (max $MAX_DEMOTION_RATE)"
else
  echo "  demotion_rate=no_data (skipped)"
fi
if [[ "$rollback_rate" != "null" ]]; then
  echo "  rollback_rate=$rollback_rate (max $MAX_ROLLBACK_RATE)"
else
  echo "  rollback_rate=no_data (skipped)"
fi
if [[ "$canonical_anti_pattern_fp_status" == "measured" && "$canonical_anti_pattern_fp_rate" != "null" ]]; then
  echo "  anti_pattern_false_positive_rate=$canonical_anti_pattern_fp_rate (max $MAX_ANTI_PATTERN_FP_RATE)"
fi
if [[ "$anti_pattern_fp_rate_weighted_status" == "measured" && "$anti_pattern_fp_rate_weighted" != "null" ]]; then
  echo "  anti_pattern_gate_false_positive_rate_weighted=$anti_pattern_fp_rate_weighted (max $MAX_ANTI_PATTERN_FP_RATE)"
fi
if [[ "$anti_pattern_fp_rate_max_status" == "measured" && "$anti_pattern_fp_rate_max" != "null" ]]; then
  echo "  anti_pattern_gate_false_positive_rate_max=$anti_pattern_fp_rate_max (max $MAX_ANTI_PATTERN_FP_RATE)"
fi
