#!/usr/bin/env bash
set -euo pipefail

# Exports a summary JSON from the injection-audit.jsonl log.
# Output: .aceclaw/metrics/continuous-learning/injection-audit-summary.json
#
# The summary provides learning_hit_rate and diagnostic counts so that
# the baseline collector can auto-read it instead of requiring manual
# --metric overrides.

PROJECT_ROOT="$(pwd)"
OUTPUT=""
AUDIT_PATH=""

usage() {
  cat <<USAGE
Usage: ./scripts/export-injection-audit-summary.sh [options]

Options:
  --project-root <path>   Project root (default: cwd).
  --audit-path <path>     Path to injection-audit.jsonl
                          (default: \$PROJECT_ROOT/.aceclaw/memory/injection-audit.jsonl)
  --output <path>         Output summary JSON path
                          (default: \$PROJECT_ROOT/.aceclaw/metrics/continuous-learning/injection-audit-summary.json)
  --help                  Show this help.
USAGE
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --project-root)
      PROJECT_ROOT="$2"
      shift 2
      ;;
    --audit-path)
      AUDIT_PATH="$2"
      shift 2
      ;;
    --output)
      OUTPUT="$2"
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

if [[ -z "$AUDIT_PATH" ]]; then
  AUDIT_PATH="$PROJECT_ROOT/.aceclaw/memory/injection-audit.jsonl"
fi
if [[ -z "$OUTPUT" ]]; then
  OUTPUT="$PROJECT_ROOT/.aceclaw/metrics/continuous-learning/injection-audit-summary.json"
fi

if ! command -v jq >/dev/null 2>&1; then
  echo "export-injection-audit-summary requires jq but it was not found in PATH." >&2
  exit 1
fi

if [[ ! -f "$AUDIT_PATH" ]]; then
  echo "Injection audit log not found: $AUDIT_PATH" >&2
  echo "No summary to export." >&2
  exit 0
fi

mkdir -p "$(dirname "$OUTPUT")"

if ! branch_name="$(cd "$PROJECT_ROOT" && git rev-parse --abbrev-ref HEAD 2>/dev/null)"; then
  branch_name="unknown"
fi
if ! commit_sha="$(cd "$PROJECT_ROOT" && git rev-parse --short HEAD 2>/dev/null)"; then
  commit_sha="unknown"
fi
collected_at="$(date -u +%Y-%m-%dT%H:%M:%SZ)"

jq -s \
  --arg collected_at "$collected_at" \
  --arg branch "$branch_name" \
  --arg commit "$commit_sha" \
  --arg audit_path "$AUDIT_PATH" \
  '
  (map(select(.type == "injection")) | length) as $injections
  | (map(select(.type == "outcome"))) as $outcomes
  | ($outcomes | length) as $total_outcomes
  | ([$outcomes[] | select(.data.success == true)] | length) as $successful
  | ([map(select(.type == "injection")) | .[] | (.data.candidatesInjected // 0)] | add // 0) as $total_candidates
  | (if $total_outcomes > 0 then ($successful / $total_outcomes) else null end) as $hit_rate
  | (if $hit_rate != null then "measured" else "pending_instrumentation" end) as $status
  | {
      metadata: {
        collected_at: $collected_at,
        repo: "AceClaw",
        branch: $branch,
        commit: $commit,
        source: $audit_path,
        collector_version: "injection-summary-v1"
      },
      metrics: {
        learning_hit_rate: {
          value: $hit_rate,
          target: 0.40,
          status: $status,
          sample_size: $total_outcomes
        }
      },
      diagnostics: {
        total_injections: $injections,
        total_outcomes: $total_outcomes,
        successful_outcomes: $successful,
        total_candidates_injected: $total_candidates
      }
    }
  ' "$AUDIT_PATH" > "$OUTPUT"

echo "Injection audit summary written to: $OUTPUT"
