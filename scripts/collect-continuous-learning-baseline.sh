#!/usr/bin/env bash
set -euo pipefail

RUN_TESTS=0
OUTPUT=""
PROJECT_ROOT="$(pwd)"

# repeatable: --metric key=value
METRIC_OVERRIDES=()

usage() {
  cat <<USAGE
Usage: ./scripts/collect-continuous-learning-baseline.sh [options]

Options:
  --run-tests                 Run focused self-learning test suites before emitting baseline.
  --output <path>             Output JSON file path.
  --project-root <path>       Project root (default: current working directory).
  --metric <key=value>        Override a metric value (repeatable).
  --help                      Show this help.
USAGE
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --run-tests)
      RUN_TESTS=1
      shift
      ;;
    --output)
      OUTPUT="$2"
      shift 2
      ;;
    --project-root)
      PROJECT_ROOT="$2"
      shift 2
      ;;
    --metric)
      METRIC_OVERRIDES+=("$2")
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

if [[ -z "$OUTPUT" ]]; then
  ts="$(date -u +%Y%m%dT%H%M%SZ)"
  OUTPUT="$PROJECT_ROOT/.aceclaw/metrics/continuous-learning/baseline-$ts.json"
fi

mkdir -p "$(dirname "$OUTPUT")"

target_for_key() {
  case "$1" in
    task_success_rate) echo "0.85" ;;
    first_try_success_rate) echo "0.70" ;;
    retry_count_per_task) echo "0.60" ;;
    tool_execution_success_rate) echo "0.95" ;;
    tool_error_rate) echo "0.05" ;;
    permission_block_rate) echo "0.05" ;;
    timeout_rate) echo "0.03" ;;
    learning_hit_rate) echo "0.40" ;;
    promotion_precision) echo "0.80" ;;
    false_learning_rate) echo "0.10" ;;
    time_to_promote_p50_hours) echo "72.0" ;;
    regression_rate_after_learning) echo "0.10" ;;
    auto_rollback_rate) echo "0.10" ;;
    replay_success_rate_delta) echo "0.00" ;;
    replay_token_delta) echo "0.00" ;;
    replay_latency_delta_ms) echo "0.00" ;;
    replay_failure_distribution_delta) echo "0.15" ;;
    promotion_rate) echo "0.00" ;;
    demotion_rate) echo "0.35" ;;
    anti_pattern_false_positive_rate) echo "0.50" ;;
    rollback_rate) echo "0.20" ;;
    *)
      echo "Unknown metric key: $1" >&2
      exit 1
      ;;
  esac
}

# Validate override format early.
for pair in "${METRIC_OVERRIDES[@]+${METRIC_OVERRIDES[@]}}"; do
  [[ -z "$pair" ]] && continue
  key="${pair%%=*}"
  val="${pair#*=}"
  if [[ -z "$key" || "$key" == "$val" ]]; then
    echo "Invalid --metric format: $pair (expected key=value)" >&2
    exit 1
  fi
  # Validate key exists
  target_for_key "$key" >/dev/null
done

find_override() {
  local key="$1"
  for pair in "${METRIC_OVERRIDES[@]+${METRIC_OVERRIDES[@]}}"; do
    [[ -z "$pair" ]] && continue
    local k="${pair%%=*}"
    local v="${pair#*=}"
    if [[ "$k" == "$key" ]]; then
      printf '%s' "$v"
      return 0
    fi
  done
  return 1
}

json_escape() {
  local s="$1"
  s="${s//\\/\\\\}"
  s="${s//\"/\\\"}"
  s="${s//$'\n'/ }"
  printf '%s' "$s"
}

as_json_value() {
  local raw="$1"
  if [[ "$raw" == "null" || "$raw" == "true" || "$raw" == "false" ]]; then
    printf '%s' "$raw"
  elif [[ "$raw" =~ ^-?[0-9]+([.][0-9]+)?$ ]]; then
    printf '%s' "$raw"
  else
    printf '"%s"' "$(json_escape "$raw")"
  fi
}

RUNTIME_METRICS_PATH="$PROJECT_ROOT/.aceclaw/metrics/continuous-learning/runtime-latest.json"
INJECTION_AUDIT_PATH="$PROJECT_ROOT/.aceclaw/memory/injection-audit.jsonl"

read_runtime_metric() {
  local key="$1"
  if [[ -f "$RUNTIME_METRICS_PATH" ]] && command -v jq >/dev/null 2>&1; then
    local status
    status="$(jq -r ".metrics.\"$key\".status // \"\"" "$RUNTIME_METRICS_PATH" 2>/dev/null)"
    if [[ "$status" == "measured" ]]; then
      jq -r ".metrics.\"$key\".value // \"null\"" "$RUNTIME_METRICS_PATH" 2>/dev/null
      return 0
    fi
  fi
  return 1
}

