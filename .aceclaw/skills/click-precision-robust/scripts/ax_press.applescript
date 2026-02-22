on run argv
	if (count of argv) < 2 then return "error:missing_args"
	set appName to item 1 of argv
	set locator to item 2 of argv
	set roleHint to ""
	if (count of argv) >= 3 then set roleHint to item 3 of argv

	if locator is "" then return "error:empty_locator"

	try
		tell application appName to activate
	on error errMsg
		return "focus_failed:" & errMsg
	end try

	delay 0.08

	try
		tell application "System Events"
			if not (exists process appName) then return "focus_failed:process_not_found"
			tell process appName
				set frontmost to true
				set targetWindow to window 1

				-- Fast path: direct button by title
				try
					click button locator of targetWindow
					return "ok:button"
				end try

				-- Generic path: search by AX name
				set candidates to (every UI element of (entire contents of targetWindow) whose name is locator)
				if (count of candidates) > 0 then
					set hit to item 1 of candidates
					if roleHint is not "" then
						try
							set filtered to (every UI element of candidates whose role description contains roleHint)
							if (count of filtered) > 0 then set hit to item 1 of filtered
						end try
					end if
					try
						perform action "AXPress" of hit
						return "ok:axpress"
					on error
						click hit
						return "ok:click"
					end try
				end if
			end tell
		end tell
	on error errMsg
		return "element_not_found:" & errMsg
	end try

	return "element_not_found:no_match"
end run
