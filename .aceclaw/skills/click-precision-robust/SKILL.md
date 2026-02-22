---
name: click-precision-robust
description: "Robust macOS click automation with AX-first interaction, calibrated coordinate fallback, strict post-click verification, retries, and telemetry"
argument-hint: "action=click app='<App>' locator='<ElementTitle>' [window_hint='<WindowTitle>'] [x=123 y=456] [expect='window_title_contains=Done'] [tiny_target=true]"
context: inline
---

# Click Precision Robust

Use this skill for desktop click actions where reliability matters (tiny controls, shifted windows, multi-display setups).

## Guarantees

- Element-first click (AXPress) whenever locator is provided.
- Coordinate fallback via `cliclick` only when element strategy fails.
- Mandatory post-click verification before declaring success.
- Bounded retries with explicit failure reason.
- Structured telemetry for every attempt.
- Pre-click screenshot capture by default (for calibration/debugging).

## Runtime

This skill is implemented by:

- `.aceclaw/skills/click-precision-robust/scripts/robust_click.sh`
- `.aceclaw/skills/click-precision-robust/scripts/ax_press.applescript`
- `.aceclaw/skills/click-precision-robust/scripts/verify_state.applescript`
- `.aceclaw/skills/click-precision-robust/scripts/display_context.jxa`
- `.aceclaw/skills/click-precision-robust/scripts/vision_click_loop.sh`
- `.aceclaw/skills/click-precision-robust/scripts/template_locate.py`

## Invocation

```bash
bash .aceclaw/skills/click-precision-robust/scripts/robust_click.sh \
  --app "AnyApp" \
  --locator "Next" \
  --window-hint "Main" \
  --expect "window_title_contains=Details" \
  --tiny-target true \
  --retries 4 \
  --backoff-ms 220
```

Coordinate fallback usage:

```bash
bash .aceclaw/skills/click-precision-robust/scripts/robust_click.sh \
  --app "AnyApp" \
  --x 1440 --y 830 \
  --expect "frontmost_app=AnyApp"
```

Microsoft Teams weekly agenda usage (recommended sequence):

```bash
# Step 1: enter Calendar area first
bash .aceclaw/skills/click-precision-robust/scripts/robust_click.sh \
  --app "Microsoft Teams" \
  --locator "Calendar" \
  --expect "element_exists=Go to next week" \
  --retries 2 \
  --backoff-ms 300

# Step 2: then move week
bash .aceclaw/skills/click-precision-robust/scripts/robust_click.sh \
  --app "Microsoft Teams" \
  --locator "Go to next week" \
  --expect "window_title_contains=Mar" \
  --retries 3 \
  --backoff-ms 400
```

## Expectation Contract

`--expect` supports:

- `frontmost_app=<AppName>`
- `window_title_contains=<Text>`
- `element_exists=<AXName>`

## Failure Reasons

- `focus_failed`
- `element_not_found`
- `transform_mismatch`
- `verify_failed`
- `retry_exhausted`

## Telemetry

Each attempt writes JSONL telemetry to:

- default: `/tmp/aceclaw-click-precision.log`
- override: `--telemetry-log <path>`

Fields include:

- `timestamp`
- `app`
- `window_id_or_title`
- `action`
- `target_locator`
- `method`
- `attempt`
- `display_context`
- `verify_result`
- `failure_reason`

Pre-click screenshots are saved to:

- default: `/tmp/aceclaw-preclick-*.png`
- override: `--capture-dir <path>`
- disable: `--capture-before false`

## Notes

- macOS only.
- Requires Accessibility permissions for Terminal/agent process.
- Requires `cliclick` for coordinate fallback (`brew install cliclick`).
- Visual template mode requires `opencv-python` (`pip3 install opencv-python`).

## Visual Closed Loop Mode

When absolute/relative coordinates are unstable, use template-based closed loop:

```bash
bash .aceclaw/skills/click-precision-robust/scripts/vision_click_loop.sh \
  --app "Microsoft Teams" \
  --template "/path/to/calendar_icon.png" \
  --expect "frontmost_app=Microsoft Teams" \
  --retries 4 \
  --threshold 0.86
```

Then run again for the next button:

```bash
bash .aceclaw/skills/click-precision-robust/scripts/vision_click_loop.sh \
  --app "Microsoft Teams" \
  --template "/path/to/go_to_next_week_icon.png" \
  --expect "window_title_contains=Mar" \
  --retries 4 \
  --threshold 0.86
```
