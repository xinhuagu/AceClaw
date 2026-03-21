#!/usr/bin/env bash
# Verifies preMergeCheck depends on both replayQualityGate and benchmarkScorecard.
set -euo pipefail

ERRORS=0

assert_depends() {
  local task="$1" dep="$2"
  if ./gradlew "$task" --dry-run 2>&1 | grep -q ":$dep"; then
    echo "PASS: $task depends on $dep"
  else
    echo "FAIL: $task does NOT depend on $dep" >&2
    ERRORS=$((ERRORS + 1))
  fi
}

echo "=== preMergeCheck dependency chain ==="
assert_depends "preMergeCheck" "replayQualityGate"
assert_depends "preMergeCheck" "benchmarkScorecard"
assert_depends "preMergeCheck" "continuousLearningSmoke"
assert_depends "preMergeCheck" "build"

echo ""
echo "=== benchmarkScorecard → generateReplayReport ==="
assert_depends "benchmarkScorecard" "generateReplayReport"

echo ""
echo "=== generateReplayReport → generateReplayCases ==="
assert_depends "generateReplayReport" "generateReplayCases"

echo ""
if [[ "$ERRORS" -gt 0 ]]; then
  echo "FAILED: $ERRORS assertion(s) failed"
  exit 1
else
  echo "ALL PASSED"
fi
