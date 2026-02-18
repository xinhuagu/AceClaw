#!/bin/sh
# Quick rebuild + restart: build, stop old daemon, launch CLI
set -e

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

exec ./aceclaw-cli/build/install/aceclaw-cli/bin/aceclaw-cli
