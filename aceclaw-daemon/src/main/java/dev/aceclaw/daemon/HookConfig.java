package dev.aceclaw.daemon;

/**
 * Configuration for a single hook command.
 *
 * @param type    the hook type (currently only "command" is supported)
 * @param command the shell command to execute
 * @param timeout timeout in seconds (default 60, max 600)
 */
public record HookConfig(String type, String command, int timeout) {

    /** Default timeout in seconds. */
    public static final int DEFAULT_TIMEOUT = 60;

    /** Maximum allowed timeout in seconds (10 minutes). */
    public static final int MAX_TIMEOUT = 600;

    /**
     * Creates a HookConfig with validated timeout.
     */
    public HookConfig {
        if (type == null || type.isBlank()) {
            throw new IllegalArgumentException("Hook type must not be blank");
        }
        if (!"command".equals(type)) {
            throw new IllegalArgumentException("Unsupported hook type: " + type + " (only 'command' is supported)");
        }
        if (command == null || command.isBlank()) {
            throw new IllegalArgumentException("Hook command must not be blank");
        }
        if (timeout <= 0) {
            timeout = DEFAULT_TIMEOUT;
        }
        if (timeout > MAX_TIMEOUT) {
            timeout = MAX_TIMEOUT;
        }
    }

    /**
     * Creates a command hook with default timeout.
     */
    public static HookConfig command(String cmd) {
        return new HookConfig("command", cmd, DEFAULT_TIMEOUT);
    }

    /**
     * Creates a command hook with specified timeout.
     */
    public static HookConfig command(String cmd, int timeoutSeconds) {
        return new HookConfig("command", cmd, timeoutSeconds);
    }
}
