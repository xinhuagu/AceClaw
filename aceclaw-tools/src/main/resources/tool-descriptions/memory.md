Manages persistent memory that survives across sessions. Memories are HMAC-signed
and stored as JSONL files. Use this to learn from experience and avoid repeating mistakes.

Four actions:
- save: Store a new memory. Requires content and category.
- search: Find relevant memories using a natural language query. Uses hybrid TF-IDF + recency ranking.
- list: Browse memories, optionally filtered by category.
- delete: Remove an incorrect or outdated memory. Requires id (from list/search output).

When to save (IMPORTANT — actively save learnings):
- After discovering a bug pattern, gotcha, or non-obvious behavior
- When the user corrects you — save as CORRECTION or MISTAKE
- When learning a project convention, naming pattern, or coding style preference
- After finding a strategy that worked well for a particular type of task
- When noting environment-specific configuration (paths, versions, tools)
- After a failed approach — save what went wrong and what worked instead
- When discovering relationships between components or systems

When to search:
- Before starting a complex or unfamiliar task — check if past sessions have relevant insights
- When encountering an error that might have been seen before
- When unsure about project conventions or preferences
- When working on a component you've previously modified

When to list:
- To review what has been learned about the project
- To check if a memory already exists before saving a duplicate
- To get an overview of accumulated knowledge

When to delete:
- When a memory is incorrect, outdated, or no longer applies
- When correcting a previous mistake — delete the wrong memory, save the corrected one
- When a convention or preference has changed
- Use list/search first to find the ID, then delete by ID

Anti-patterns — do NOT:
- Save trivial or obvious information (basic language syntax, common patterns)
- Search on every single turn — only search when genuinely useful
- Save information already present in the system prompt or CLAUDE.md
- Save raw file contents — summarize the INSIGHT instead
- Save duplicate memories — list/search first if you think it might exist already

Writing good memory content:
- Be concise and actionable — one clear insight per memory, not a wall of text
- Focus on the WHY and the FIX, not just the symptom
- Include enough context that future-you can understand without re-reading the source
- Good: "Gradle 8.14 removed javaPlatform.allowDependenciesInConstraints() — just omit the call"
- Good: "User prefers no Co-Authored-By lines in commit messages"
- Bad: "There was an error in the build" (too vague, no actionable insight)
- Bad: entire file contents copy-pasted (use a summary instead)

Categories:
- mistake: Errors made and lessons learned
- pattern: Code patterns, naming conventions, architectural patterns
- preference: User preferences for code style, tools, workflows
- codebase_insight: Knowledge about the specific codebase structure
- strategy: Approaches that work well for certain tasks
- workflow: Multi-step processes and procedures
- environment: System config, paths, versions, tool setup
- relationship: How components, modules, or systems connect
- terminology: Project-specific terms and their meanings
- constraint: Limitations, rules, or requirements to follow
- decision: Architectural or design decisions and their rationale
- tool_usage: Tips for using specific tools or commands effectively
- communication: User communication preferences
- context: Background information about the project or task
- correction: User corrections to agent behavior
- bookmark: Important file paths or references to remember
- session_summary: Summary of a session's key actions and outcomes
- error_recovery: An error encountered and how it was resolved
- successful_strategy: A strategy that proved successful for a specific task type
- anti_pattern: An approach that failed or should be avoided
- user_feedback: User feedback about agent behavior (positive or negative)

Parameter details:
- action: save, search, list, or delete (required)
- content: The memory text (required for save). Write concise, actionable insights.
- category: One of the categories above (required for save, optional filter for search/list)
- tags: Comma-separated tags for categorization (optional, for save). Helps narrow future searches.
- query: Natural language search query (required for search). Be specific for better results.
- limit: Max results (default: 10 for search, 20 for list, max: 50)
- global: If true, memory is cross-project. Default: false (project-scoped).
- id: Memory entry ID (required for delete). Get the ID from list/search output first.

Common workflows:
- Learn from mistake: encounter error → fix it → save with category=mistake and tags
- Pre-task check: search for relevant memories → review insights → start the task informed
- Convention capture: user states a preference → save with category=preference → apply in future
- Dedup check: search or list by category → verify no similar memory exists → save if new
- Self-correction: realize memory is wrong → list to find ID → delete → save corrected version

Search scoring:
- Results are ranked by a hybrid score: TF-IDF text relevance + recency boost.
- Recent memories score higher when relevance is similar — fresh insights beat stale ones.
- Use specific, descriptive queries for better ranking (not single keywords).
- Category filter narrows results before scoring, improving precision.

Memory hierarchy (8 tiers — this tool manages tier 6):
- Tiers 1-5 (Soul/Policy/Workspace/User/Local): Loaded automatically, NOT managed here.
- Tier 6 (Auto-Memory): What THIS tool manages — agent-learned insights (JSONL).
- Tier 7 (Markdown Memory): MEMORY.md + topic files — write via standard file tools.
- Tier 8 (Journal): Daily log, appended automatically. Don't duplicate journal content.
- Context compaction auto-extracts file paths, commands, errors — save INSIGHTS instead.
- Path-based rules from {project}/.aceclaw/rules/*.md inject contextual instructions.

Safety & constraints:
- Memory files are HMAC-SHA256 signed — tampered entries are rejected on load.
- Project-scoped memories (global=false) are stored per working directory (SHA-256 hash).
- Global memories (global=true) are shared across all projects — use sparingly.
- Never save secrets, API keys, passwords, or credentials as memories.
- Memory storage is JSONL — entries can be added and deleted. Use delete to correct mistakes.
- Do not save information that tiers 0-3 already provide (SOUL.md, ACECLAW.md, etc.).

Tips:
- Use tags liberally — they improve future search precision (e.g., "gradle, java, build").
- Save immediately after learning something, not at end of session.
- Search with natural phrases, not single keywords.
- When in doubt, save it — redundant memory < re-discovering the same insight.
- Use category filter in search when you know the insight type needed.