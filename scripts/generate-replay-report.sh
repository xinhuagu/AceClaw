#!/usr/bin/env bash
set -euo pipefail

INPUT=""
OUTPUT=""
MANIFEST=""
ANTI_PATTERN_FEEDBACK=""
CANDIDATE_TRANSITIONS=""

usage() {
  cat <<USAGE
Usage: ./scripts/generate-replay-report.sh --input <cases.json> [--output <report.json>] [--manifest <manifest.json>] [--anti-pattern-feedback <feedback.json>] [--candidate-transitions <transitions.jsonl>]

Input schema:
{
  "cases": [
    {
      "id": "case-1",
      "off": {
        "success": true,
        "tokens": 120,
        "latency_ms": 820,
        "failure_type": null,
        "estimated_tokens": 128,
        "provider_tokens": 120,
        "estimation_error_ratio": 0.0667
      },
      "on":  {
        "success": true,
        "tokens": 110,
        "latency_ms": 790,
        "failure_type": null,
        "estimated_tokens": 117,
        "provider_tokens": 110,
        "estimation_error_ratio": 0.0636
      }
    }
  ]
}

failure_type (when success=false) accepted values:
- permission
- timeout
- tool-error
- other

Options:
  --input <path>   Replay A/B case results JSON.
  --output <path>  Output report path
                   (default: .aceclaw/metrics/continuous-learning/replay-latest.json)
  --manifest <path> Replay cases manifest JSON with cases_sha256.
  --anti-pattern-feedback <path> Anti-pattern gate feedback JSON
                   (default: .aceclaw/metrics/continuous-learning/anti-pattern-gate-feedback.json)
  --candidate-transitions <path> Candidate transitions JSONL
                   (default: .aceclaw/memory/candidate-transitions.jsonl)
  --help           Show this help.
USAGE
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --input)
      INPUT="$2"
      shift 2
      ;;
    --output)
      OUTPUT="$2"
      shift 2
      ;;
    --manifest)
      MANIFEST="$2"
      shift 2
      ;;
    --anti-pattern-feedback)
      ANTI_PATTERN_FEEDBACK="$2"
      shift 2
      ;;
    --candidate-transitions)
      CANDIDATE_TRANSITIONS="$2"
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
  echo "Missing required --input argument." >&2
  usage
  exit 1
fi

if [[ -z "$OUTPUT" ]]; then
  OUTPUT=".aceclaw/metrics/continuous-learning/replay-latest.json"
fi

if [[ -z "$MANIFEST" ]]; then
  MANIFEST=".aceclaw/metrics/continuous-learning/replay-cases.manifest.json"
fi
if [[ -z "$ANTI_PATTERN_FEEDBACK" ]]; then
  ANTI_PATTERN_FEEDBACK=".aceclaw/metrics/continuous-learning/anti-pattern-gate-feedback.json"
fi
if [[ -z "$CANDIDATE_TRANSITIONS" ]]; then
  CANDIDATE_TRANSITIONS=".aceclaw/memory/candidate-transitions.jsonl"
fi

if ! command -v jq >/dev/null 2>&1; then
  echo "generate-replay-report requires jq but it was not found in PATH." >&2
  exit 1
fi

if [[ ! -f "$INPUT" ]]; then
  echo "Replay input file does not exist: $INPUT" >&2
  exit 1
fi

sha256_file() {
  local file="$1"
  if command -v shasum >/dev/null 2>&1; then
    shasum -a 256 "$file" | awk '{print $1}'
    return 0
  fi
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$file" | awk '{print $1}'
    return 0
  fi
  if command -v openssl >/dev/null 2>&1; then
    openssl dgst -sha256 "$file" | awk '{print $NF}'
    return 0
  fi
  echo "No SHA-256 tool found (shasum/sha256sum/openssl)." >&2
  exit 1
}

manifest_expected_sha=""
manifest_actual_sha=""
manifest_verified="false"
manifest_path_json="null"
if [[ -f "$MANIFEST" ]]; then
  manifest_expected_sha="$(jq -er '.cases_sha256 // empty' "$MANIFEST")"
  if [[ -z "$manifest_expected_sha" ]]; then
    echo "Replay manifest missing cases_sha256: $MANIFEST" >&2
    exit 1
  fi
  manifest_actual_sha="$(sha256_file "$INPUT")"
  if [[ "$manifest_expected_sha" != "$manifest_actual_sha" ]]; then
    echo "Replay manifest checksum mismatch: expected=$manifest_expected_sha actual=$manifest_actual_sha" >&2
    exit 1
  fi
  manifest_verified="true"
  manifest_path_json="$MANIFEST"
