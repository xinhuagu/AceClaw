#!/bin/sh
# Attach to a running AceClaw daemon, or start one if none is running.
# Unlike dev.sh, this never stops/restarts the daemon and never runs benchmarks.
#
# Can be run from any directory — paths resolve relative to the AceClaw repo.
#
# Usage: ./tui.sh [provider]
#   provider: anthropic (default), openai, openai-codex, ollama, copilot, groq
#   Example: ./tui.sh ollama
set -e

# Resolve the AceClaw repo root from the script's own location
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

# Build CLI distribution if it doesn't exist yet (paths relative to repo root)
CLI_BIN="$SCRIPT_DIR/aceclaw-cli/build/install/aceclaw-cli/bin/aceclaw-cli"
if [ ! -x "$CLI_BIN" ]; then
    echo ">> First run: building CLI..."
    "$SCRIPT_DIR/gradlew" -p "$SCRIPT_DIR" :aceclaw-cli:installDist -q
fi

# Validate and set provider via env if specified
if [ -n "$PROVIDER" ]; then
    case " $VALID_PROVIDERS " in
        *" $PROVIDER "*) ;;
        *) echo "Invalid provider: $PROVIDER"; echo "Valid: $VALID_PROVIDERS"; exit 1 ;;
    esac
    export ACECLAW_PROVIDER="$PROVIDER"
fi

# No bench mode for TUI sessions
export ACECLAW_BENCH_MODE="none"

exec "$CLI_BIN"
