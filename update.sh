#!/bin/sh
# Update AceClaw to the latest release.
# Downloads the latest pre-built release — no git or build tools required.
#
# If run from a git checkout (developer), falls back to git pull + rebuild.
#
# Usage: aceclaw-update
set -e

# Resolve symlinks to find the real script location
SELF="$0"
while [ -L "$SELF" ]; do
    DIR="$(cd "$(dirname "$SELF")" && pwd)"
    SELF="$(readlink "$SELF")"
    case "$SELF" in /*) ;; *) SELF="$DIR/$SELF" ;; esac
done
SCRIPT_DIR="$(cd "$(dirname "$SELF")" && pwd)"

REPO="xinhuagu/AceClaw"

info()  { printf '  \033[1;34m>\033[0m %s\n' "$1"; }
ok()    { printf '  \033[1;32m✓\033[0m %s\n' "$1"; }
warn()  { printf '  \033[1;33m!\033[0m %s\n' "$1"; }
fail()  { printf '  \033[1;31m✗\033[0m %s\n' "$1"; exit 1; }

echo ""
echo "  AceClaw Update"
echo "  ──────────────"
echo ""

# ---------------------------------------------------------------------------
# Detect mode: release install vs git checkout
# ---------------------------------------------------------------------------
if [ -d "$SCRIPT_DIR/.git" ]; then
    # Developer mode: git pull + rebuild
    info "Detected git checkout — updating from source"
    cd "$SCRIPT_DIR"

    # Auto-detect JAVA_HOME if not set
    if [ -z "$JAVA_HOME" ]; then
        DETECTED_JDK="$(/usr/libexec/java_home -v 21 2>/dev/null || true)"
        if [ -n "$DETECTED_JDK" ] && [ -d "$DETECTED_JDK" ]; then
            export JAVA_HOME="$DETECTED_JDK"
        fi
    fi

    OLD_HEAD=$(git rev-parse HEAD)
    git pull --ff-only || fail "git pull failed. Resolve conflicts manually."
    NEW_HEAD=$(git rev-parse HEAD)

    if [ "$OLD_HEAD" = "$NEW_HEAD" ]; then
        ok "Already up to date"
        echo ""
        exit 0
    fi

    COMMIT_COUNT=$(git rev-list --count "$OLD_HEAD".."$NEW_HEAD")
    ok "Updated: $COMMIT_COUNT new commit(s)"

    info "Rebuilding CLI..."
    "$SCRIPT_DIR/gradlew" -p "$SCRIPT_DIR" :aceclaw-cli:installDist -q
    ok "Build complete"

    # Restart daemon if idle
    CLI_BIN="$SCRIPT_DIR/aceclaw-cli/build/install/aceclaw-cli/bin/aceclaw-cli"
    if [ -S ~/.aceclaw/aceclaw.sock ] && [ -x "$CLI_BIN" ]; then
        ACTIVE_SESSIONS=$("$CLI_BIN" daemon status 2>/dev/null | sed -n 's/.*Active Sessions: *//p' || echo "0")
        if [ "$ACTIVE_SESSIONS" -gt 0 ] 2>/dev/null; then
            warn "Daemon has $ACTIVE_SESSIONS active session(s) — not restarting"
            echo "  Run 'aceclaw-restart' when ready."
        else
            info "Restarting daemon..."
            "$CLI_BIN" daemon stop 2>/dev/null || true
            sleep 0.5
            ok "Daemon stopped. Will auto-start on next launch."
        fi
    fi

    echo ""
    ok "AceClaw updated from source!"
    echo ""
    exit 0
fi

# ---------------------------------------------------------------------------
# Release mode: download latest release archive
# ---------------------------------------------------------------------------
INSTALL_DIR="$SCRIPT_DIR"

# Check current version
CURRENT_VERSION=""
if [ -f "$INSTALL_DIR/VERSION" ]; then
    CURRENT_VERSION=$(cat "$INSTALL_DIR/VERSION")
    info "Current version: $CURRENT_VERSION"
else
    info "Current version: unknown"
fi

