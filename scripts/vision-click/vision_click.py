#!/usr/bin/env python3
"""
Locate a template on screen with OpenCV and click it using PyAutoGUI.

Example:
  python3 vision_click.py --template ./teams_calendar_icon.png --confidence 0.82 --retries 5
"""

from __future__ import annotations

import argparse
import sys
import time
from pathlib import Path

import cv2  # type: ignore
import numpy as np


def parse_region(raw: str | None) -> tuple[int, int, int, int] | None:
    if not raw:
        return None
    parts = [p.strip() for p in raw.split(",")]
    if len(parts) != 4:
        raise ValueError("region must be x,y,width,height")
    x, y, w, h = [int(p) for p in parts]
    if w <= 0 or h <= 0:
        raise ValueError("region width/height must be > 0")
    return x, y, w, h


def grab_screen(region: tuple[int, int, int, int] | None) -> np.ndarray:
    import pyautogui  # type: ignore

    img = pyautogui.screenshot(region=region)
    arr = np.array(img)
    return cv2.cvtColor(arr, cv2.COLOR_RGB2BGR)


def find_template(
    screenshot_bgr: np.ndarray, template_bgr: np.ndarray
) -> tuple[float, tuple[int, int], tuple[int, int]]:
    shot_gray = cv2.cvtColor(screenshot_bgr, cv2.COLOR_BGR2GRAY)
    tpl_gray = cv2.cvtColor(template_bgr, cv2.COLOR_BGR2GRAY)
    result = cv2.matchTemplate(shot_gray, tpl_gray, cv2.TM_CCOEFF_NORMED)
    _, max_val, _, max_loc = cv2.minMaxLoc(result)
    h, w = tpl_gray.shape
    return float(max_val), max_loc, (w, h)


def draw_debug(
    screenshot_bgr: np.ndarray,
    top_left: tuple[int, int],
    size: tuple[int, int],
    score: float,
    out_path: Path,
) -> None:
    x, y = top_left
    w, h = size
    dbg = screenshot_bgr.copy()
    cv2.rectangle(dbg, (x, y), (x + w, y + h), (0, 255, 0), 2)
    cv2.putText(
        dbg,
        f"score={score:.3f}",
        (x, max(20, y - 8)),
        cv2.FONT_HERSHEY_SIMPLEX,
        0.6,
        (0, 255, 0),
        2,
        cv2.LINE_AA,
    )
    out_path.parent.mkdir(parents=True, exist_ok=True)
    cv2.imwrite(str(out_path), dbg)


def main() -> int:
    parser = argparse.ArgumentParser(description="Template locate and click (PyAutoGUI + OpenCV)")
    parser.add_argument("--template", required=True, help="Path to template image")
    parser.add_argument("--confidence", type=float, default=0.85, help="Match threshold 0..1")
    parser.add_argument("--retries", type=int, default=3, help="Retry attempts")
    parser.add_argument("--interval", type=float, default=0.3, help="Seconds between retries")
    parser.add_argument("--move-duration", type=float, default=0.08, help="Mouse move duration")
    parser.add_argument("--clicks", type=int, default=1, help="Click count")
    parser.add_argument("--button", default="left", choices=["left", "right", "middle"], help="Mouse button")
    parser.add_argument("--region", help="Optional region x,y,width,height")
    parser.add_argument("--dry-run", action="store_true", help="Locate only, do not click")
    parser.add_argument("--debug-dir", help="Write debug screenshots per attempt")
    args = parser.parse_args()

    if not (0.0 < args.confidence <= 1.0):
        print("error: confidence must be in (0,1]", file=sys.stderr)
        return 2
    if args.retries < 1:
        print("error: retries must be >= 1", file=sys.stderr)
        return 2

    template_path = Path(args.template)
    if not template_path.is_file():
        print(f"error: template not found: {template_path}", file=sys.stderr)
        return 2

    template_bgr = cv2.imread(str(template_path), cv2.IMREAD_COLOR)
    if template_bgr is None:
        print(f"error: failed to read template: {template_path}", file=sys.stderr)
        return 2

    try:
        region = parse_region(args.region)
    except ValueError as e:
        print(f"error: {e}", file=sys.stderr)
        return 2

    try:
        import pyautogui  # type: ignore
    except Exception:
        print(
            "error: missing dependency pyautogui. "
            "Install with: python3 -m pip install -r scripts/vision-click/requirements.txt",
            file=sys.stderr,
        )
        return 2

    pyautogui.FAILSAFE = True

    for attempt in range(1, args.retries + 1):
        shot = grab_screen(region)
        score, top_left, (tw, th) = find_template(shot, template_bgr)
        found = score >= args.confidence

        if args.debug_dir:
            debug_path = Path(args.debug_dir) / f"attempt-{attempt}.png"
            draw_debug(shot, top_left, (tw, th), score, debug_path)

        if not found:
            print(
                f'{{"attempt":{attempt},"found":false,"score":{score:.6f},"threshold":{args.confidence}}}'
            )
            time.sleep(args.interval)
            continue

        cx = top_left[0] + tw // 2
        cy = top_left[1] + th // 2
        if region:
            cx += region[0]
            cy += region[1]

        if args.dry_run:
            print(
                f'{{"attempt":{attempt},"found":true,"score":{score:.6f},"x":{cx},"y":{cy},"clicked":false}}'
            )
            return 0

        pyautogui.moveTo(cx, cy, duration=args.move_duration)
        pyautogui.click(x=cx, y=cy, clicks=args.clicks, button=args.button)
        print(f'{{"attempt":{attempt},"found":true,"score":{score:.6f},"x":{cx},"y":{cy},"clicked":true}}')
        return 0

    print(f'{{"ok":false,"failure_reason":"template_not_found","retries":{args.retries}}}')
    return 1


if __name__ == "__main__":
    raise SystemExit(main())
