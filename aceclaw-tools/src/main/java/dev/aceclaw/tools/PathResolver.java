package dev.aceclaw.tools;

import java.nio.file.Path;

/**
 * Shared path resolution logic for all tools.
 * Handles tilde expansion and relative path resolution against the working directory.
 */
final class PathResolver {

    private PathResolver() {}

    /**
     * Expand tilde (~) to the user's home directory.
     * Java's {@code Path.of("~")} treats ~ as a literal directory name, not the home dir.
     */
    static String expandTilde(String raw) {
        if (raw.startsWith("~/") || raw.equals("~")) {
            return System.getProperty("user.home") + raw.substring(1);
        }
        return raw;
    }

    /**
     * Resolve a file/dir path from user input: expand tilde, then resolve relative paths
     * against the working directory.
     */
    static Path resolve(String raw, Path workingDir) {
        raw = expandTilde(raw);
        var path = Path.of(raw);
        if (path.isAbsolute()) {
            return path;
        }
        return workingDir.resolve(path).normalize();
    }
}
