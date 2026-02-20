Invoke a skill to perform a specialized task.

Skills are structured knowledge packages with instructions, reference materials,
and supporting files. Each skill has a name and optional arguments.

Available skills are listed in the input schema. Use the `arguments` parameter
to pass context to the skill.

When to use this tool:
- The task matches a specific skill's description
- You need specialized instructions or templates for a task type
- The user explicitly requests a skill by name (e.g., "/commit", "/review")

When NOT to use this tool:
- For general-purpose tasks better handled directly with other tools
- When no matching skill exists in the registry
- For tasks requiring interactive user input during execution

Skills run in two modes:
- Inline: the skill instructions are returned for you to follow directly in the
  current conversation. Use this for skills that need access to conversation context.
- Fork: a sub-agent executes the skill instructions independently with its own
  context window. Use this for self-contained tasks that benefit from isolation.

Skill configuration format (.aceclaw/skills/):
- Each skill is defined in a markdown file (SKILL.md format)
- Skills can specify allowed/disallowed tools for their execution context
- Skills can include argument substitution using {{arg_name}} placeholders

Argument substitution:
- Pass arguments via the `arguments` parameter as a string
- Arguments are substituted into the skill's instruction template
- For structured arguments, use "key=value" pairs separated by spaces
- The entire arguments string is available as {{arguments}} in the template

Tips:
- Check the skill's description to understand what arguments it expects
- Inline mode is faster for simple skills; fork mode for complex multi-step tasks
- If a skill fails, check that required arguments were provided
- Skills inherit the working directory from the parent agent
- Forked skills run as sub-agents with their own tool set and context window
- Skill execution respects the same permission model as other tools
