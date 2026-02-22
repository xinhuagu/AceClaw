# Click Precision Robust (Issue #67 MVP)

This package adds a reusable macOS click framework focused on reliability for small targets.

## Location

- `.aceclaw/skills/click-precision-robust/SKILL.md`
- `.aceclaw/skills/click-precision-robust/scripts/robust_click.sh`

## Strategy Order

1. **Element-first** (`AXPress`) via `ax_press.applescript`
2. **Coordinate fallback** (`cliclick`) with tiny-target jitter options
3. **Mandatory post-click verification** via `verify_state.applescript`
4. **Bounded retry + backoff** with explicit failure reasons

## Failure Reasons

- `focus_failed`
- `element_not_found`
- `transform_mismatch`
- `verify_failed`
- `retry_exhausted`

## Telemetry

Structured JSONL logs are emitted to `/tmp/aceclaw-click-precision.log` by default.

## Example

```bash
bash .aceclaw/skills/click-precision-robust/scripts/robust_click.sh \
  --app "Calendar" \
  --locator "Next" \
  --window-hint "Calendar" \
  --expect "window_title_contains=2026" \
  --tiny-target true \
  --retries 4 \
  --backoff-ms 220
```

For Microsoft Teams weekly meeting lookup, use a two-step sequence:

```bash
# 1) Enter Calendar first, then verify target control exists
bash .aceclaw/skills/click-precision-robust/scripts/robust_click.sh \
  --app "Microsoft Teams" \
  --locator "Calendar" \
  --expect "element_exists=Go to next week" \
  --retries 2 \
  --backoff-ms 300

# 2) Move to next week
bash .aceclaw/skills/click-precision-robust/scripts/robust_click.sh \
  --app "Microsoft Teams" \
  --locator "Go to next week" \
  --expect "window_title_contains=Mar" \
  --retries 3 \
  --backoff-ms 400
```

## Notes

- macOS only.
- Requires Accessibility permissions.
- Coordinate fallback requires `cliclick` (`brew install cliclick`).
