#!/bin/sh
# AceClaw installer — download pre-built release and install CLI commands.
#
# Usage:
#   curl -fsSL https://raw.githubusercontent.com/xinhuagu/AceClaw/main/install.sh | sh
#
# What it does:
#   1. Checks prerequisites (Java 21 runtime)
#   2. Downloads the latest release from GitHub
#   3. Extracts to ~/.aceclaw/
#   4. Creates aceclaw, aceclaw-tui, aceclaw-restart, aceclaw-update commands
#
# No build tools required — only Java 21 runtime.
# Supports: macOS, Linux, Windows (Git Bash / WSL)
set -e

REPO="xinhuagu/AceClaw"
INSTALL_DIR="$HOME/.aceclaw"
BIN_DIR=""
PLATFORM=""

# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------
info()  { printf '  \033[1;34m>\033[0m %s\n' "$1"; }
ok()    { printf '  \033[1;32m✓\033[0m %s\n' "$1"; }
warn()  { printf '  \033[1;33m!\033[0m %s\n' "$1"; }
fail()  { printf '  \033[1;31m✗\033[0m %s\n' "$1"; exit 1; }

# ---------------------------------------------------------------------------
# Detect OS and pick bin directory
# ---------------------------------------------------------------------------
detect_platform() {
    OS="$(uname -s)"
    case "$OS" in
        Darwin)  PLATFORM="macos" ;;
        Linux)   PLATFORM="linux" ;;
        MINGW*|MSYS*|CYGWIN*)  PLATFORM="windows" ;;
        *)       fail "Unsupported OS: $OS" ;;
    esac

    if [ "$PLATFORM" = "windows" ]; then
        BIN_DIR="$HOME/bin"
    elif [ -d "$HOME/.local/bin" ] && echo "$PATH" | grep -q "$HOME/.local/bin"; then
        BIN_DIR="$HOME/.local/bin"
    elif [ -d "$HOME/bin" ] && echo "$PATH" | grep -q "$HOME/bin"; then
        BIN_DIR="$HOME/bin"
    elif [ -d "/usr/local/bin" ] && [ -w "/usr/local/bin" ]; then
        BIN_DIR="/usr/local/bin"
    else
        BIN_DIR="$HOME/.local/bin"
    fi
}

# ---------------------------------------------------------------------------
# Check prerequisites (only Java runtime — no git, no Gradle)
# ---------------------------------------------------------------------------
check_prereqs() {
    info "Checking prerequisites..."

    JAVA_CMD=""
    if [ -n "$JAVA_HOME" ] && [ -x "$JAVA_HOME/bin/java" ]; then
        JAVA_CMD="$JAVA_HOME/bin/java"
    elif command -v java >/dev/null 2>&1; then
        JAVA_CMD="java"
    elif [ "$PLATFORM" = "macos" ]; then
        DETECTED_JDK="$(/usr/libexec/java_home -v 21 2>/dev/null || true)"
        if [ -n "$DETECTED_JDK" ] && [ -d "$DETECTED_JDK" ]; then
            export JAVA_HOME="$DETECTED_JDK"
            JAVA_CMD="$JAVA_HOME/bin/java"
        fi
    fi

    if [ -z "$JAVA_CMD" ]; then
        fail "Java 21+ required but not found. Set JAVA_HOME or install Java 21."
    fi

    JAVA_VERSION=$("$JAVA_CMD" -version 2>&1 | head -1 | sed 's/.*"\([0-9]*\)\..*/\1/')
    if [ "$JAVA_VERSION" -ge 21 ] 2>/dev/null; then
        ok "Java $JAVA_VERSION found"
    else
        fail "Java 21+ required, found Java $JAVA_VERSION"
    fi

    # Need curl or wget for downloading
    if command -v curl >/dev/null 2>&1; then
        DOWNLOAD_CMD="curl"
    elif command -v wget >/dev/null 2>&1; then
        DOWNLOAD_CMD="wget"
    else
        fail "curl or wget required for downloading releases."
    fi
}

# ---------------------------------------------------------------------------
# Fetch latest release tag from GitHub API
# ---------------------------------------------------------------------------
fetch_latest_version() {
    info "Fetching latest release..."
    if [ "$DOWNLOAD_CMD" = "curl" ]; then
        LATEST_TAG=$(curl -fsSL "https://api.github.com/repos/$REPO/releases/latest" | sed -n 's/.*"tag_name": *"\([^"]*\)".*/\1/p')
    else
        LATEST_TAG=$(wget -qO- "https://api.github.com/repos/$REPO/releases/latest" | sed -n 's/.*"tag_name": *"\([^"]*\)".*/\1/p')
    fi

    if [ -z "$LATEST_TAG" ]; then
        fail "Could not determine latest release. Check https://github.com/$REPO/releases"
    fi

    VERSION="${LATEST_TAG#v}"
    ok "Latest version: $VERSION"
}

