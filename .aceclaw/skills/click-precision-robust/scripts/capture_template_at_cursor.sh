#!/usr/bin/env bash
set -euo pipefail

APP="Microsoft Teams"
OUT=".aceclaw/skills/click-precision-robust/templates/teams_calendar.png"
SIZE=120
DELAY=3

usage() {
  cat <<USAGE
Usage:
  capture_template_at_cursor.sh [--app "Microsoft Teams"] [--out <path>] [--size 120] [--delay 3]
USAGE
}

require_cmd() {
  command -v "$1" >/dev/null 2>&1 || {
    echo "missing dependency: $1" >&2
    exit 2
  }
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --app) APP="${2:-}"; shift 2 ;;
    --out) OUT="${2:-}"; shift 2 ;;
    --size) SIZE="${2:-}"; shift 2 ;;
    --delay) DELAY="${2:-}"; shift 2 ;;
    -h|--help) usage; exit 0 ;;
    *) echo "Unknown arg: $1" >&2; usage; exit 2 ;;
  esac
done

if ! [[ "$SIZE" =~ ^[0-9]+$ ]] || [[ "$SIZE" -lt 24 ]]; then
  echo "--size must be an integer >= 24" >&2
  exit 2
fi
if ! [[ "$DELAY" =~ ^[0-9]+$ ]]; then
  echo "--delay must be an integer" >&2
  exit 2
fi

require_cmd osascript
require_cmd screencapture
require_cmd cliclick
require_cmd python3

mkdir -p "$(dirname "$OUT")"
tmp="/tmp/aceclaw-template-full-$$.png"

osascript -e "tell application \"$APP\" to activate" >/dev/null 2>&1 || true
echo "Activated: $APP"
echo "Move mouse to the icon center..."
for ((i=DELAY; i>0; i--)); do
  echo "Capturing in ${i}s"
  sleep 1
done

coord="$(cliclick p)"
x="${coord%%,*}"
y="${coord##*,}"

screencapture -x "$tmp"

python3 - "$tmp" "$OUT" "$x" "$y" "$SIZE" <<'PY'
import sys
import cv2

src, dst, x, y, size = sys.argv[1], sys.argv[2], int(sys.argv[3]), int(sys.argv[4]), int(sys.argv[5])
img = cv2.imread(src, cv2.IMREAD_COLOR)
if img is None:
    raise SystemExit("failed: cannot read screenshot")
h, w = img.shape[:2]
half = size // 2
left = max(0, x - half)
top = max(0, y - half)
right = min(w, left + size)
bottom = min(h, top + size)

# Re-adjust near borders to keep requested size when possible.
left = max(0, right - size)
top = max(0, bottom - size)
crop = img[top:bottom, left:right]
if crop.size == 0:
    raise SystemExit("failed: empty crop")
ok = cv2.imwrite(dst, crop)
if not ok:
    raise SystemExit("failed: cannot write output")
print(f"saved: {dst}")
print(f"cursor=({x},{y}) crop=({left},{top})..({right},{bottom})")
PY

echo "{\"ok\":true,\"template\":\"$OUT\",\"cursor\":\"$coord\",\"size\":$SIZE}"
