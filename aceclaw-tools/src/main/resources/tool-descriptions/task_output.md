Retrieves the output or checks the status of a background sub-agent task.

Use this tool after launching a background task with the task tool (run_in_background=true).
Returns the current status (RUNNING/COMPLETED/FAILED) and the result text when complete.

When to use:
- Checking if a background task has finished
- Retrieving the result of a completed background task
- Waiting for a background task to complete with a timeout
- Polling multiple background tasks to collect results

When NOT to use:
- The task was run synchronously (default) — the result is already in the task tool output
- You need to interact with the running task — background tasks cannot be modified

Parameter details:
- task_id: The task ID returned by the task tool when run_in_background=true. Required.
- timeout_ms: Maximum time to wait in milliseconds. Optional, default: 0.
  - timeout_ms=0: Non-blocking poll — returns immediately with current status
  - timeout_ms>0: Blocking wait — waits up to this many ms for completion
  - Maximum value: 300000 (5 minutes)
  - If timeout expires, returns RUNNING status (NOT an error — task still going)

Status values:
- RUNNING: The task is still executing. Use timeout_ms to wait, or poll again later.
- COMPLETED: The task finished successfully. The result text is included in the response.
- FAILED: The task encountered an error. The error message is included.

Common workflows:
- Fire and forget: launch background task, continue working, check later
- Parallel execution: launch multiple tasks, then collect results with timeout_ms
- Polling: check status with timeout_ms=0, continue other work, check again

Important notes:
- Background tasks are in-memory only — they are lost on daemon restart
- Completed tasks are automatically cleaned up after 30 minutes
- If task_id is unknown (expired or never existed), returns an error
- Tasks cannot be cancelled once started (cancellation support is future work)
- Both task and task_output tools are excluded from sub-agent registries (no nesting)

Tips:
- Use timeout_ms=0 to quickly check if a task is done without blocking
- For long-running tasks, use a reasonable timeout (e.g., 60000ms) to avoid spinning
- Launch explore agents in background for parallel codebase searches
- Collect results in order of completion by polling all tasks periodically
