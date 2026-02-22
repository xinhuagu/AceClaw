#!/usr/bin/env python3
import argparse
import json
import sys
from typing import Iterable, List, Optional, Tuple

try:
    import cv2  # type: ignore
except Exception:
    print(json.dumps({"found": False, "error": "missing_cv2", "hint": "pip3 install opencv-python"}))
    sys.exit(2)


def parse_scales(raw: str) -> List[float]:
    values = []
    for part in raw.split(","):
        part = part.strip()
        if not part:
            continue
        values.append(float(part))
    if not values:
        values = [1.0]
    # De-duplicate while preserving order.
    out = []
    seen = set()
    for v in values:
        key = round(v, 6)
        if key in seen:
            continue
        seen.add(key)
        out.append(v)
    return out


def resize_with_mask(
    tpl_gray, tpl_mask, scale: float
) -> Tuple[Optional[object], Optional[object]]:
    if scale <= 0:
        return None, None
    if abs(scale - 1.0) < 1e-9:
        return tpl_gray, tpl_mask
    h, w = tpl_gray.shape
    nw = max(1, int(round(w * scale)))
    nh = max(1, int(round(h * scale)))
    tpl2 = cv2.resize(tpl_gray, (nw, nh), interpolation=cv2.INTER_AREA if scale < 1 else cv2.INTER_CUBIC)
    mask2 = None
    if tpl_mask is not None:
        mask2 = cv2.resize(tpl_mask, (nw, nh), interpolation=cv2.INTER_NEAREST)
    return tpl2, mask2


def best_match(shot, tpl, mask=None):
    method = cv2.TM_CCORR_NORMED
    if mask is not None:
        res = cv2.matchTemplate(shot, tpl, method, mask=mask)
    else:
        res = cv2.matchTemplate(shot, tpl, method)
    _min_val, max_val, _min_loc, max_loc = cv2.minMaxLoc(res)
    return float(max_val), max_loc


def main() -> int:
    p = argparse.ArgumentParser()
    p.add_argument("--screenshot", required=True)
    p.add_argument("--template", required=True)
    p.add_argument("--threshold", type=float, default=0.86)
    p.add_argument("--scales", default="0.70,0.85,1.00,1.15,1.30")
    args = p.parse_args()

    shot = cv2.imread(args.screenshot, cv2.IMREAD_GRAYSCALE)
    tpl_rgba = cv2.imread(args.template, cv2.IMREAD_UNCHANGED)

    if shot is None:
        print(json.dumps({"found": False, "error": "invalid_screenshot"}))
        return 1
    if tpl_rgba is None:
        print(json.dumps({"found": False, "error": "invalid_template"}))
        return 1

    if len(tpl_rgba.shape) == 2:
        tpl_gray = tpl_rgba
        tpl_mask = None
    elif tpl_rgba.shape[2] == 4:
        tpl_gray = cv2.cvtColor(tpl_rgba, cv2.COLOR_BGRA2GRAY)
        alpha = tpl_rgba[:, :, 3]
        tpl_mask = cv2.threshold(alpha, 8, 255, cv2.THRESH_BINARY)[1]
    else:
        tpl_gray = cv2.cvtColor(tpl_rgba, cv2.COLOR_BGR2GRAY)
        tpl_mask = None

    scales = parse_scales(args.scales)
    shot_edge = cv2.Canny(shot, 80, 180)
    best = {
        "score": -1.0,
        "loc": (0, 0),
        "w": 0,
        "h": 0,
        "scale": 1.0,
        "mode": "gray",
    }

    for s in scales:
        tpl2, mask2 = resize_with_mask(tpl_gray, tpl_mask, s)
        if tpl2 is None:
            continue
        if shot.shape[0] < tpl2.shape[0] or shot.shape[1] < tpl2.shape[1]:
            continue

        # Gray match.
        gray_score, gray_loc = best_match(shot, tpl2, mask2)
        if gray_score > best["score"]:
            best.update(
                {
                    "score": gray_score,
                    "loc": gray_loc,
                    "w": int(tpl2.shape[1]),
                    "h": int(tpl2.shape[0]),
                    "scale": float(s),
                    "mode": "gray",
                }
            )

        # Edge match for robustness against color/theme changes.
        tpl_edge = cv2.Canny(tpl2, 80, 180)
        edge_score, edge_loc = best_match(shot_edge, tpl_edge, mask2)
        if edge_score > best["score"]:
            best.update(
                {
                    "score": edge_score,
                    "loc": edge_loc,
                    "w": int(tpl2.shape[1]),
                    "h": int(tpl2.shape[0]),
                    "scale": float(s),
                    "mode": "edge",
                }
            )

    max_val = float(best["score"])
    max_loc = best["loc"]
    found = max_val >= args.threshold
    out = {
        "found": bool(found),
        "score": max_val,
        "threshold": float(args.threshold),
        "scale": float(best["scale"]),
        "mode": best["mode"],
    }

    if found:
        x, y = max_loc
        h = int(best["h"])
        w = int(best["w"])
        out.update(
            {
                "x": int(x + w // 2),
                "y": int(y + h // 2),
                "left": int(x),
                "top": int(y),
                "w": int(w),
                "h": int(h),
            }
        )

    print(json.dumps(out))
    return 0 if found else 1


if __name__ == "__main__":
    raise SystemExit(main())
