#!/usr/bin/env python3
import argparse
import json
import sys

try:
    import cv2  # type: ignore
except Exception:
    print(json.dumps({"found": False, "error": "missing_cv2", "hint": "pip3 install opencv-python"}))
    sys.exit(2)


def main() -> int:
    p = argparse.ArgumentParser()
    p.add_argument("--screenshot", required=True)
    p.add_argument("--template", required=True)
    p.add_argument("--threshold", type=float, default=0.86)
    args = p.parse_args()

    shot = cv2.imread(args.screenshot, cv2.IMREAD_GRAYSCALE)
    tpl = cv2.imread(args.template, cv2.IMREAD_GRAYSCALE)

    if shot is None:
        print(json.dumps({"found": False, "error": "invalid_screenshot"}))
        return 1
    if tpl is None:
        print(json.dumps({"found": False, "error": "invalid_template"}))
        return 1

    if shot.shape[0] < tpl.shape[0] or shot.shape[1] < tpl.shape[1]:
        print(json.dumps({"found": False, "error": "template_larger_than_screenshot"}))
        return 1

    res = cv2.matchTemplate(shot, tpl, cv2.TM_CCOEFF_NORMED)
    _min_val, max_val, _min_loc, max_loc = cv2.minMaxLoc(res)

    found = float(max_val) >= args.threshold
    out = {
        "found": bool(found),
        "score": float(max_val),
        "threshold": float(args.threshold),
    }

    if found:
        x, y = max_loc
        h, w = tpl.shape
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
