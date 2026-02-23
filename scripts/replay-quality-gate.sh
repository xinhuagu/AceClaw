#!/usr/bin/env bash
set -euo pipefail

REPORT=""
STRICT=0

MIN_SUCCESS_RATE_DELTA="0.00"
MAX_TOKEN_DELTA="200.00"
MAX_LATENCY_DELTA_MS="500.00"
MAX_FAILURE_DISTRIBUTION_DELTA="0.15"
MAX_TOKEN_ESTIMATION_ERROR_RATIO="0.25"
FAIL_ON_LATENCY="false"

usage() {
  cat <<USAGE
Usage: ./scripts/replay-quality-gate.sh [options]

Options:
  --report <path>                        Replay report JSON path.
  --strict                               Fail when report is missing.
  --min-success-rate-delta <number>      Default: 0.00
  --max-token-delta <number>             Default: 200.00
  --max-latency-delta-ms <number>        Default: 500.00
  --fail-on-latency <true|false>         Default: false
  --max-failure-dist-delta <number>      Default: 0.15
  --max-token-estimation-error-ratio <number>  Default: 0.25
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

if ! command -v jq >/dev/null 2>&1; then
  echo "replay-quality-gate requires jq but it was not found in PATH." >&2
  exit 1
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
token_estimation_error_ratio_max="$(ensure_measured_metric "token_estimation_error_ratio_max")"

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
if ! compare "$token_estimation_error_ratio_max <= $MAX_TOKEN_ESTIMATION_ERROR_RATIO"; then
  echo "Replay quality gate failed: token_estimation_error_ratio_max=$token_estimation_error_ratio_max > $MAX_TOKEN_ESTIMATION_ERROR_RATIO" >&2
  exit 1
fi

echo "Replay quality gate passed:"
echo "  replay_success_rate_delta=$success_rate_delta (min $MIN_SUCCESS_RATE_DELTA)"
echo "  replay_token_delta=$token_delta (max $MAX_TOKEN_DELTA)"
echo "  replay_latency_delta_ms=$latency_delta_ms (max $MAX_LATENCY_DELTA_MS)"
echo "  replay_failure_distribution_delta=$failure_dist_delta (max $MAX_FAILURE_DISTRIBUTION_DELTA)"
echo "  token_estimation_error_ratio_max=$token_estimation_error_ratio_max (max $MAX_TOKEN_ESTIMATION_ERROR_RATIO)"
