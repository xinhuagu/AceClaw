Executes AppleScript code on macOS using the osascript command. macOS only.

Use this for macOS-specific automation: controlling applications, showing system dialogs,
managing Finder operations, and sending notifications.

When to use:
- Controlling macOS applications (open, activate, quit, bring to front)
- Showing system dialogs or notifications to the user
- Performing Finder operations (reveal files, move to trash, create aliases)
- Interacting with macOS system features (clipboard, notifications, volumes)
- Automating macOS-specific workflows between multiple applications
- Getting information about running applications or system state

When NOT to use:
- Running shell commands - use bash instead
- File operations (read/write/edit) - use read_file, write_file, edit_file
- Web operations - use web_fetch, web_search, browser
- Any task that can be done with platform-independent tools

Parameter details:
- script: The AppleScript code to execute. Can be multi-line.
  Each line is passed as a separate -e argument to osascript.

Common patterns:
- Open an app: tell application "Safari" to activate
- Show dialog: display dialog "Hello" with title "AceClaw"
- Reveal in Finder: tell application "Finder" to reveal POSIX file "/path/to/file"
- Get clipboard: the clipboard
- Set clipboard: set the clipboard to "text content"
- Send notification: display notification "Done" with title "AceClaw"
- List running apps: tell application "System Events" to get name of every process

Error handling patterns:
- Wrap risky operations in try/on error blocks for graceful failure
- Check if an app is running before sending commands to it
- Use "with timeout of N seconds" for operations that may hang
- Application names are case-sensitive in "tell" blocks

Return value parsing:
- AppleScript returns the result of the last expression as a string
- Lists are returned as comma-separated values: "item1, item2, item3"
- Records are returned as key-value pairs
- Use "as text" to force string conversion of complex types
- Boolean values return as "true" or "false" strings

Multi-application workflows:
- Use nested "tell application" blocks for cross-app automation
- Be aware of app activation side effects (bringing windows to front)
- Use "System Events" for UI scripting across any application

Safety and constraints:
- 30-second timeout for script execution
- Output capped at 30,000 characters
- Do not execute untrusted or user-provided AppleScript without review
- Some operations may require Accessibility permissions in System Settings
- UI scripting requires "Allow Accessibility" permission for the terminal app

Tips:
- Multi-line scripts are supported - write them naturally with line breaks
- Use "tell application" blocks to control specific apps
- If the script fails, check that the target application is installed and permissions are granted
- Use "delay N" between UI actions to allow time for the interface to update
- Test complex scripts incrementally - build up from simpler commands
