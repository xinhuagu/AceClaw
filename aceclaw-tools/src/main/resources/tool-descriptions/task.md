Delegates a task to a sub-agent with an isolated context window. Use this when a task
would benefit from focused, independent work without polluting the main conversation.

Available agent types:
- explore: Fast read-only codebase search (uses haiku model). Best for finding files,
  definitions, patterns, and understanding code structure.
- plan: Research and planning with read-only access plus bash. Best for investigating
  issues, profiling performance, and designing implementation approaches.
- general: Full tool access for complex multi-step tasks. Best for self-contained
  implementation work like writing a function, fixing a bug in a specific module, or
  refactoring a component.
- bash: Isolated terminal execution. Best for running build commands, tests, or scripts
  where only command output is needed.

When to use sub-agents:
- Searching across many files for a pattern or definition (use explore)
- Investigating a bug before fixing it (use plan)
- Implementing a self-contained change in parallel (use general)
- Running a build or test suite (use bash)
- Any task that benefits from a fresh context window

When NOT to use sub-agents:
- Simple file reads or single-command execution (use tools directly)
- Tasks requiring the full conversation context
- Tasks that need user interaction or permission approval
- Trivial operations that would be slower through delegation overhead

Background execution:
- Set run_in_background=true to launch the sub-agent asynchronously
- Returns a task_id immediately without waiting for completion
- Use the task_output tool to check status or retrieve results later
- Background tasks are ideal for long-running builds, tests, or research
- Multiple background tasks can run concurrently on virtual threads
- Background tasks are lost on daemon restart (in-memory only)

Guidelines:
- Write clear, specific prompts with ALL context the sub-agent needs
- The sub-agent has NO access to the parent conversation history
- Include file paths, function names, error messages, and constraints in the prompt
- Sub-agents cannot spawn other sub-agents (no nesting)
- Use lower max_turns for simple tasks to save tokens (e.g., max_turns=3 for reads)
- The sub-agent's text response is returned as the tool result

Tips:
- For parallel work, launch multiple background tasks then collect results
- If a sub-agent fails, check that required files exist and paths are correct
- Explore agents are cheapest (haiku model) - use them for search before general agents
- Custom agent types may also be available if configured in .aceclaw/agents/*.md files
- Background tasks have a 30-minute cleanup after completion to prevent memory leaks
