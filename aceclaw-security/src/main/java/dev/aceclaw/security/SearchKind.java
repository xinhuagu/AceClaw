package dev.aceclaw.security;

/**
 * Distinguishes the three flavors of {@link Capability.FileSearch} so a
 * "no recursive grep on /etc" policy can refuse content searches without
 * having to also forbid filename-only listings, and an audit reader can
 * tell at a glance what the agent was looking for.
 *
 * <ul>
 *   <li>{@code GLOB} — filename pattern match (e.g. {@code **\/*.java}).
 *       Walks the tree but only inspects names. Cheaper, no content read.</li>
 *   <li>{@code GREP} — content pattern match. Opens every matching file
 *       and reads its bytes; policies that care about secret-bearing files
 *       (.env, credentials) should treat this as more sensitive than {@code GLOB}.</li>
 *   <li>{@code LIST} — single-directory listing. No recursion, no content.
 *       The lowest-impact form.</li>
 * </ul>
 */
public enum SearchKind {
    GLOB,
    GREP,
    LIST
}
