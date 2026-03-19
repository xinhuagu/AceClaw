#!/usr/bin/env bash
# Test script: verify Anthropic API connectivity with different beta combinations
# Reads OAuth token from macOS Keychain (same as OpenClaw)

set -euo pipefail

API_URL="https://api.anthropic.com/v1/messages"

# Read fresh token from macOS Keychain (like OpenClaw does)
KEYCHAIN_JSON=$(security find-generic-password -s "Claude Code-credentials" -w 2>/dev/null)
TOKEN=$(echo "$KEYCHAIN_JSON" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d['claudeAiOauth']['accessToken'])")
REFRESH=$(echo "$KEYCHAIN_JSON" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d['claudeAiOauth']['refreshToken'])")
EXPIRES=$(echo "$KEYCHAIN_JSON" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d['claudeAiOauth']['expiresAt'])")

echo "Token prefix: ${TOKEN:0:20}..."
echo "Expires: $(python3 -c "import datetime; print(datetime.datetime.fromtimestamp($EXPIRES/1000))")"
echo "Is OAuth: $(echo "$TOKEN" | grep -q 'sk-ant-oat' && echo 'YES' || echo 'NO')"
echo ""

# Minimal request body
BODY='{"model":"claude-haiku-4-5-20251001","max_tokens":64,"messages":[{"role":"user","content":"Say hello in 5 words"}]}'

run_test() {
  local label="$1"
  local betas="$2"
  echo "=== TEST: $label ==="
  echo "  betas: $betas"

  HTTP_CODE=$(curl -s -o /tmp/aceclaw-beta-test.json -w "%{http_code}" \
    -X POST "$API_URL" \
    -H "Content-Type: application/json" \
    -H "anthropic-version: 2023-06-01" \
    -H "Authorization: Bearer $TOKEN" \
    -H "anthropic-beta: $betas" \
    -H "user-agent: claude-cli/2.1.50 (external, cli)" \
    -H "x-app: cli" \
    --data "$BODY" \
    --max-time 30)

  echo "  status: $HTTP_CODE"
  if [ "$HTTP_CODE" = "200" ]; then
    TEXT=$(python3 -c "import json; r=json.load(open('/tmp/aceclaw-beta-test.json')); print(r['content'][0]['text'][:80])" 2>/dev/null || echo "(parse error)")
    echo "  response: $TEXT"
  else
    python3 -c "import json; r=json.load(open('/tmp/aceclaw-beta-test.json')); print('  error:', json.dumps(r.get('error',r), indent=2)[:200])" 2>/dev/null || cat /tmp/aceclaw-beta-test.json
  fi
  echo ""
}

# Test 1: AceClaw's current OAuth betas
run_test "AceClaw OAuth (current)" \
  "claude-code-20250219,oauth-2025-04-20,fine-grained-tool-streaming-2025-05-14,interleaved-thinking-2025-05-14"

# Test 2: OpenClaw minimal OAuth betas
run_test "Minimal OAuth betas" \
  "claude-code-20250219,oauth-2025-04-20"

# Test 3: OpenClaw + context-1m (should be rejected for OAuth)
run_test "OAuth + context-1m (OpenClaw filters this out)" \
  "claude-code-20250219,oauth-2025-04-20,fine-grained-tool-streaming-2025-05-14,interleaved-thinking-2025-05-14,context-1m-2025-08-07"

# Test 4: Missing OAuth betas (should fail)
run_test "API-key betas only (missing OAuth betas)" \
  "fine-grained-tool-streaming-2025-05-14,interleaved-thinking-2025-05-14"

echo "Done."
