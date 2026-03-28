#!/bin/sh
# AceClaw installer — clone, build, and install CLI shortcuts.
#
# Usage:
#   curl -fsSL https://raw.githubusercontent.com/xinhuagu/AceClaw/main/install.sh | sh
#
# What it does:
#   1. Checks prerequisites (Java 21, git)
#   2. Clones (or updates) the repo to ~/.aceclaw/src
#   3. Builds the CLI with Gradle
#   4. Creates aceclaw, aceclaw-tui, aceclaw-restart, aceclaw-dev commands
#
# Supports: macOS, Linux, Windows (Git Bash / WSL)
set -e

REPO_URL="https://github.com/xinhuagu/AceClaw.git"
INSTALL_DIR="$HOME/.aceclaw/src"
BIN_DIR=""

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

    # Pick a bin directory on PATH
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
# Check prerequisites
# ---------------------------------------------------------------------------
check_prereqs() {
    info "Checking prerequisites..."

    # Git
    if ! command -v git >/dev/null 2>&1; then
        fail "git is required but not found. Install git first."
    fi
    ok "git found"

    # Java 21 — check JAVA_HOME first, then PATH, then macOS java_home
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
}

# ---------------------------------------------------------------------------
# Clone or update repo
# ---------------------------------------------------------------------------
setup_repo() {
    if [ -d "$INSTALL_DIR/.git" ]; then
        info "Updating existing repo at $INSTALL_DIR..."
        cd "$INSTALL_DIR"
        git pull --ff-only || warn "git pull failed, using existing version"
    else
        info "Cloning AceClaw to $INSTALL_DIR..."
        mkdir -p "$(dirname "$INSTALL_DIR")"
        git clone "$REPO_URL" "$INSTALL_DIR"
        cd "$INSTALL_DIR"
    fi
    ok "Repo ready at $INSTALL_DIR"
}

# ---------------------------------------------------------------------------
# Build
# ---------------------------------------------------------------------------
build() {
    info "Building CLI (this may take a minute on first run)..."
    cd "$INSTALL_DIR"
    ./gradlew :aceclaw-cli:installDist -q
    ok "Build complete"
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
    # Main CLI
    ln -sf "$INSTALL_DIR/aceclaw-cli/build/install/aceclaw-cli/bin/aceclaw-cli" "$BIN_DIR/aceclaw"
    # Scripts
    ln -sf "$INSTALL_DIR/tui.sh" "$BIN_DIR/aceclaw-tui"
    ln -sf "$INSTALL_DIR/restart.sh" "$BIN_DIR/aceclaw-restart"
    ln -sf "$INSTALL_DIR/dev.sh" "$BIN_DIR/aceclaw-dev"
    ln -sf "$INSTALL_DIR/update.sh" "$BIN_DIR/aceclaw-update"

    ok "Installed: aceclaw, aceclaw-tui, aceclaw-restart, aceclaw-dev, aceclaw-update"
}

install_windows_cmd() {
    local CLI_BAT="%USERPROFILE%\.aceclaw\src\aceclaw-cli\build\install\aceclaw-cli\bin\aceclaw-cli.bat"

    # aceclaw.cmd
    cat > "$BIN_DIR/aceclaw.cmd" <<CMDEOF
@echo off
call "$CLI_BAT" %*
CMDEOF

    # aceclaw-tui.cmd
    cat > "$BIN_DIR/aceclaw-tui.cmd" <<CMDEOF
@echo off
set ACECLAW_BENCH_MODE=none
call "$CLI_BAT" %*
CMDEOF

    # aceclaw-restart.cmd
    cat > "$BIN_DIR/aceclaw-restart.cmd" <<CMDEOF
@echo off
set ACECLAW_BENCH_MODE=none
cd /d "%USERPROFILE%\.aceclaw\src"
call gradlew.bat :aceclaw-cli:installDist -q
call "$CLI_BAT" daemon stop 2>nul
call "$CLI_BAT" %*
CMDEOF

    # aceclaw-dev.cmd
    cat > "$BIN_DIR/aceclaw-dev.cmd" <<CMDEOF
@echo off
cd /d "%USERPROFILE%\.aceclaw\src"
call gradlew.bat :aceclaw-cli:installDist -q
call "$CLI_BAT" daemon stop 2>nul
call "$CLI_BAT" %*
CMDEOF

    # aceclaw-update.cmd
    cat > "$BIN_DIR/aceclaw-update.cmd" <<CMDEOF
@echo off
cd /d "%USERPROFILE%\.aceclaw\src"
git pull --ff-only || (echo git pull failed & exit /b 1)
call gradlew.bat :aceclaw-cli:installDist -q
echo Update complete.
CMDEOF

    ok "Installed: aceclaw.cmd, aceclaw-tui.cmd, aceclaw-restart.cmd, aceclaw-dev.cmd, aceclaw-update.cmd"
    warn "Windows support is experimental — see https://github.com/xinhuagu/AceClaw/issues/357"
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
    setup_repo
    build
    install_commands
    verify_path

    echo ""
    ok "AceClaw installed successfully!"
    echo ""
    echo "  Commands available:"
    echo "    aceclaw          Start AceClaw (auto-starts daemon)"
    echo "    aceclaw-tui      Open another TUI window (non-destructive)"
    echo "    aceclaw-restart  Rebuild + restart daemon (no benchmarks)"
    echo "    aceclaw-dev      Rebuild + restart + benchmark checks"
    echo "    aceclaw-update   Pull latest + rebuild (safe: skips daemon restart if sessions active)"
    echo ""
}

main
