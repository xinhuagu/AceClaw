#!/usr/bin/env bash
set -euo pipefail

INPUT=""
MIN_PER_CATEGORY=3

usage() {
  cat <<USAGE
Usage: ./scripts/validate-replay-suite.sh [options]

Options:
  --input <path>             Replay prompts suite JSON path.
  --min-per-category <n>     Minimum cases for each required category (default: 3).
  --help                     Show this help.

Required case fields:
- id
- prompt
- category: one of [error_recovery, user_correction, workflow_reuse, adversarial]
- owner
- risk_level: one of [low, medium, high]
- labels: non-empty array

Optional case fields:
- timeout_ms: positive integer per-case timeout override
USAGE
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --input)
      INPUT="$2"
      shift 2
      ;;
    --min-per-category)
      MIN_PER_CATEGORY="$2"
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

if [[ -z "$INPUT" ]]; then
  INPUT="docs/reports/samples/replay-prompts-sample.json"
fi

if ! command -v jq >/dev/null 2>&1; then
  echo "validate-replay-suite requires jq but it was not found in PATH." >&2
  exit 1
fi

if [[ ! -f "$INPUT" ]]; then
  echo "Replay suite input not found: $INPUT" >&2
  exit 1
fi

if ! [[ "$MIN_PER_CATEGORY" =~ ^[0-9]+$ ]]; then
  echo "--min-per-category must be a non-negative integer" >&2
  exit 1
fi

schema_expr='
  .cases and (.cases | type == "array") and (.cases | length > 0)
  and all(.cases[];
    (.id | type == "string") and (.id | length > 0)
    and (.prompt | type == "string") and (.prompt | length > 0)
    and (((.category // "") as $c | (["error_recovery","user_correction","workflow_reuse","adversarial"] | index($c))) != null)
    and (.owner | type == "string") and (.owner | length > 0)
    and (((.risk_level // "") as $r | (["low","medium","high"] | index($r))) != null)
    and (.labels | type == "array") and (.labels | length > 0)
    and ((.timeout_ms == null) or (((.timeout_ms | type) == "number") and (.timeout_ms > 0)))
  )
'

if ! jq -e "$schema_expr" "$INPUT" >/dev/null; then
  echo "Replay suite schema validation failed: $INPUT" >&2
  exit 1
fi

categories=(error_recovery user_correction workflow_reuse adversarial)
for cat in "${categories[@]}"; do
  count="$(jq -r --arg c "$cat" '[.cases[] | select(.category == $c)] | length' "$INPUT")"
  if (( count < MIN_PER_CATEGORY )); then
    echo "Replay suite coverage failed: category '$cat' has $count cases, requires >= $MIN_PER_CATEGORY" >&2
    exit 1
  fi
done

total="$(jq -r '.cases | length' "$INPUT")"
echo "Replay suite validation passed: total_cases=$total, min_per_category=$MIN_PER_CATEGORY"