# Fetch latest release
info "Checking for updates..."
if command -v curl >/dev/null 2>&1; then
    LATEST_TAG=$(curl -fsSL "https://api.github.com/repos/$REPO/releases/latest" | sed -n 's/.*"tag_name": *"\([^"]*\)".*/\1/p')
else
    LATEST_TAG=$(wget -qO- "https://api.github.com/repos/$REPO/releases/latest" | sed -n 's/.*"tag_name": *"\([^"]*\)".*/\1/p')
fi

if [ -z "$LATEST_TAG" ]; then
    fail "Could not determine latest release."
fi

LATEST_VERSION="${LATEST_TAG#v}"

if [ "$CURRENT_VERSION" = "$LATEST_VERSION" ]; then
    ok "Already up to date ($LATEST_VERSION)"
    echo ""
    exit 0
fi

info "Updating: $CURRENT_VERSION -> $LATEST_VERSION"

# Require daemon to be stopped before replacing binaries
CLI_BIN="$INSTALL_DIR/bin/aceclaw-cli"
if [ -S "$HOME/.aceclaw/aceclaw.sock" ] && [ -x "$CLI_BIN" ]; then
    ACTIVE_SESSIONS=$("$CLI_BIN" daemon status 2>/dev/null | sed -n 's/.*Active Sessions: *//p' || echo "0")
    if [ "$ACTIVE_SESSIONS" -gt 0 ] 2>/dev/null; then
        fail "Daemon has $ACTIVE_SESSIONS active session(s). Stop all sessions first, then re-run aceclaw-update."
    fi
    info "Stopping daemon before update..."
    "$CLI_BIN" daemon stop 2>/dev/null || true
    sleep 0.5
    ok "Daemon stopped"
fi

# Download
ARCHIVE_NAME="aceclaw-cli-${LATEST_VERSION}.tar"
DOWNLOAD_URL="https://github.com/$REPO/releases/download/$LATEST_TAG/$ARCHIVE_NAME"
TMP_DIR=$(mktemp -d)

info "Downloading $ARCHIVE_NAME..."
if command -v curl >/dev/null 2>&1; then
    curl -fsSL -o "$TMP_DIR/$ARCHIVE_NAME" "$DOWNLOAD_URL" || {
        ARCHIVE_NAME="aceclaw-cli-${LATEST_VERSION}.zip"
        DOWNLOAD_URL="https://github.com/$REPO/releases/download/$LATEST_TAG/$ARCHIVE_NAME"
        curl -fsSL -o "$TMP_DIR/$ARCHIVE_NAME" "$DOWNLOAD_URL" || fail "Download failed"
    }
else
    wget -q -O "$TMP_DIR/$ARCHIVE_NAME" "$DOWNLOAD_URL" || {
        ARCHIVE_NAME="aceclaw-cli-${LATEST_VERSION}.zip"
        DOWNLOAD_URL="https://github.com/$REPO/releases/download/$LATEST_TAG/$ARCHIVE_NAME"
        wget -q -O "$TMP_DIR/$ARCHIVE_NAME" "$DOWNLOAD_URL" || fail "Download failed"
    }
fi

# Extract (keep config, memory, workspaces — only replace bin/lib/scripts)
info "Extracting..."
rm -rf "${INSTALL_DIR:?}/bin" "${INSTALL_DIR:?}/lib"

case "$ARCHIVE_NAME" in
    *.tar.gz) tar -xzf "$TMP_DIR/$ARCHIVE_NAME" -C "$INSTALL_DIR" --strip-components=1 ;;
    *.tar)    tar -xf "$TMP_DIR/$ARCHIVE_NAME" -C "$INSTALL_DIR" --strip-components=1 ;;
    *.zip)    unzip -qo "$TMP_DIR/$ARCHIVE_NAME" -d "$TMP_DIR/extract"
              cp -r "$TMP_DIR/extract"/aceclaw-cli-*/* "$INSTALL_DIR/" ;;
esac

rm -rf "$TMP_DIR"
chmod +x "$INSTALL_DIR/bin/"* 2>/dev/null || true
chmod +x "$INSTALL_DIR/"*.sh 2>/dev/null || true

echo ""
ok "AceClaw updated to $LATEST_VERSION!"
echo "  Daemon will auto-start on next aceclaw/aceclaw-tui launch."
echo ""