fi

validate_expr='
  .cases and (.cases | type == "array") and (.cases | length > 0)
  and all(.cases[];
    (.off.success | type == "boolean")
    and (.on.success | type == "boolean")
    and ((.off.tokens | type) == "number")
    and ((.on.tokens | type) == "number")
    and ((.off.latency_ms | type) == "number")
    and ((.on.latency_ms | type) == "number")
    and (
      .off.success
      or (((.off.failure_type // "other") as $ft
        | (["permission", "timeout", "tool-error", "other"] | index($ft))) != null)
    )
    and (
      .on.success
      or (((.on.failure_type // "other") as $ft
        | (["permission", "timeout", "tool-error", "other"] | index($ft))) != null)
    )
    and ((.off.estimated_tokens // 0 | type) == "number")
    and ((.on.estimated_tokens // 0 | type) == "number")
    and ((.off.provider_tokens == null) or ((.off.provider_tokens | type) == "number"))
    and ((.on.provider_tokens == null) or ((.on.provider_tokens | type) == "number"))
    and ((.off.estimation_error_ratio == null) or ((.off.estimation_error_ratio | type) == "number"))
    and ((.on.estimation_error_ratio == null) or ((.on.estimation_error_ratio | type) == "number"))
  )
'

if ! jq -e "$validate_expr" "$INPUT" >/dev/null; then
  echo "Invalid replay input schema or values in: $INPUT" >&2
  exit 1
fi

mkdir -p "$(dirname "$OUTPUT")"

if ! branch_name="$(git rev-parse --abbrev-ref HEAD 2>/dev/null)"; then
  branch_name="unknown"
fi
if ! commit_sha="$(git rev-parse --short HEAD 2>/dev/null)"; then
  commit_sha="unknown"
fi
collected_at="$(date -u +%Y-%m-%dT%H:%M:%SZ)"

jq \
  --arg collected_at "$collected_at" \
  --arg branch "$branch_name" \
  --arg commit "$commit_sha" \
  --arg input_path "$INPUT" \
  --arg manifest_path "$manifest_path_json" \
  --arg manifest_expected_sha "$manifest_expected_sha" \
  --arg manifest_actual_sha "$manifest_actual_sha" \
  --arg manifest_verified "$manifest_verified" \
  '
  def bucket(ft):
    if ft == "permission" then "permission"
    elif ft == "timeout" then "timeout"
    elif ft == "tool-error" then "tool-error"
    else "other"
    end;

  def failures(mode):
    [.cases[] | .[mode] | select(.success == false) | bucket((.failure_type // "other"))];

  def dist(mode):
    (failures(mode)) as $f
    | ($f | length) as $n
    | if $n == 0 then
        {"permission": 0.0, "timeout": 0.0, "tool-error": 0.0, "other": 0.0}
      else
        {
          "permission": (($f | map(select(. == "permission")) | length) / $n),
          "timeout": (($f | map(select(. == "timeout")) | length) / $n),
          "tool-error": (($f | map(select(. == "tool-error")) | length) / $n),
          "other": (($f | map(select(. == "other")) | length) / $n)
        }
      end;

  def token_error_values_raw:
    [
      .cases[] | .off.estimation_error_ratio, .on.estimation_error_ratio
      | select(. != null)
    ];

  def token_error_pairs:
    [
      .cases[] | .off, .on
      | {estimated: .estimated_tokens, provider: (.provider_tokens // .tokens)}
      | select((.estimated | type) == "number" and (.provider | type) == "number")
      | select(.estimated > 0 and .provider > 0)
    ];

  (.cases | length) as $n
  | (([.cases[] | .off.success | if . then 1 else 0 end] | add) / $n) as $success_off
  | (([.cases[] | .on.success | if . then 1 else 0 end] | add) / $n) as $success_on
  | (([.cases[] | .off.tokens] | add) / $n) as $tokens_off
  | (([.cases[] | .on.tokens] | add) / $n) as $tokens_on
  | (([.cases[] | .off.latency_ms] | add) / $n) as $latency_off
  | (([.cases[] | .on.latency_ms] | add) / $n) as $latency_on
  | (dist("off")) as $dist_off
  | (dist("on")) as $dist_on
  | (token_error_values_raw) as $token_err_values_raw
  | ($token_err_values_raw | length) as $token_err_count_raw
  | (if $token_err_count_raw > 0 then ($token_err_values_raw | max) else null end) as $token_err_max_raw
  | (if $token_err_count_raw > 0 then (($token_err_values_raw | add) / $token_err_count_raw) else null end) as $token_err_avg_raw
  | (token_error_pairs) as $token_err_pairs
  | ($token_err_pairs | length) as $token_err_count
  | (if $token_err_count > 0
      then (([$token_err_pairs[] | (.provider / .estimated)] | add) / $token_err_count)
      else 1.0
      end) as $token_err_calibration
  | (if $token_err_count > 0
      then ([$token_err_pairs[]
        | (((.estimated * $token_err_calibration) - .provider) / .provider
          | if . < 0 then -. else . end)] | max)
      else 0.0
      end) as $token_err_max
  | (if $token_err_count > 0
      then ([$token_err_pairs[]
        | (((.estimated * $token_err_calibration) - .provider) / .provider
          | if . < 0 then -. else . end)] | add) / $token_err_count
      else 0.0
      end) as $token_err_avg
  | ((($dist_on.permission - $dist_off.permission) | if . < 0 then -. else . end)
    + (($dist_on.timeout - $dist_off.timeout) | if . < 0 then -. else . end)
    + (($dist_on["tool-error"] - $dist_off["tool-error"]) | if . < 0 then -. else . end)
    + (($dist_on.other - $dist_off.other) | if . < 0 then -. else . end)) as $l1
  | {
      metadata: {
        collected_at: $collected_at,
        repo: "AceClaw",
        branch: $branch,
        commit: $commit,
        collector_version: "replay-v1"
      },
      collection: {
        input_path: $input_path,
        total_cases: $n,
        mode_off: "learning=off",
        mode_on: "learning=on"
      },
      source_manifest: {
        path: (if $manifest_path == "null" then null else $manifest_path end),
        expected_cases_sha256: (if $manifest_expected_sha == "" then null else $manifest_expected_sha end),
        actual_cases_sha256: (if $manifest_actual_sha == "" then null else $manifest_actual_sha end),
        verified: ($manifest_verified == "true")
      },
      metrics: {
        replay_success_rate_delta: {
          value: ($success_on - $success_off),
          target: 0.00,
          status: "measured",
          sample_size: $n
        },
        replay_token_delta: {
          value: ($tokens_on - $tokens_off),
          target: 200.00,
          status: "measured",
          sample_size: $n
        },
        replay_latency_delta_ms: {
          value: ($latency_on - $latency_off),
          target: 500.00,
          status: "measured",
          sample_size: $n
        },
        replay_failure_distribution_delta: {
          value: $l1,
          target: 0.15,
          status: "measured",
          sample_size: $n
        },
        token_estimation_error_ratio_max: {
          value: $token_err_max,
          target: 0.25,
          status: "measured",
          sample_size: $token_err_count
        },
        first_try_success_rate_delta: {
          value: null,
          target: 0.00,
          status: "pending_instrumentation"
        },
        retry_count_per_task_delta: {
          value: null,
          target: 0.00,
          status: "pending_instrumentation"
        },
        anti_pattern_gate_false_positive_rate_weighted: {
          value: null,
          target: 0.50,
          status: "pending"
        },
        anti_pattern_gate_false_positive_rate_max: {
          value: null,
          target: 0.50,
          status: "pending"
        }
      },
      diagnostics: {
        success_rate_off: $success_off,
        success_rate_on: $success_on,
        avg_tokens_off: $tokens_off,
        avg_tokens_on: $tokens_on,
        avg_latency_ms_off: $latency_off,
        avg_latency_ms_on: $latency_on,
        token_estimation_calibration_factor: $token_err_calibration,
        token_estimation_error_ratio_samples: $token_err_count,
        token_estimation_error_ratio_expected_samples: ($n * 2),
        token_estimation_error_ratio_avg: $token_err_avg,
        token_estimation_error_ratio_max: $token_err_max,
        token_estimation_error_fallback_used: ($token_err_count == 0),
        token_estimation_error_ratio_raw_avg: $token_err_avg_raw,
        token_estimation_error_ratio_raw_max: $token_err_max_raw,
        failure_distribution_off: $dist_off,
        failure_distribution_on: $dist_on
      }
    }
  ' "$INPUT" > "$OUTPUT"

if [[ -f "$ANTI_PATTERN_FEEDBACK" ]]; then
  ap_rules_total="$(jq -er 'length' "$ANTI_PATTERN_FEEDBACK" 2>/dev/null || echo 0)"
  ap_blocked_total="$(jq -er '[.[] | (.blockedCount // 0)] | add // 0' "$ANTI_PATTERN_FEEDBACK" 2>/dev/null || echo 0)"
  ap_fp_total="$(jq -er '[.[] | (.falsePositiveCount // 0)] | add // 0' "$ANTI_PATTERN_FEEDBACK" 2>/dev/null || echo 0)"
  ap_rate_weighted="$(jq -nr --argjson b "$ap_blocked_total" --argjson fp "$ap_fp_total" 'if $b > 0 then ($fp / $b) else null end')"
  ap_rate_max="$(jq -r '[.[] | select((.blockedCount // 0) > 0) | ((.falsePositiveCount // 0) / .blockedCount)] | max // null' "$ANTI_PATTERN_FEEDBACK" 2>/dev/null || echo "null")"
  ap_status="pending"
  if [[ "$ap_blocked_total" -gt 0 ]]; then
    ap_status="measured"
  fi
  tmp_report="${OUTPUT}.tmp-ap"
  jq \
    --argjson ap_rules_total "$ap_rules_total" \
    --argjson ap_blocked_total "$ap_blocked_total" \
    --argjson ap_fp_total "$ap_fp_total" \
    --argjson ap_rate_weighted "$ap_rate_weighted" \
    --argjson ap_rate_max "$ap_rate_max" \
    --arg ap_status "$ap_status" \
    '
    .metrics.anti_pattern_gate_false_positive_rate_weighted = {
      value: $ap_rate_weighted,
      target: 0.50,
      status: $ap_status
    }
    | .metrics.anti_pattern_gate_false_positive_rate_max = {
      value: $ap_rate_max,
      target: 0.50,
      status: $ap_status
    }
    | .diagnostics.anti_pattern_gate_rules_total = $ap_rules_total
    | .diagnostics.anti_pattern_gate_blocked_total = $ap_blocked_total
    | .diagnostics.anti_pattern_gate_false_positive_total = $ap_fp_total
    ' "$OUTPUT" > "$tmp_report"
  mv "$tmp_report" "$OUTPUT"
fi

ap_rate_weighted_metric="0.0"
ap_metric_status="measured"
if [[ -f "$ANTI_PATTERN_FEEDBACK" ]]; then
  ap_rate_weighted_metric="$(jq -er '.metrics.anti_pattern_gate_false_positive_rate_weighted.value // 0.0' "$OUTPUT" 2>/dev/null || echo "0.0")"
  ap_metric_status="$(jq -er '.metrics.anti_pattern_gate_false_positive_rate_weighted.status // "measured"' "$OUTPUT" 2>/dev/null || echo "measured")"
fi

transition_source="missing"
promotion_count=0
demotion_count=0
rollback_count=0
transition_total=0
promotion_rate=0.0
demotion_rate=0.0
rollback_rate=0.0

read_json_number_or_default() {
  local jq_expr="$1"
  local input_file="$2"
  local fallback="$3"
  local value
  value="$(jq -s "$jq_expr" "$input_file" 2>/dev/null | tail -n1 || true)"
  if [[ -z "$value" || ! "$value" =~ ^-?[0-9]+([.][0-9]+)?$ ]]; then
    value="$fallback"
  fi
  printf '%s' "$value"
}

if [[ -f "$CANDIDATE_TRANSITIONS" ]]; then
  transition_source="candidate-transitions"
  transition_total="$(read_json_number_or_default 'length' "$CANDIDATE_TRANSITIONS" 0)"
  promotion_count="$(read_json_number_or_default '[.[] | select((.toState // "") == "PROMOTED")] | length' "$CANDIDATE_TRANSITIONS" 0)"
  demotion_count="$(read_json_number_or_default '[.[] | select((.toState // "") == "DEMOTED")] | length' "$CANDIDATE_TRANSITIONS" 0)"
  rollback_count="$(read_json_number_or_default '[.[] | select(((.reasonCode // "") == "MANUAL_ROLLBACK") or ((.reasonCode // "") == "ANTI_PATTERN_FALSE_POSITIVE_ROLLBACK"))] | length' "$CANDIDATE_TRANSITIONS" 0)"
  promotion_rate="$(jq -ner --argjson p "$promotion_count" --argjson t "$transition_total" 'if $t > 0 then ($p / $t) else 0.0 end')"
  demotion_rate="$(jq -ner --argjson d "$demotion_count" --argjson t "$transition_total" 'if $t > 0 then ($d / $t) else 0.0 end')"
  rollback_rate="$(jq -ner --argjson r "$rollback_count" --argjson p "$promotion_count" 'if $p > 0 then ($r / $p) else 0.0 end')"
  # promotion_precision: promoted that stayed healthy / total promoted
  promotion_precision="$(jq -ner --argjson p "$promotion_count" --argjson d "$demotion_count" --argjson r "$rollback_count" \
    'if $p > 0 then (($p - $d - $r) / $p | if . < 0 then 0.0 else . end) else null end')"
  # false_learning_rate: promoted later demoted or rolled back / total promoted
  false_learning_rate="$(jq -ner --argjson d "$demotion_count" --argjson r "$rollback_count" --argjson p "$promotion_count" \
    'if $p > 0 then (($d + $r) / $p) else null end')"
fi

promotion_precision="${promotion_precision:-null}"
false_learning_rate="${false_learning_rate:-null}"

tmp_report="${OUTPUT}.tmp-lifecycle"
jq \
  --argjson promotion_rate "$promotion_rate" \
  --argjson demotion_rate "$demotion_rate" \
  --argjson rollback_rate "$rollback_rate" \
  --argjson promotion_precision "$promotion_precision" \
  --argjson false_learning_rate "$false_learning_rate" \
  --argjson promotion_count_val "$promotion_count" \
  --argjson ap_rate "$ap_rate_weighted_metric" \
  --arg ap_status "$ap_metric_status" \
  --arg transition_source "$transition_source" \
  --argjson promotion_count "$promotion_count" \
  --argjson demotion_count "$demotion_count" \
  --argjson rollback_count "$rollback_count" \
  --argjson transition_total "$transition_total" \
  '
  .metrics.promotion_rate = {
    value: $promotion_rate,
    target: 0.00,
    status: "measured"
  }
  | .metrics.demotion_rate = {
    value: $demotion_rate,
    target: 0.35,
    status: "measured"
  }
  | .metrics.promotion_precision = {
    value: $promotion_precision,
    target: 0.80,
    status: (if $promotion_precision == null then "pending_instrumentation" else "measured" end),
    sample_size: $promotion_count_val
  }
  | .metrics.false_learning_rate = {
    value: $false_learning_rate,
    target: 0.10,
    status: (if $false_learning_rate == null then "pending_instrumentation" else "measured" end),
    sample_size: $promotion_count_val
  }
  | .metrics.rollback_rate = {
    value: $rollback_rate,
    target: 0.20,
    status: "measured"
  }
  | .metrics.anti_pattern_false_positive_rate = {
    value: (if ($ap_rate == null) then 0.0 else $ap_rate end),
    target: 0.50,
    status: (if $ap_status == "" then "measured" else $ap_status end)
  }
  | .diagnostics.learning_lifecycle_source = $transition_source
  | .diagnostics.learning_lifecycle_transition_total = $transition_total
  | .diagnostics.learning_lifecycle_promotions = $promotion_count
  | .diagnostics.learning_lifecycle_demotions = $demotion_count
  | .diagnostics.learning_lifecycle_rollbacks = $rollback_count
  ' "$OUTPUT" > "$tmp_report"
mv "$tmp_report" "$OUTPUT"

echo "Replay report written to: $OUTPUT"