# ---------------------------------------------------------------------------
# Download and extract release
# ---------------------------------------------------------------------------
download_release() {
    ARCHIVE_NAME="aceclaw-cli-${VERSION}.tar"
    DOWNLOAD_URL="https://github.com/$REPO/releases/download/$LATEST_TAG/$ARCHIVE_NAME"
    TMP_DIR=$(mktemp -d)

    info "Downloading $ARCHIVE_NAME..."
    if [ "$DOWNLOAD_CMD" = "curl" ]; then
        curl -fsSL -o "$TMP_DIR/$ARCHIVE_NAME" "$DOWNLOAD_URL" || {
            # Try .zip if .tar not found
            ARCHIVE_NAME="aceclaw-cli-${VERSION}.zip"
            DOWNLOAD_URL="https://github.com/$REPO/releases/download/$LATEST_TAG/$ARCHIVE_NAME"
            curl -fsSL -o "$TMP_DIR/$ARCHIVE_NAME" "$DOWNLOAD_URL" || fail "Download failed: $DOWNLOAD_URL"
        }
    else
        wget -q -O "$TMP_DIR/$ARCHIVE_NAME" "$DOWNLOAD_URL" || {
            ARCHIVE_NAME="aceclaw-cli-${VERSION}.zip"
            DOWNLOAD_URL="https://github.com/$REPO/releases/download/$LATEST_TAG/$ARCHIVE_NAME"
            wget -q -O "$TMP_DIR/$ARCHIVE_NAME" "$DOWNLOAD_URL" || fail "Download failed: $DOWNLOAD_URL"
        }
    fi
    ok "Downloaded"

    info "Extracting to $INSTALL_DIR..."
    mkdir -p "$INSTALL_DIR"

    # Remove old dist if exists (keep config, memory, workspaces)
    rm -rf "$INSTALL_DIR/bin" "$INSTALL_DIR/lib"

    case "$ARCHIVE_NAME" in
        *.tar.gz) tar -xzf "$TMP_DIR/$ARCHIVE_NAME" -C "$INSTALL_DIR" --strip-components=1 ;;
        *.tar)    tar -xf "$TMP_DIR/$ARCHIVE_NAME" -C "$INSTALL_DIR" --strip-components=1 ;;
        *.zip)    unzip -qo "$TMP_DIR/$ARCHIVE_NAME" -d "$TMP_DIR/extract"
                  cp -r "$TMP_DIR/extract"/aceclaw-cli-*/* "$INSTALL_DIR/" ;;
    esac

    rm -rf "$TMP_DIR"

    # Make scripts executable
    chmod +x "$INSTALL_DIR/bin/"* 2>/dev/null || true
    chmod +x "$INSTALL_DIR/"*.sh 2>/dev/null || true

    ok "Extracted to $INSTALL_DIR"
}

# ---------------------------------------------------------------------------
# Install commands
# ---------------------------------------------------------------------------
install_commands() {
    info "Installing commands to $BIN_DIR..."
    mkdir -p "$BIN_DIR"

    if [ "$PLATFORM" = "windows" ]; then
        install_windows_cmd
    else
        install_unix_symlinks
    fi
}

install_unix_symlinks() {
    ln -sf "$INSTALL_DIR/bin/aceclaw-cli" "$BIN_DIR/aceclaw"
    ln -sf "$INSTALL_DIR/tui.sh" "$BIN_DIR/aceclaw-tui"
    ln -sf "$INSTALL_DIR/restart.sh" "$BIN_DIR/aceclaw-restart"
    ln -sf "$INSTALL_DIR/update.sh" "$BIN_DIR/aceclaw-update"

    ok "Installed: aceclaw, aceclaw-tui, aceclaw-restart, aceclaw-update"
}

install_windows_cmd() {
    local CLI_BAT="%USERPROFILE%\.aceclaw\bin\aceclaw-cli.bat"

    cat > "$BIN_DIR/aceclaw.cmd" <<CMDEOF
@echo off
call "$CLI_BAT" %*
CMDEOF

    cat > "$BIN_DIR/aceclaw-tui.cmd" <<CMDEOF
@echo off
set ACECLAW_BENCH_MODE=none
call "$CLI_BAT" %*
CMDEOF

    cat > "$BIN_DIR/aceclaw-update.cmd" <<CMDEOF
@echo off
echo Use: curl -fsSL https://raw.githubusercontent.com/$REPO/main/install.sh | sh
echo to update AceClaw on Windows.
CMDEOF

    ok "Installed: aceclaw.cmd, aceclaw-tui.cmd, aceclaw-update.cmd"
    warn "Windows support is experimental — see https://github.com/$REPO/issues/357"
}

# ---------------------------------------------------------------------------
# Verify PATH
# ---------------------------------------------------------------------------
verify_path() {
    case ":$PATH:" in
        *":$BIN_DIR:"*) ;;
        *)
            warn "$BIN_DIR is not in your PATH"
            echo ""
            case "$SHELL" in
                */zsh)  echo "  Add to ~/.zshrc:   export PATH=\"$BIN_DIR:\$PATH\"" ;;
                */bash) echo "  Add to ~/.bashrc:  export PATH=\"$BIN_DIR:\$PATH\"" ;;
                *)      echo "  Add to your shell profile:  export PATH=\"$BIN_DIR:\$PATH\"" ;;
            esac
            echo ""
            ;;
    esac
}

# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------
main() {
    echo ""
    echo "  AceClaw Installer"
    echo "  ─────────────────"
    echo ""

    detect_platform
    check_prereqs
    fetch_latest_version
    download_release
    install_commands
    verify_path

    echo ""
    ok "AceClaw $VERSION installed successfully!"
    echo ""
    echo "  Commands available:"
    echo "    aceclaw          Start AceClaw (auto-starts daemon)"
    echo "    aceclaw-tui      Open another TUI window (non-destructive)"
    echo "    aceclaw-restart  Rebuild + restart daemon (no benchmarks)"
    echo "    aceclaw-update   Update to latest release"
    echo ""
}

main
