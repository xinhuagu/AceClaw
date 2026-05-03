package dev.aceclaw.security;

/**
 * Distinguishes the three flavors of {@link Capability.FileWrite} so a
 * "create only" policy can refuse overwrites without inspecting filesystem
 * state, and an audit reader can tell at a glance whether a write replaced
 * existing content.
 */
public enum WriteMode {
    /** Fail if the path already exists. */
    CREATE_NEW,
    /** Replace the file's content if it exists; create otherwise. */
    OVERWRITE,
    /** Append to existing content; create if missing. */
    APPEND
}
