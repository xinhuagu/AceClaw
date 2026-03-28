#!/bin/sh
# Quick daemon restart: rebuild CLI, stop old daemon, launch CLI.
# No benchmarks, no checks — just restart as fast as possible.
#
# Usage: ./restart.sh [provider]
#   provider: anthropic (default), openai, openai-codex, ollama, copilot, groq
set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

# Parse optional provider
PROVIDER=""
for arg in "$@"; do
    case "$arg" in
        *) PROVIDER="$arg" ;;
    esac
done

VALID_PROVIDERS="anthropic openai openai-codex ollama copilot groq"

# Auto-detect JAVA_HOME if not set — require exact Java 21
if [ -z "$JAVA_HOME" ]; then
    DETECTED_JDK="$(/usr/libexec/java_home -v 21 2>/dev/null || true)"
    if [ -n "$DETECTED_JDK" ] && [ -d "$DETECTED_JDK" ]; then
        export JAVA_HOME="$DETECTED_JDK"
    fi
fi

# Build
"$SCRIPT_DIR/gradlew" -p "$SCRIPT_DIR" :aceclaw-cli:installDist -q

# Stop old daemon (best-effort) — warn about active sessions
CLI_BIN="$SCRIPT_DIR/aceclaw-cli/build/install/aceclaw-cli/bin/aceclaw-cli"
if [ -S ~/.aceclaw/aceclaw.sock ]; then
    ACTIVE_SESSIONS=$("$CLI_BIN" daemon status 2>/dev/null | sed -n 's/.*Active Sessions: *//p' || echo "0")
    if [ "$ACTIVE_SESSIONS" -gt 0 ] 2>/dev/null; then
        echo ">> WARNING: Restarting daemon with $ACTIVE_SESSIONS active session(s)"
        echo ">> Other connected TUI windows will be disconnected."
    fi
    "$CLI_BIN" daemon stop 2>/dev/null || true
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
        *) echo "Invalid provider: $PROVIDER"; echo "Valid: $VALID_PROVIDERS"; exit 1 ;;
    esac
    export ACECLAW_PROVIDER="$PROVIDER"
fi

# No benchmarks
export ACECLAW_BENCH_MODE="none"

exec "$CLI_BIN"
