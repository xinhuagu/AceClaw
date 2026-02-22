#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
AX_SCRIPT="$SCRIPT_DIR/ax_press.applescript"
VERIFY_SCRIPT="$SCRIPT_DIR/verify_state.applescript"
DISPLAY_SCRIPT="$SCRIPT_DIR/display_context.jxa"

APP=""
WINDOW_HINT=""
LOCATOR=""
ROLE_HINT=""
X=""
Y=""
EXPECT=""
RETRIES=3
BACKOFF_MS=200
TINY_TARGET=false
SKIP_DISPLAY_CHECK=false
TELEMETRY_LOG="/tmp/aceclaw-click-precision.log"

usage() {
  cat <<USAGE
Usage:
  robust_click.sh --app <AppName> [--window-hint <title>] [--locator <AX name>] [--role-hint <role>] \\
                  [--x <int> --y <int>] [--expect <predicate>] [--retries <n>] [--backoff-ms <ms>] \\
                  [--skip-display-check true|false] \\
                  [--tiny-target true|false] [--telemetry-log <path>]
USAGE
}

require_cmd() {
  command -v "$1" >/dev/null 2>&1 || {
    echo "missing dependency: $1" >&2
    exit 2
  }
}

json_escape() {
  local s="${1:-}"
  s=${s//\\/\\\\}
  s=${s//\"/\\\"}
  s=${s//$'\n'/ }
  s=${s//$'\r'/ }
  echo "$s"
}

now_iso() {
  date -u +"%Y-%m-%dT%H:%M:%SZ"
}

sleep_backoff() {
  local attempt="$1"
  local total_ms
  local seconds
  total_ms=$((BACKOFF_MS * attempt))
  seconds="$(LC_ALL=C awk -v ms="$total_ms" 'BEGIN { printf "%.3f", ms / 1000 }')"
  sleep "$seconds"
}

display_context_json() {
  if [[ -x "$DISPLAY_SCRIPT" ]]; then
    osascript -l JavaScript "$DISPLAY_SCRIPT" 2>/dev/null || echo '{"source":"unknown","screens":[]}'
  else
    echo '{"source":"unknown","screens":[]}'
  fi
}

focus_target_window() {
  local app="$1"
  local hint="$2"
  osascript <<APPLESCRIPT
on run
	try
		tell application "$app" to activate
	on error errMsg
		return "focus_failed:" & errMsg
	end try
	delay 0.08
	if "$hint" is not "" then
		try
			tell application "System Events"
				tell process "$app"
					set frontmost to true
					set ws to every window whose name contains "$hint"
					if (count of ws) > 0 then
						perform action "AXRaise" of item 1 of ws
						return "ok:" & (name of item 1 of ws)
					end if
				end tell
			end tell
		on error errMsg
			return "focus_failed:" & errMsg
		end try
	end if
	return "ok"
end run
APPLESCRIPT
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

point_in_any_display() {
  local x="$1"
  local y="$2"
  local context="$3"

  if command -v jq >/dev/null 2>&1; then
    echo "$context" | jq -e --argjson x "$x" --argjson y "$y" '
      .screens | any((.frame.x <= $x) and ($x <= (.frame.x + .frame.width)) and (.frame.y <= $y) and ($y <= (.frame.y + .frame.height)))
    ' >/dev/null 2>&1
  else
    return 0
  fi
}

telemetry() {
  local action="$1"
  local method="$2"
  local attempt="$3"
  local verify_result="$4"
  local failure_reason="$5"
  local window_title="$6"
  local display_json="$7"

  mkdir -p "$(dirname "$TELEMETRY_LOG")" 2>/dev/null || true
  {
    echo "{\"timestamp\":\"$(now_iso)\",\"app\":\"$(json_escape "$APP")\",\"window_id_or_title\":\"$(json_escape "$window_title")\",\"action\":\"$(json_escape "$action")\",\"target_locator\":\"$(json_escape "$LOCATOR")\",\"method\":\"$(json_escape "$method")\",\"attempt\":$attempt,\"display_context\":$display_json,\"verify_result\":\"$(json_escape "$verify_result")\",\"failure_reason\":$( [[ -n "$failure_reason" ]] && echo "\"$(json_escape "$failure_reason")\"" || echo "null" )}"
  } >>"$TELEMETRY_LOG"
}

fallback_click_once() {
  local x="$1"
  local y="$2"
  cliclick "c:${x},${y}" >/dev/null 2>&1
}

fallback_click() {
  local x="$1"
  local y="$2"

  if [[ "$TINY_TARGET" == "true" ]]; then
    local offsets=(
      "0 0" "1 0" "-1 0" "0 1" "0 -1" "1 1" "-1 -1" "2 0" "0 2"
    )
    for p in "${offsets[@]}"; do
      local dx dy
      dx="${p%% *}"
      dy="${p##* }"
      fallback_click_once "$((x + dx))" "$((y + dy))"
      sleep 0.04
    done
  else
    fallback_click_once "$x" "$y"
  fi
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --app) APP="${2:-}"; shift 2 ;;
    --window-hint) WINDOW_HINT="${2:-}"; shift 2 ;;
    --locator) LOCATOR="${2:-}"; shift 2 ;;
    --role-hint) ROLE_HINT="${2:-}"; shift 2 ;;
    --x) X="${2:-}"; shift 2 ;;
    --y) Y="${2:-}"; shift 2 ;;
    --expect) EXPECT="${2:-}"; shift 2 ;;
    --retries) RETRIES="${2:-}"; shift 2 ;;
    --backoff-ms) BACKOFF_MS="${2:-}"; shift 2 ;;
    --skip-display-check) SKIP_DISPLAY_CHECK="${2:-false}"; shift 2 ;;
    --tiny-target) TINY_TARGET="${2:-false}"; shift 2 ;;
    --telemetry-log) TELEMETRY_LOG="${2:-}"; shift 2 ;;
    -h|--help) usage; exit 0 ;;
    *) echo "Unknown arg: $1" >&2; usage; exit 2 ;;
  esac
