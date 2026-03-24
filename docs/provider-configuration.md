# Provider Configuration

AceClaw supports multiple LLM providers. This guide covers how to configure each one.

## GitHub Copilot (Enterprise)

Use your GitHub Copilot subscription to access a wide range of models — including Claude, GPT, and Codex — without separate API keys.

### Prerequisites

- A **GitHub Copilot** subscription (Individual, Business, or Enterprise)
- One of the following for authentication:
  - [GitHub CLI](https://cli.github.com/) installed and logged in (`gh auth login`)
  - A GitHub Personal Access Token (PAT) with the **copilot** scope

### Authentication

AceClaw resolves your GitHub token automatically in this order:

1. **Cached OAuth token** from device-code flow (`aceclaw-cli copilot auth`)
2. **`apiKey`** in the copilot profile (`~/.aceclaw/config.json`)
3. **`GITHUB_TOKEN`** environment variable
4. **`GH_TOKEN`** environment variable
5. **`gh auth token`** from GitHub CLI

The simplest method is GitHub CLI:

```bash
# Login to GitHub (one-time)
gh auth login

# Start AceClaw with Copilot
./dev.sh copilot
```

Or configure a PAT in `~/.aceclaw/config.json`:

```json
{
    "profiles": {
        "copilot": {
            "provider": "copilot",
            "apiKey": "ghp_your_personal_access_token",
            "model": "claude-sonnet-4.5",
            "maxTokens": 16384,
            "thinkingBudget": 0,
            "contextWindowTokens": 200000
        }
    }
}
```

### Available Models

Copilot exposes models from multiple providers. Use `/model <name>` at runtime to switch:

| Model | API Endpoint | Notes |
|-------|-------------|-------|
| `claude-opus-4.6` | Chat Completions | Anthropic Claude Opus (latest) |
| `claude-sonnet-4.6` | Chat Completions | Anthropic Claude Sonnet (latest) |
| `claude-sonnet-4.5` | Chat Completions | Anthropic Claude Sonnet |
| `claude-haiku-4.5` | Chat Completions | Anthropic Claude Haiku |
| `gpt-4o` | Chat Completions | OpenAI GPT-4o |
| `gpt-5.2-codex` | Responses API | Codex model, optimized for coding |
| `o4-mini` | Chat Completions | OpenAI reasoning model |

AceClaw automatically routes requests to the correct API endpoint — Codex models use the Responses API (`/responses`), all others use Chat Completions (`/chat/completions`).

> **Model name format:** Copilot uses dot-notation for versions (`claude-opus-4.6`), while Anthropic direct API uses hyphen-notation (`claude-opus-4-6`). When switching between providers, use the format for the active provider. If the global config has an Anthropic-format model name (e.g. `claude-opus-4-6`), the Copilot provider ignores it and uses its own default (`claude-sonnet-4.5`).

### Default Model Behavior

When using `./dev.sh copilot`, the model is resolved as follows:
- If a **copilot profile** exists in config with a `model` field → use that model
- If only a **global model** exists and it is an Anthropic-native name → ignore it, use `claude-sonnet-4.5`
- If no model is configured → use `claude-sonnet-4.5`

This means you can keep `"model": "claude-opus-4-6"` in your global config for Anthropic direct API, and `./dev.sh copilot` will still use `claude-sonnet-4.5` without conflict.

### Running

```bash
# Via dev.sh (development)
./dev.sh copilot

# Via environment variable
export ACECLAW_PROVIDER=copilot
./aceclaw-cli/build/install/aceclaw-cli/bin/aceclaw-cli

# Switch models at runtime
aceclaw> /model claude-opus-4.6
aceclaw> /model gpt-5.2-codex
```

---

## OpenAI Codex (OAuth Subscription)

Use Codex OAuth credentials (for example from `codex auth login`) without manually copying short-lived JWTs.

### Authentication Resolution

When `provider` is `openai-codex`, AceClaw resolves token in this order:

1. `apiKey` in profile (explicit override)
2. `~/.codex/auth.json` → `tokens.access_token`
3. `~/.codex/auth.json` → `OPENAI_API_KEY` (legacy)
4. `OPENAI_API_KEY` env var

### Setup

```json
{
    "profiles": {
        "openai-codex": {
            "provider": "openai-codex",
            "model": "gpt-5-codex",
            "maxTokens": 16384,
            "thinkingBudget": 0
        }
    }
}
```

```bash
# Authenticate via AceClaw (runs Codex CLI flow)
aceclaw models auth login --provider openai-codex

# Start AceClaw with openai-codex provider
./dev.sh openai-codex
```

> `openai` and `openai-codex` are different modes. `openai` expects a standard OpenAI API key. `openai-codex` is for Codex OAuth credentials.

### OpenAI Codex Request Notes

`openai-codex` uses ChatGPT Codex backend semantics, not standard OpenAI Responses API semantics.

- AceClaw always sends `stream=true`
- AceClaw always sends `store=false`
- AceClaw does not send `temperature`
- AceClaw does not send `max_output_tokens`

`maxTokens` and `temperature` can remain in your profile for cross-provider consistency, but they are ignored when `provider=openai-codex`.

---

## Anthropic Claude (Direct API)

Use Claude models directly via the Anthropic API. Supports extended thinking, prompt caching, and image input.

### Authentication

AceClaw supports two authentication modes for Anthropic:

#### Option 1: API Key (`sk-ant-api03-*`)

Standard API key — simple, does not expire, no token refresh needed.

```bash
export ANTHROPIC_API_KEY="sk-ant-api03-..."
./dev.sh
```

Or in `~/.aceclaw/config.json`:

```json
{
    "profiles": {
        "claude": {
            "provider": "anthropic",
            "apiKey": "sk-ant-api03-...",
            "model": "claude-sonnet-4-5-20250929",
            "maxTokens": 16384,
            "thinkingBudget": 10240,
            "contextWindowTokens": 200000
        }
    }
}
```

#### Option 2: OAuth Token (`sk-ant-oat01-*`)

Uses Claude CLI's OAuth credentials. Supports automatic token refresh so long-running daemon sessions never disconnect.

**Setup:** Log in with Claude CLI once — AceClaw auto-discovers the credentials:

```bash
# One-time: authenticate with Claude CLI
claude /login

# AceClaw automatically reads from:
#   macOS Keychain: "anthropic.com" → claudeAiOauth.accessToken
#   Fallback file: ~/.claude/.credentials
```

No manual configuration needed. AceClaw reads the OAuth token, refresh token, and expiry from Claude CLI's credential store.

**Token refresh behavior:**
- **Proactive refresh:** Before each API request, the daemon checks if the token is expired or about to expire. If so, it refreshes automatically using the OAuth refresh endpoint — no failed requests, no user intervention.
- **Reactive fallback (401):** If the proactive check misses (e.g., unknown expiry), a 401 response triggers credential recovery: first from Keychain, then via OAuth refresh flow.
- **Credential writeback:** After a successful refresh, the new token is written back to the credential store so daemon restarts pick it up.

**Resolution order** (first match wins):
1. `apiKey` in profile config
2. `ANTHROPIC_API_KEY` environment variable
3. Claude CLI OAuth credentials (Keychain or `~/.claude/.credentials`)

### Features

- Extended thinking (`thinkingBudget > 0`)
- Prompt caching (automatic, reduces latency and cost)
- Image input support
- Proactive OAuth token refresh (sessions run indefinitely)

---

## Ollama (Local Models)

Run models locally with [Ollama](https://ollama.com/) for fully offline, private operation.

### Setup

```bash
# Install Ollama
brew install ollama     # macOS
# or: curl -fsSL https://ollama.com/install.sh | sh  # Linux

# Pull a model
ollama pull qwen3:32b

# Start AceClaw with Ollama
./dev.sh ollama
```

Configure in `~/.aceclaw/config.json`:

```json
{
    "profiles": {
        "ollama": {
            "provider": "ollama",
            "model": "qwen3:32b",
            "maxTokens": 4096,
            "thinkingBudget": 0,
            "contextWindowTokens": 40960
        }
    }
}
```

> **Note on model size:** AceClaw is an autonomous agent that performs multi-step reasoning, tool selection, and JSON-structured tool calls. Small language models (8B, 14B parameters) lack the capacity to reliably handle these tasks — they frequently produce malformed tool calls, hallucinate tool names, or lose track of multi-turn context. **Use models with at least 32B parameters** (e.g., `qwen3:32b`, `llama-3.3-70b`) for acceptable agent performance. 70B+ models are recommended for complex tasks.

---

## Other OpenAI-Compatible Providers

AceClaw works with any provider that implements the OpenAI Chat Completions API.

| Provider | Config Name | Default Base URL |
|----------|-------------|-----------------|
| OpenAI | `openai` | `https://api.openai.com` |
| OpenAI Codex OAuth | `openai-codex` | `https://chatgpt.com/backend-api/codex` |
| Groq | `groq` | `https://api.groq.com/openai` |
| Together | `together` | `https://api.together.xyz` |
| Mistral | `mistral` | `https://api.mistral.ai` |

### Example (Groq)

```json
{
    "profiles": {
        "groq": {
            "provider": "groq",
            "apiKey": "gsk_...",
            "model": "llama-3.3-70b-versatile",
            "maxTokens": 4096,
            "thinkingBudget": 0,
            "contextWindowTokens": 131072
        }
    }
}
```

```bash
./dev.sh groq
```

---

## Configuration Reference

### Config File Location

- **Global**: `~/.aceclaw/config.json`
- **Project**: `{project}/.aceclaw/config.json` (overrides global)

### Profile Selection

Profiles are selected in this order:

1. `ACECLAW_PROFILE` env var (explicit profile name)
2. `ACECLAW_PROVIDER` env var (uses matching profile if it exists)
3. `defaultProfile` field in config.json

### Environment Variables

Environment variables take highest precedence and override config file values:

| Variable | Purpose |
|----------|---------|
| `ACECLAW_PROVIDER` | Provider name (`anthropic`, `copilot`, `ollama`, etc.) |
| `ACECLAW_PROFILE` | Named profile to activate |
| `ACECLAW_MODEL` | Model identifier override |
| `ACECLAW_BASE_URL` | Custom API base URL |
| `ACECLAW_LOG_LEVEL` | Log level (`DEBUG`, `INFO`, `WARN`) |
| `ANTHROPIC_API_KEY` | Anthropic API key |
| `OPENAI_API_KEY` | OpenAI API key (fallback for non-Anthropic providers) |
| `GITHUB_TOKEN` | GitHub token for Copilot |
| `GH_TOKEN` | GitHub token for Copilot (alternative) |

### Full Config Example

```json
{
    "defaultProfile": "claude",
    "profiles": {
        "claude": {
            "provider": "anthropic",
            "apiKey": "sk-ant-api03-...",
            "model": "claude-sonnet-4-5-20250929",
            "maxTokens": 16384,
            "thinkingBudget": 10240,
            "contextWindowTokens": 200000
        },
        "copilot": {
            "provider": "copilot",
            "model": "claude-sonnet-4.5",
            "maxTokens": 16384,
            "thinkingBudget": 0,
            "contextWindowTokens": 200000
        },
        "ollama": {
            "provider": "ollama",
            "model": "qwen3:32b",
            "maxTokens": 4096,
            "thinkingBudget": 0,
            "contextWindowTokens": 40960
        }
    }
}
```
