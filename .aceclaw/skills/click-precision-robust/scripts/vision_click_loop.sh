#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
LOCATE_PY="$SCRIPT_DIR/template_locate.py"
VERIFY_SCRIPT="$SCRIPT_DIR/verify_state.applescript"

APP=""
TEMPLATE=""
EXPECT=""
THRESHOLD="0.86"
RETRIES=3
BACKOFF_MS=300
POST_DELAY_MS=300
CAPTURE_DIR="/tmp"

usage() {
  cat <<USAGE
Usage:
  vision_click_loop.sh --app <AppName> --template <png> [--expect <predicate>] \
    [--threshold 0.86] [--retries 3] [--backoff-ms 300] [--post-delay-ms 300] [--capture-dir /tmp]
USAGE
}

require_cmd() {
  command -v "$1" >/dev/null 2>&1 || {
    echo "missing dependency: $1" >&2
    exit 2
  }
}

sleep_backoff() {
  local attempt="$1"
  local total_ms
  local seconds
  total_ms=$((BACKOFF_MS * attempt))
  seconds="$(LC_ALL=C awk -v ms="$total_ms" 'BEGIN { printf "%.3f", ms / 1000 }')"
  sleep "$seconds"
}

verify_state() {
  local app="$1"
  local expect="$2"
  if [[ -z "$expect" ]]; then
    echo "ok"
    return 0
  fi
  osascript "$VERIFY_SCRIPT" "$app" "$expect" 2>/dev/null || echo "verify_failed:osascript_error"
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --app) APP="${2:-}"; shift 2 ;;
    --template) TEMPLATE="${2:-}"; shift 2 ;;
    --expect) EXPECT="${2:-}"; shift 2 ;;
    --threshold) THRESHOLD="${2:-}"; shift 2 ;;
    --retries) RETRIES="${2:-}"; shift 2 ;;
    --backoff-ms) BACKOFF_MS="${2:-}"; shift 2 ;;
    --post-delay-ms) POST_DELAY_MS="${2:-}"; shift 2 ;;
    --capture-dir) CAPTURE_DIR="${2:-}"; shift 2 ;;
    -h|--help) usage; exit 0 ;;
    *) echo "Unknown arg: $1" >&2; usage; exit 2 ;;
  esac
done

if [[ -z "$APP" || -z "$TEMPLATE" ]]; then
  usage
  exit 2
fi

require_cmd osascript
require_cmd screencapture
require_cmd cliclick
require_cmd python3

mkdir -p "$CAPTURE_DIR"

python3 - <<'PY' >/dev/null 2>&1 || { echo '{"ok":false,"failure_reason":"missing_cv2","hint":"pip3 install opencv-python"}'; exit 2; }
import importlib.util
assert importlib.util.find_spec('cv2') is not None
PY

if [[ "$RETRIES" -lt 1 ]]; then
  RETRIES=1
fi

if [[ ! -f "$TEMPLATE" ]]; then
  echo "{\"ok\":false,\"failure_reason\":\"template_missing\",\"template\":\"$TEMPLATE\"}"
  exit 2
fi

LAST_REASON=""
for ((attempt=1; attempt<=RETRIES; attempt++)); do
  osascript -e "tell application \"$APP\" to activate" >/dev/null 2>&1 || true
  sleep 0.08

  pre="$CAPTURE_DIR/aceclaw-vision-pre-${attempt}-$(date +%s)-$$.png"
  post="$CAPTURE_DIR/aceclaw-vision-post-${attempt}-$(date +%s)-$$.png"
  if ! screencapture -x "$pre" >/dev/null 2>&1 || [[ ! -s "$pre" ]]; then
    echo "{\"ok\":false,\"attempt\":$attempt,\"failure_reason\":\"capture_failed\",\"hint\":\"Grant Screen Recording permission to the terminal/agent process\"}"
    exit 2
  fi

  locate_json="$(python3 "$LOCATE_PY" --screenshot "$pre" --template "$TEMPLATE" --threshold "$THRESHOLD" || true)"
  found="$(echo "$locate_json" | python3 -c 'import json,sys; print(json.loads(sys.stdin.read()).get("found", False))' 2>/dev/null || echo false)"
  score_dbg="$(echo "$locate_json" | python3 -c 'import json,sys; d=json.loads(sys.stdin.read()); print(d.get("score",""))' 2>/dev/null || true)"
  scale_dbg="$(echo "$locate_json" | python3 -c 'import json,sys; d=json.loads(sys.stdin.read()); print(d.get("scale",""))' 2>/dev/null || true)"
  mode_dbg="$(echo "$locate_json" | python3 -c 'import json,sys; d=json.loads(sys.stdin.read()); print(d.get("mode",""))' 2>/dev/null || true)"

  if [[ "$found" != "True" && "$found" != "true" ]]; then
    LAST_REASON="template_not_found"
    echo "{\"attempt\":$attempt,\"found\":false,\"score\":\"$score_dbg\",\"scale\":\"$scale_dbg\",\"mode\":\"$mode_dbg\"}" >&2
    sleep_backoff "$attempt"
    continue
  fi

  x="$(echo "$locate_json" | python3 -c 'import json,sys; print(json.loads(sys.stdin.read()).get("x", ""))' 2>/dev/null || true)"
  y="$(echo "$locate_json" | python3 -c 'import json,sys; print(json.loads(sys.stdin.read()).get("y", ""))' 2>/dev/null || true)"
  score="$(echo "$locate_json" | python3 -c 'import json,sys; print(json.loads(sys.stdin.read()).get("score", 0.0))' 2>/dev/null || echo 0.0)"

  if [[ -z "$x" || -z "$y" ]]; then
    LAST_REASON="template_parse_failed"
    sleep_backoff "$attempt"
    continue
  fi

  cliclick "c:${x},${y}" >/dev/null 2>&1 || true

  sleep "$(LC_ALL=C awk -v ms="$POST_DELAY_MS" 'BEGIN { printf "%.3f", ms / 1000 }')"
  if ! screencapture -x "$post" >/dev/null 2>&1 || [[ ! -s "$post" ]]; then
    echo "{\"ok\":false,\"attempt\":$attempt,\"failure_reason\":\"capture_failed_post\",\"hint\":\"Grant Screen Recording permission to the terminal/agent process\"}"
    exit 2
  fi

  verify="$(verify_state "$APP" "$EXPECT")"
  if [[ "$verify" == "ok" ]]; then
    echo "{\"ok\":true,\"attempt\":$attempt,\"x\":$x,\"y\":$y,\"score\":$score,\"pre\":\"$pre\",\"post\":\"$post\"}"
    exit 0
  fi

  LAST_REASON="verify_failed"
  sleep_backoff "$attempt"
done

echo "{\"ok\":false,\"attempt\":$RETRIES,\"failure_reason\":\"retry_exhausted\",\"last_reason\":\"$LAST_REASON\"}"
exit 1