metric_json() {
  local key="$1"
  local target
  local val="null"
  local status="pending_instrumentation"
  local source=""

  target="$(target_for_key "$key")"

  # Priority: 1) manual override, 2) runtime-latest.json, 3) pending
  if override="$(find_override "$key")"; then
    val="$override"
    status="measured"
    source="manual_override"
  elif runtime_val="$(read_runtime_metric "$key")"; then
    val="$runtime_val"
    status="measured"
    source="runtime-latest.json"
  fi

  printf '    "%s": {"value": %s, "target": %s, "status": "%s", "source": "%s"}' \
    "$key" "$(as_json_value "$val")" "$target" "$status" "$source"
}

TESTS_STATUS="not_run"
TESTS_EXIT_CODE="null"
TESTS_COMMAND=""

if [[ "$RUN_TESTS" -eq 1 ]]; then
  TESTS_COMMAND="./gradlew :aceclaw-daemon:test :aceclaw-memory:test :aceclaw-core:test --tests dev.aceclaw.daemon.SelfLearningPipelineTest --tests dev.aceclaw.memory.StrategyRefinerTest --tests dev.aceclaw.core.agent.SkillRegistryTest --tests dev.aceclaw.core.agent.SkillContentResolverTest"

  set +e
  (cd "$PROJECT_ROOT" && eval "$TESTS_COMMAND")
  rc=$?
  set -e

  TESTS_EXIT_CODE="$rc"
  if [[ "$rc" -eq 0 ]]; then
    TESTS_STATUS="passed"
  else
    TESTS_STATUS="failed"
  fi
fi

if ! branch_name="$(cd "$PROJECT_ROOT" && git rev-parse --abbrev-ref HEAD 2>/dev/null)"; then
  branch_name="unknown"
fi
if ! commit_sha="$(cd "$PROJECT_ROOT" && git rev-parse --short HEAD 2>/dev/null)"; then
  commit_sha="unknown"
fi

collected_at="$(date -u +%Y-%m-%dT%H:%M:%SZ)"

metric_keys=(
  task_success_rate
  first_try_success_rate
  retry_count_per_task
  tool_execution_success_rate
  tool_error_rate
  permission_block_rate
  timeout_rate
  learning_hit_rate
  promotion_precision
  false_learning_rate
  time_to_promote_p50_hours
  regression_rate_after_learning
  auto_rollback_rate
  replay_success_rate_delta
  replay_token_delta
  replay_latency_delta_ms
  replay_failure_distribution_delta
  promotion_rate
  demotion_rate
  anti_pattern_false_positive_rate
  rollback_rate
)

{
  echo "{"
  echo "  \"metadata\": {"
  echo "    \"collected_at\": \"$collected_at\"," 
  echo "    \"repo\": \"AceClaw\"," 
  echo "    \"branch\": \"$(json_escape "$branch_name")\"," 
  echo "    \"commit\": \"$(json_escape "$commit_sha")\"," 
  echo "    \"collector_version\": \"v1\""
  echo "  },"
  echo "  \"collection\": {"
  echo "    \"run_tests\": $([[ "$RUN_TESTS" -eq 1 ]] && echo true || echo false),"
  echo "    \"tests_status\": \"$TESTS_STATUS\"," 
  echo "    \"tests_command\": \"$(json_escape "$TESTS_COMMAND")\"," 
  echo "    \"tests_exit_code\": $TESTS_EXIT_CODE"
  echo "  },"
  echo "  \"metrics\": {"

  last_index=$((${#metric_keys[@]} - 1))
  for i in "${!metric_keys[@]}"; do
    key="${metric_keys[$i]}"
    metric_json "$key"
    if [[ "$i" -lt "$last_index" ]]; then
      echo ","
    else
      echo
    fi
  done

  echo "  },"
  echo "  \"notes\": ["
  echo "    \"Core metrics are auto-read from runtime-latest.json when available.\","
  echo "    \"Use --metric key=value overrides only for debugging or missing data.\","
  echo "    \"Metrics without runtime data are emitted as pending_instrumentation.\""
  echo "  ]"
  echo "}"
} > "$OUTPUT"

echo "Baseline written to: $OUTPUT"

# Propagate test failure as script exit code when tests are requested.
if [[ "$RUN_TESTS" -eq 1 && "$TESTS_STATUS" == "failed" ]]; then
  exit 1
fi
