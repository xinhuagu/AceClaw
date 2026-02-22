on startsWith(txt, prefix)
	if (length of txt) < (length of prefix) then return false
	return (text 1 thru (length of prefix) of txt) is prefix
end startsWith

on suffixText(txt, prefix)
	if (length of txt) <= (length of prefix) then return ""
	return text ((length of prefix) + 1) thru -1 of txt
end suffixText

on run argv
	if (count of argv) < 2 then return "verify_failed:missing_args"
	set appName to item 1 of argv
	set expectation to item 2 of argv

	if expectation is "" then return "ok"

	try
		tell application appName to activate
	on error errMsg
		return "focus_failed:" & errMsg
	end try

	delay 0.08

	if my startsWith(expectation, "frontmost_app=") then
		set expectedApp to my suffixText(expectation, "frontmost_app=")
		tell application "System Events"
			set p to first process whose frontmost is true
			set pName to name of p
		end tell
		if pName is expectedApp then return "ok"
		return "verify_failed:frontmost_app_mismatch"
	end if

	if my startsWith(expectation, "window_title_contains=") then
		set needle to my suffixText(expectation, "window_title_contains=")
		tell application "System Events"
			tell process appName
				if not (exists window 1) then return "verify_failed:no_window"
				set t to name of window 1
			end tell
		end tell
		if t contains needle then return "ok"
		return "verify_failed:window_title_mismatch"
	end if

	if my startsWith(expectation, "element_exists=") then
		set targetName to my suffixText(expectation, "element_exists=")
		tell application "System Events"
			tell process appName
				if not (exists window 1) then return "verify_failed:no_window"
				set matches to (every UI element of (entire contents of window 1) whose name is targetName)
				if (count of matches) > 0 then return "ok"
			end tell
		end tell
		return "verify_failed:element_missing"
	end if

	return "verify_failed:unknown_expectation"
end run
