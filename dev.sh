#!/bin/sh
# Quick rebuild + restart: build, stop old daemon, launch CLI
# Usage: ./dev.sh [provider]
#   provider: anthropic (default), openai, ollama, copilot, groq
#   Example: ./dev.sh ollama
set -e

PROVIDER="${1:-}"
VALID_PROVIDERS="anthropic openai ollama copilot groq"

# Auto-detect JAVA_HOME if not set
if [ -z "$JAVA_HOME" ]; then
    BREW_JDK="$(brew --prefix openjdk@21 2>/dev/null)/libexec/openjdk.jdk/Contents/Home"
    if [ -d "$BREW_JDK" ]; then
        export JAVA_HOME="$BREW_JDK"
    fi
fi

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

# Validate and set provider via env if specified
if [ -n "$PROVIDER" ]; then
    case " $VALID_PROVIDERS " in
        *" $PROVIDER "*) ;;
        *) echo "❌ Invalid provider: $PROVIDER"; echo "Valid: $VALID_PROVIDERS"; exit 1 ;;
    esac
    export ACECLAW_PROVIDER="$PROVIDER"
    echo "🔧 Provider: $PROVIDER"
fi

exec ./aceclaw-cli/build/install/aceclaw-cli/bin/aceclaw-cli
