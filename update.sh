#!/bin/sh
# Update AceClaw: pull latest code, rebuild CLI, restart daemon if running.
#
# Usage: aceclaw-update
set -e

# Resolve symlinks to find the real script location (not the symlink dir)
SELF="$0"
while [ -L "$SELF" ]; do
    DIR="$(cd "$(dirname "$SELF")" && pwd)"
    SELF="$(readlink "$SELF")"
    case "$SELF" in /*) ;; *) SELF="$DIR/$SELF" ;; esac
done
SCRIPT_DIR="$(cd "$(dirname "$SELF")" && pwd)"

info()  { printf '  \033[1;34m>\033[0m %s\n' "$1"; }
ok()    { printf '  \033[1;32m✓\033[0m %s\n' "$1"; }
warn()  { printf '  \033[1;33m!\033[0m %s\n' "$1"; }
fail()  { printf '  \033[1;31m✗\033[0m %s\n' "$1"; exit 1; }

# Auto-detect JAVA_HOME if not set
if [ -z "$JAVA_HOME" ]; then
    DETECTED_JDK="$(/usr/libexec/java_home -v 21 2>/dev/null || true)"
    if [ -n "$DETECTED_JDK" ] && [ -d "$DETECTED_JDK" ]; then
        export JAVA_HOME="$DETECTED_JDK"
    fi
fi

echo ""
echo "  AceClaw Update"
echo "  ──────────────"
echo ""

# Pull latest
info "Pulling latest changes..."
cd "$SCRIPT_DIR"
OLD_HEAD=$(git rev-parse HEAD)
git pull --ff-only || fail "git pull failed. Resolve conflicts manually in $SCRIPT_DIR"
NEW_HEAD=$(git rev-parse HEAD)

if [ "$OLD_HEAD" = "$NEW_HEAD" ]; then
    ok "Already up to date"
    echo ""
    exit 0
fi

COMMIT_COUNT=$(git rev-list --count "$OLD_HEAD".."$NEW_HEAD")
ok "Updated: $COMMIT_COUNT new commit(s)"

# Rebuild
info "Rebuilding CLI..."
"$SCRIPT_DIR/gradlew" -p "$SCRIPT_DIR" :aceclaw-cli:installDist -q
ok "Build complete"

# Restart daemon if running
CLI_BIN="$SCRIPT_DIR/aceclaw-cli/build/install/aceclaw-cli/bin/aceclaw-cli"
if [ -S ~/.aceclaw/aceclaw.sock ]; then
    ACTIVE_SESSIONS=$("$CLI_BIN" daemon status 2>/dev/null | sed -n 's/.*Active Sessions: *//p' || echo "0")
    if [ "$ACTIVE_SESSIONS" -gt 0 ] 2>/dev/null; then
        warn "Daemon has $ACTIVE_SESSIONS active session(s) — not restarting automatically"
        echo "  Run 'aceclaw-restart' to restart the daemon when ready."
    else
        info "Restarting daemon..."
        "$CLI_BIN" daemon stop 2>/dev/null || true
        sleep 0.5
        ok "Daemon stopped. It will auto-start on next aceclaw/aceclaw-tui launch."
    fi
else
    ok "No daemon running — nothing to restart"
fi

echo ""
ok "AceClaw updated successfully!"
echo ""
