Reads a file from the filesystem. You can access any file directly by using this tool.

The file_path can be absolute or relative to the working directory. Returns file contents
prefixed with line numbers (like cat -n). By default reads up to 2000 lines from the
beginning of the file. Lines longer than 2000 characters are truncated.

IMPORTANT — Use this tool INSTEAD of:
- cat, head, tail, less, more, sed -n (reading files via bash)
- Any bash command whose purpose is to view file contents

When to use:
- Reading source code, configs, logs, or any text file
- Understanding code before modifying it with edit_file (REQUIRED — always read before edit)
- Checking file contents after writing or editing
- Reading multiple related files in parallel (e.g., a class and its test, a config and its consumer)

When NOT to use:
- You already know the exact content and just need to modify it — use edit_file
- Searching for files by name — use glob instead
- Searching inside files for a pattern — use grep instead
- Listing directory contents — use list_directory instead

Parameter details:
- file_path: Absolute or relative path. Relative paths resolve against the working directory.
- offset: Line number to start reading from (1-based). Use for large files to skip headers.
- limit: Maximum lines to read. Default 2000. Use with offset for paginated reading.
- encoding: Optional charset name (e.g. "ISO-8859-1", "Shift_JIS", "IBM037"). If omitted, defaults to
  UTF-8. When a file cannot be decoded as UTF-8, the tool automatically detects the encoding using
  `file -bi` and retries. Use this parameter to override auto-detection when you know the encoding.

Common workflows:
- Read then edit: read_file → understand code → edit_file (never skip the read step)
- Explore structure: glob to find files → read_file to understand each one
- Debug: read_file on the error-producing file → grep for related usages

Safety & constraints:
- Returns an error if the file does not exist. Correct potential typos (readme.md → README.md)
  or use glob to discover the correct path.
- Binary files or images will produce garbled output — consider whether you actually need them.
- Very large files (>2000 lines) are automatically capped. Use offset/limit for targeted reading.

Tips:
- Always read a file before editing. The edit_file tool enforces this.
- Read multiple files in parallel when you need context from several sources at once.
- If a file is not found, try glob with a pattern like "**/*filename*" to locate it.
- Use offset/limit to read specific sections of large files instead of reading everything.