done

if [[ -z "$APP" ]]; then
  echo "--app is required" >&2
  usage
  exit 2
fi
if [[ -z "$LOCATOR" && ( -z "$X" || -z "$Y" ) ]]; then
  echo "Either --locator or both --x/--y are required" >&2
  exit 2
fi
if [[ "$RETRIES" -lt 1 ]]; then
  RETRIES=1
fi

require_cmd osascript
if [[ -n "$X" || -n "$Y" ]]; then
  require_cmd cliclick
fi

DISPLAY_JSON="$(display_context_json)"
LAST_REASON=""

for ((attempt=1; attempt<=RETRIES; attempt++)); do
  window_title=""
  focus_result="$(focus_target_window "$APP" "$WINDOW_HINT" || true)"
  if [[ "$focus_result" == ok:* ]]; then
    window_title="${focus_result#ok:}"
  elif [[ "$focus_result" != "ok" ]]; then
    LAST_REASON="focus_failed"
    verify="focus_failed"
    telemetry "click" "focus" "$attempt" "$verify" "$LAST_REASON" "$window_title" "$DISPLAY_JSON"
    sleep_backoff "$attempt"
    continue
  fi

  method="coordinate_fallback"
  action_ok=false

  if [[ -n "$LOCATOR" ]]; then
    ax_result="$(osascript "$AX_SCRIPT" "$APP" "$LOCATOR" "$ROLE_HINT" 2>/dev/null || echo "element_not_found:osascript_error")"
    if [[ "$ax_result" == ok:* ]]; then
      method="element"
      action_ok=true
    else
      LAST_REASON="element_not_found"
    fi
  fi

  if [[ "$action_ok" != true ]]; then
    if [[ -n "$X" && -n "$Y" ]]; then
      if [[ "$SKIP_DISPLAY_CHECK" != "true" ]] && ! point_in_any_display "$X" "$Y" "$DISPLAY_JSON"; then
        LAST_REASON="transform_mismatch"
        telemetry "click" "$method" "$attempt" "verify_skipped" "$LAST_REASON" "$window_title" "$DISPLAY_JSON"
        sleep_backoff "$attempt"
        continue
      fi
      fallback_click "$X" "$Y"
      method="coordinate_fallback"
      action_ok=true
    else
      telemetry "click" "$method" "$attempt" "verify_skipped" "element_not_found" "$window_title" "$DISPLAY_JSON"
      sleep_backoff "$attempt"
      continue
    fi
  fi

  verify="$(verify_state "$APP" "$EXPECT")"
  if [[ "$verify" == "ok" ]]; then
    telemetry "click" "$method" "$attempt" "ok" "" "$window_title" "$DISPLAY_JSON"
    echo "{\"ok\":true,\"method\":\"$method\",\"attempt\":$attempt,\"failure_reason\":null}"
    exit 0
  fi

  LAST_REASON="verify_failed"
  telemetry "click" "$method" "$attempt" "$verify" "$LAST_REASON" "$window_title" "$DISPLAY_JSON"
  sleep_backoff "$attempt"
done

echo "{\"ok\":false,\"method\":null,\"attempt\":$RETRIES,\"failure_reason\":\"retry_exhausted\",\"last_reason\":\"$LAST_REASON\"}"
exit 1
