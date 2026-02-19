#!/bin/sh
# Quick rebuild + restart: build, stop old daemon, launch CLI
# Usage: ./dev.sh [provider]
#   provider: anthropic (default), openai, ollama, copilot, groq
#   Example: ./dev.sh ollama
set -e

PROVIDER="${1:-}"

export JAVA_HOME="${JAVA_HOME:-$(brew --prefix openjdk@21 2>/dev/null)/libexec/openjdk.jdk/Contents/Home}"

./gradlew :aceclaw-cli:installDist -q

# Stop old daemon (best-effort)
if [ -S ~/.aceclaw/aceclaw.sock ]; then
    ./aceclaw-cli/build/install/aceclaw-cli/bin/aceclaw-cli daemon stop 2>/dev/null || true
    sleep 0.5
fi
# Kill by PID if still alive
if [ -f ~/.aceclaw/aceclaw.pid ]; then
    kill "$(cat ~/.aceclaw/aceclaw.pid)" 2>/dev/null || true
    sleep 0.3
fi

# Set provider via env if specified
if [ -n "$PROVIDER" ]; then
    export ACECLAW_PROVIDER="$PROVIDER"
    echo "🔧 Provider: $PROVIDER"
fi

exec ./aceclaw-cli/build/install/aceclaw-cli/bin/aceclaw-cli
