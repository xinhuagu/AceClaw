# Vision Click Tool (PyAutoGUI + OpenCV)

Independent desktop automation helper for:
- screenshot
- template match (`cv2.matchTemplate`)
- click center point with `pyautogui`

## Install

```bash
python3 -m pip install -r scripts/vision-click/requirements.txt
```

On macOS, grant permissions to your terminal:
- Accessibility
- Screen Recording

## Usage

```bash
python3 scripts/vision-click/vision_click.py \
  --template scripts/vision-click/templates/teams-calendar-icon.png \
  --confidence 0.82 \
  --retries 5
```

## Useful Options

- `--dry-run`: locate only, no click.
- `--region x,y,width,height`: restrict search region for speed/stability.
- `--debug-dir /tmp/vision-debug`: save attempt images with match boxes.
- `--clicks 2`: double-click.
- `--button right`: right-click.

## Example: teams two-step

```bash
python3 scripts/vision-click/vision_click.py \
  --template .aceclaw/skills/click-precision-robust/templates/teams-calendar-icon.png \
  --confidence 0.80 \
  --retries 4

python3 scripts/vision-click/vision_click.py \
  --template .aceclaw/skills/click-precision-robust/templates/teams-next-week-icon.png \
  --confidence 0.88 \
  --retries 4
```
