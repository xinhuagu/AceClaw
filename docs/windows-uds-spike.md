# Windows UDS Spike: AF_UNIX Feasibility on Windows 10/11 + JDK 21

## Conclusion

**AF_UNIX works on Windows 10 1803+ with JDK 21. No transport abstraction needed for the MVP.**

AceClaw can continue using `UnixDomainSocketAddress` on Windows without introducing named pipes, loopback TCP, or a transport abstraction layer.

## Evidence

### JDK Support
- [JEP 380](https://openjdk.org/jeps/380) (Java 16) added `UnixDomainSocketAddress` with explicit Windows support
- The JEP states: "Tests run on all supported Unix platforms and on multiple versions of Windows"
- The API is stable and unchanged in JDK 17, 21, and beyond
- `StandardProtocolFamily.UNIX` works on both `ServerSocketChannel.open()` and `SocketChannel.open()`

### Windows OS Support
- AF_UNIX available since Windows 10 Build 17063 (Fall Creators Update, 2017)
- Windows 10 1803+ and Windows 11 all support it
- Windows Server 2019+ supports it

### Path Length
- AF_UNIX paths limited to 108 bytes (Linux), 104 bytes (macOS)
- AceClaw socket: `~/.aceclaw/aceclaw.sock` = ~35-50 bytes depending on username
- Windows equivalent: `C:\Users\<user>\.aceclaw\aceclaw.sock` = ~45-55 bytes
- Well within limits on all platforms

## Code Audit: What Works, What Needs Fixing

### UdsListener.java — Works on Windows with one fix

| Line | Code | Windows Status |
|------|------|----------------|
| 87 | `ServerSocketChannel.open(StandardProtocolFamily.UNIX)` | Works |
| 88 | `UnixDomainSocketAddress.of(socketPath)` | Works |
| 92 | `Files.setPosixFilePermissions(...)` | Already guarded with `UnsupportedOperationException` catch |
| 85 | `Files.deleteIfExists(socketPath)` | Works |

**Verdict: UdsListener is Windows-ready.** The POSIX permissions fallback at line 92-95 already handles non-POSIX filesystems.

### DaemonConnection.java — Works on Windows as-is

| Line | Code | Windows Status |
|------|------|----------------|
| 63 | `SocketChannel.open(StandardProtocolFamily.UNIX)` | Works |
| 62 | `UnixDomainSocketAddress.of(socketPath)` | Works |
| All I/O | `channel.read()`, `channel.write()`, `Selector` | Works (standard NIO) |

**Verdict: DaemonConnection is Windows-ready.** No changes needed.

### DaemonStarter.java — NOT Windows-ready (main blocker)

| Line | Code | Windows Issue |
|------|------|---------------|
| 97 | `Path.of(javaHome, "bin", "java")` | Must be `java.exe` on Windows |
| 107 | Builds shell command string with single quotes | Windows `cmd.exe` uses double quotes |
| 111-113 | `/usr/bin/setsid`, `/bin/sh` | Unix-only; no Windows equivalent |
| 116-117 | `trap '' INT; exec ...` | Bash syntax, not cmd.exe |
| 125 | `Path.of("/dev/null")` | Does not exist on Windows; use `NUL` |

**Verdict: DaemonStarter is the primary Windows blocker.** Needs platform-aware refactoring (Step 2).

### DaemonClient.java — Works on Windows as-is

Uses `DaemonConnection.connect()` internally. No platform-specific code.

## Recommendation

1. **Do NOT introduce transport abstraction** — AF_UNIX works on the target Windows versions
2. **Do NOT introduce named pipes or loopback TCP** — unnecessary complexity, would break the zero-network-surface promise
3. **Focus refactoring on DaemonStarter.java** — this is the only real blocker in the IPC/transport path
4. **Keep the socket path at `~/.aceclaw/aceclaw.sock`** — works on all platforms, well within length limits

## Risk

- **Low risk**: Users on Windows 10 pre-1803 (2017) cannot use AF_UNIX. This is acceptable for a JDK 21 project (JDK 21 itself requires modern Windows).
- **Low risk**: Some enterprise environments disable AF_UNIX. Mitigation: clear error message on `SocketException` at startup.

## Implementation Status

All steps completed:

- **Step 1**: Spike confirmed AF_UNIX works on Windows 10 1803+ with JDK 21
- **Step 2**: DaemonStarter refactored into platform-aware launchers (Linux/macOS/Windows)
- **Step 3**: ReadFileTool charset detection uses BOM on Windows (fail-safe, no guessing)
- **Step 4**: CI runs Windows + macOS smoke tests (assemble + targeted cross-platform tests)
- **Step 5**: Native `.cmd` wrappers for Windows (aceclaw, aceclaw-tui, aceclaw-restart, aceclaw-update)
- **Step 6**: README platform support matrix, Windows marked as experimental
