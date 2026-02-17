# Claude API Integration Reference for AceClaw

## API Endpoint
```
POST https://api.anthropic.com/v1/messages
```

## Required Headers
```
x-api-key: <API_KEY>
anthropic-version: 2023-06-01
content-type: application/json
```

## Request Schema
```json
{
  "model": "claude-sonnet-4-5-20250929",
  "max_tokens": 4096,
  "messages": [...],
  "system": "...",
  "stream": true,
  "tools": [...],
  "tool_choice": {"type": "auto"}
}
```

## SSE Streaming Event Flow
1. `message_start` - Message with empty content
2. `content_block_start` - Block type: "text" or "tool_use"
3. `content_block_delta` (1+) - text_delta or input_json_delta
4. `content_block_stop`
5. `message_delta` - stop_reason + usage
6. `message_stop` - end of stream
7. `ping` - keepalive (ignore)

## Delta Types
- `text_delta`: `{"type":"text_delta","text":"chunk"}`
- `input_json_delta`: `{"type":"input_json_delta","partial_json":"..."}`
- `thinking_delta`: `{"type":"thinking_delta","thinking":"..."}`

## Stop Reasons
- `end_turn` - Normal completion
- `tool_use` - Wants to execute tool(s)
- `max_tokens` - Hit token limit
- `stop_sequence` - Hit stop sequence

## Tool Use Flow
1. Send messages + tools array
2. Claude responds with tool_use content blocks + stop_reason:"tool_use"
3. Execute tools locally (parallel if multiple)
4. Send tool_result blocks back (matching tool_use_id)
5. Claude responds with final answer or more tool calls

## Tool Definition Format
```json
{
  "name": "read_file",
  "description": "Read a file from the filesystem",
  "input_schema": {
    "type": "object",
    "properties": {
      "path": {"type": "string", "description": "Absolute file path"}
    },
    "required": ["path"]
  }
}
```

## Error Handling
| Status | Type | Action |
|--------|------|--------|
| 400 | invalid_request_error | Don't retry |
| 401 | authentication_error | Don't retry |
| 429 | rate_limit_error | Retry after `retry-after` header |
| 500 | api_error | Exponential backoff |
| 529 | overloaded_error | Exponential backoff (2-5s start) |

## Rate Limit Headers
- `retry-after` - Seconds to wait
- `anthropic-ratelimit-requests-remaining` - Remaining RPM
- `anthropic-ratelimit-input-tokens-remaining` - Remaining ITPM
- `anthropic-ratelimit-output-tokens-remaining` - Remaining OTPM

## Model IDs
- `claude-opus-4-6` - Most intelligent
- `claude-sonnet-4-5-20250929` - Best balance
- `claude-haiku-4-5-20251001` - Fastest/cheapest

## Implementation Notes
- Use `java.net.http.HttpClient` (no external HTTP deps)
- SSE parsing: line-by-line, `event:` prefix for type, `data:` prefix for JSON
- Use `BodyHandlers.ofLines()` for streaming
- Accumulate `input_json_delta` partials, parse on `content_block_stop`
- Jackson for JSON serialization (already in BOM)
