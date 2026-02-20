Captures a screenshot on macOS using the screencapture command. macOS only.

Saves the screenshot as a PNG file. Optionally captures a specific screen region
and extracts text from the image using OCR (macOS Vision framework).

IMPORTANT: This is a LAST RESORT for information gathering. Always prefer text-based
tools (read_file, web_fetch, grep, get_text) over screenshots. Only use screen_capture
when you genuinely need visual information that cannot be obtained as text.

When to use:
- Debugging UI layout issues that require visual inspection
- Verifying visual appearance of an application after changes
- Capturing error dialogs or visual glitches that can't be read as text
- Extracting text from images when no text source is available (with ocr=true)
- Documenting the current state of a GUI application

When NOT to use:
- Reading web page content - use web_fetch or browser get_text
- Reading file contents - use read_file
- Any task where text-based tools can get the same information
- Reading terminal output - use bash and capture stdout directly

Parameter details:
- region: Screen region as 'x,y,width,height' (e.g., '0,0,800,600'). Optional.
  If omitted, captures the entire primary screen.
  Coordinates start from the top-left corner of the screen.
- ocr: If true, extract text from the screenshot using macOS Vision framework. Default: false.
- output_path: File path for the screenshot. Optional, defaults to a temp file.

Multi-monitor behavior:
- Without a region, captures only the primary display
- Use region coordinates to capture from specific monitors
- Monitor arrangement follows macOS System Settings display layout

Behavior:
- Captures silently (no shutter sound, no visual flash)
- Returns the file path of the saved screenshot
- OCR uses accurate recognition level with language correction
- 30-second timeout for the capture operation
- Output is a PNG file (lossless compression)

Combining with other tools:
- After capture: use read_file on the PNG path to view the image
- For web UI testing: use browser to navigate, then screen_capture to verify layout
- For OCR pipelines: capture with ocr=true, then process the extracted text

Tips:
- Use region capture to focus on a specific area of the screen
- Enable ocr=true when you need to read text from the captured image
- The OCR output may not be perfect - treat it as approximate text extraction
- For repeated captures, specify output_path to control file naming
- Smaller regions capture faster and produce smaller files
