#!/bin/sh
# Update AceClaw to the latest release.
# Downloads the latest pre-built release — no git or build tools required.
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
INSTALL_DIR="$SCRIPT_DIR"

info()  { printf '  \033[1;34m>\033[0m %s\n' "$1"; }
ok()    { printf '  \033[1;32m✓\033[0m %s\n' "$1"; }
warn()  { printf '  \033[1;33m!\033[0m %s\n' "$1"; }
fail()  { printf '  \033[1;31m✗\033[0m %s\n' "$1"; exit 1; }

echo ""
echo "  AceClaw Update"
echo "  ──────────────"
echo ""

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

# Handle running daemon
CLI_BIN="$INSTALL_DIR/bin/aceclaw-cli"
if [ -S "$HOME/.aceclaw/aceclaw.sock" ] && [ -x "$CLI_BIN" ]; then
    ACTIVE_SESSIONS=$("$CLI_BIN" daemon status 2>/dev/null | sed -n 's/.*Active Sessions: *//p' || echo "0")
    if [ "$ACTIVE_SESSIONS" -gt 0 ] 2>/dev/null; then
        warn "Daemon has $ACTIVE_SESSIONS active session(s) — not restarting automatically"
        echo "  Run 'aceclaw-restart' to restart the daemon when ready."
    else
        info "Stopping idle daemon..."
        "$CLI_BIN" daemon stop 2>/dev/null || true
        sleep 0.5
    fi
fi

# Extract (keep config, memory, workspaces — only replace bin/lib/scripts)
info "Extracting..."
rm -rf "$INSTALL_DIR/bin" "$INSTALL_DIR/lib"

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
