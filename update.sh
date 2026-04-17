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
    fail "This is a git checkout, not a release install. To update from source:
    cd $SCRIPT_DIR && git pull && ./gradlew :aceclaw-cli:installDist -q"
fi

if [ ! -f "$SCRIPT_DIR/VERSION" ] || [ ! -x "$SCRIPT_DIR/bin/aceclaw-cli" ]; then
    fail "Not a valid release install (missing VERSION or bin/aceclaw-cli).
  If this is a source checkout, use: git pull && ./gradlew :aceclaw-cli:installDist -q"
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
# Use --progress-bar / --show-progress so users see a live indicator on the ~210MB
# archive; both flags render only when stderr is a tty, so piped / scripted use
# (e.g. install.sh over curl | sh) still gets silent output.
if command -v curl >/dev/null 2>&1; then
    curl -fL --progress-bar -o "$TMP_DIR/$ARCHIVE_NAME" "$DOWNLOAD_URL" || {
        ARCHIVE_NAME="aceclaw-cli-${LATEST_VERSION}.zip"
        DOWNLOAD_URL="https://github.com/$REPO/releases/download/$LATEST_TAG/$ARCHIVE_NAME"
        curl -fL --progress-bar -o "$TMP_DIR/$ARCHIVE_NAME" "$DOWNLOAD_URL" || fail "Download failed"
    }
else
    wget -q --show-progress -O "$TMP_DIR/$ARCHIVE_NAME" "$DOWNLOAD_URL" || {
        ARCHIVE_NAME="aceclaw-cli-${LATEST_VERSION}.zip"
        DOWNLOAD_URL="https://github.com/$REPO/releases/download/$LATEST_TAG/$ARCHIVE_NAME"
        wget -q --show-progress -O "$TMP_DIR/$ARCHIVE_NAME" "$DOWNLOAD_URL" || fail "Download failed"
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
