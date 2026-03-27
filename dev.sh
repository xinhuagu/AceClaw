#!/bin/sh
# Quick rebuild + restart: build, stop old daemon, launch CLI
# Usage: ./dev.sh [--check | --baseline | --auto | --no-bench] [provider]
#   (no flag)  : auto-select benchmark mode on feature branches, skip on main
#   --check    : run local benchmark check before launching
#   --baseline : run benchmark check + baseline export before launching
#   --auto     : auto-select --check or --baseline based on changed files
#   --no-bench : skip benchmarks even on feature branches
#   provider   : anthropic (default), openai, openai-codex, ollama, copilot, groq
#   Example: ./dev.sh ollama          # auto-detect if on feature branch
#   Example: ./dev.sh --no-bench      # quick restart, skip benchmarks
set -e

# ---------------------------------------------------------------------------
# Parse arguments: flags first, then positional provider
# ---------------------------------------------------------------------------
BENCH_MODE=""
PROVIDER=""

for arg in "$@"; do
    case "$arg" in
        --check)    BENCH_MODE="check" ;;
        --baseline) BENCH_MODE="baseline" ;;
        --auto)     BENCH_MODE="auto" ;;
        --no-bench) BENCH_MODE="none" ;;
        *)          PROVIDER="$arg" ;;
    esac
done

VALID_PROVIDERS="anthropic openai openai-codex ollama copilot groq"

# ---------------------------------------------------------------------------
# Detect current branch — auto-enable benchmark mode on feature branches
# ---------------------------------------------------------------------------
CURRENT_BRANCH="$(git rev-parse --abbrev-ref HEAD 2>/dev/null || echo "unknown")"

if [ -z "$BENCH_MODE" ] && [ "$CURRENT_BRANCH" != "main" ] && [ "$CURRENT_BRANCH" != "unknown" ]; then
    BENCH_MODE="auto"
fi

# Auto-detect JAVA_HOME if not set — require exact Java 21
if [ -z "$JAVA_HOME" ]; then
    # macOS: use java_home utility which correctly resolves registered JVMs
    DETECTED_JDK="$(/usr/libexec/java_home -v 21 2>/dev/null || true)"
    if [ -n "$DETECTED_JDK" ] && [ -d "$DETECTED_JDK" ]; then
        export JAVA_HOME="$DETECTED_JDK"
    fi
fi

# ---------------------------------------------------------------------------
# Benchmark mode: --auto resolves to --check or --baseline
# ---------------------------------------------------------------------------
if [ "$BENCH_MODE" = "none" ]; then
    BENCH_MODE=""
elif [ "$BENCH_MODE" = "auto" ]; then
    if ! CHANGED_FILES="$(git diff --name-only origin/main...HEAD 2>/dev/null)"; then
        echo ">> --auto: unable to diff against origin/main, falling back to check mode"
        BENCH_MODE="check"
    elif [ -z "$CHANGED_FILES" ]; then
        echo ">> --auto: no diff against origin/main, skipping benchmarks"
        BENCH_MODE=""
    else
        # Patterns that upgrade to baseline mode
        BASELINE_MATCH=""
        while IFS= read -r f; do
            case "$f" in
                aceclaw-memory/*)                                  BASELINE_MATCH=1 ;;
                scripts/generate-replay-report.sh)                 BASELINE_MATCH=1 ;;
                scripts/replay-quality-gate.sh)                    BASELINE_MATCH=1 ;;
                scripts/collect-continuous-learning-baseline.sh)   BASELINE_MATCH=1 ;;
                scripts/export-injection-audit-summary.sh)         BASELINE_MATCH=1 ;;
                *metrics*|*scorecard*|*rollout*|*candidate*|*replay*|*learning*) BASELINE_MATCH=1 ;;
            esac
        done <<EOF
$CHANGED_FILES
EOF

        if [ -n "$BASELINE_MATCH" ]; then
            BENCH_MODE="baseline"
            echo ">> --auto: detected learning/benchmark sensitive changes -> baseline mode"
        else
            BENCH_MODE="check"
            echo ">> --auto: normal feature changes -> check mode"
        fi
    fi
fi

# Export bench mode so the CLI can display it in the status line
export ACECLAW_BENCH_MODE="${BENCH_MODE:-none}"

# Run benchmark steps if requested
if [ "$BENCH_MODE" = "check" ]; then
    echo ">> Running local benchmark check..."
    ./gradlew preMergeCheck -PreplayGateStrict=false
elif [ "$BENCH_MODE" = "baseline" ]; then
    echo ">> Running local benchmark check + baseline export..."
    ./gradlew preMergeCheck -PreplayGateStrict=false
    ./scripts/export-injection-audit-summary.sh
    ./scripts/collect-continuous-learning-baseline.sh --output .aceclaw/metrics/continuous-learning/baseline.json
fi

# ---------------------------------------------------------------------------
# Build + restart
# ---------------------------------------------------------------------------
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
        *) echo "Invalid provider: $PROVIDER"; echo "Valid: $VALID_PROVIDERS"; exit 1 ;;
    esac
    export ACECLAW_PROVIDER="$PROVIDER"
    echo "Provider: $PROVIDER"
fi

exec ./aceclaw-cli/build/install/aceclaw-cli/bin/aceclaw-cli
