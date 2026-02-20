Searches the web and returns results with title, URL, and snippet.

Uses Brave Search API when BRAVE_SEARCH_API_KEY is configured, or DuckDuckGo
Lite as a free fallback when no API key is set. Both backends return real-time
search results.

When to use:
- Finding documentation or API references for a library or tool
- Looking up error messages or stack traces for solutions
- Finding current best practices or recommended approaches
- Discovering URLs for resources you need to read with web_fetch
- Getting up-to-date information beyond your training data
- Comparing alternatives or finding community recommendations

When NOT to use:
- You already know the URL - use web_fetch directly
- You need to read a known web page - use web_fetch
- You need to interact with a web page - use browser
- The information is in the local codebase - use grep or read_file
- You need to search local files - use glob or grep

Parameter details:
- query: The search query string. Be specific and include relevant keywords.
  Include the current year for time-sensitive topics (e.g., "React best practices 2025").
- max_results: Number of results to return (default: 5, max: 20).
  Use more results for broad research, fewer for specific lookups.

Query optimization tips:
- Be specific: "Jackson ObjectMapper custom deserializer Java" not just "Jackson"
- Include the programming language or framework in your query
- For error debugging, quote the exact error message: "NullPointerException at HashMap.get"
- For API docs, include the version: "Spring Boot 3.2 @Transactional documentation"
- Use site-specific searches when you know the source: "site:stackoverflow.com Java streams"

Multi-step research workflow:
1. Start broad: web_search for the topic to find relevant sources
2. Read top results: web_fetch on the most promising URLs
3. Refine if needed: web_search with more specific terms from initial results
4. Synthesize: combine findings from multiple sources

Common workflows:
- Research: web_search for topic -> web_fetch top results -> summarize findings
- Error debugging: web_search for error message -> find solution -> apply fix
- Documentation: web_search for "library_name docs" -> web_fetch the doc URL

Result interpretation:
- Snippets provide context but may be truncated - use web_fetch for full content
- Prefer official documentation over blog posts for API details
- Check result dates - older results may reference deprecated APIs
- If results are not helpful, try rephrasing the query with different keywords
- Use web_search to FIND the URL, then web_fetch to READ the content
