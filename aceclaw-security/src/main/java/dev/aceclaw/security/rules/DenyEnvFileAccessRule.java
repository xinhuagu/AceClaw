package dev.aceclaw.security.rules;

import dev.aceclaw.security.Capability;
import dev.aceclaw.security.PermissionDecision;
import dev.aceclaw.security.PolicyContext;
import dev.aceclaw.security.Provenance;
import dev.aceclaw.security.Rule;
import dev.aceclaw.security.SearchKind;

import java.nio.file.Path;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Denies filesystem operations that target environment files, private
 * keys, or other credentials regardless of which adapter requested them
 * (#465 Scope #2 — canonical cross-cutting example from the epic:
 * "no {@code .env} reads from any adapter").
 *
 * <h3>What "credential file" means here</h3>
 *
 * <p>Conservative deny-list, biased toward false positives that produce
 * one extra prompt or a clear error rather than silent data leak:
 *
 * <ul>
 *   <li><strong>{@code .env} family</strong> — filename is exactly
 *       {@code .env} or starts with {@code .env.} ({@code .env.local},
 *       {@code .env.production}, etc.). The historical leak vector for
 *       Twelve-Factor apps.</li>
 *   <li><strong>SSH / SSL private keys</strong> — {@code id_rsa},
 *       {@code id_ed25519}, {@code id_dsa}, {@code id_ecdsa}, and any
 *       file ending in {@code .pem} or {@code .key}.</li>
 *   <li><strong>Cloud + tool credentials</strong> — any path whose final
 *       segments contain {@code credentials} (matches
 *       {@code ~/.aws/credentials}, {@code .git-credentials}, etc.).</li>
 *   <li><strong>Token / config files</strong> — {@code .npmrc},
 *       {@code .pypirc}, {@code .netrc}, {@code .docker/config.json}.
 *       These commonly carry registry / auth tokens.</li>
 * </ul>
 *
 * <h3>Why deny instead of prompt</h3>
 *
 * For built-in tools the user already has a prompt path
 * ({@link PermissionDecision.NeedsUserApproval}); a rule that re-prompts
 * doesn't add value over what {@link dev.aceclaw.security.DefaultPermissionPolicy}
 * does for any {@link dev.aceclaw.security.PermissionLevel#WRITE} or
 * {@link dev.aceclaw.security.PermissionLevel#EXECUTE} request. The
 * cross-cutting rule's job is to enforce something the per-level mode
 * cannot: deny the operation regardless of mode (including
 * {@code auto-accept}, which would otherwise silently approve a
 * credential read). Operators who genuinely need to access a credential
 * file can rename, move it out of the workspace, or fork this rule's
 * pattern set.
 *
 * <h3>Applies to which capability variants</h3>
 *
 * {@link Capability.FileRead}, {@link Capability.FileWrite},
 * {@link Capability.FileDelete} on any path matching the deny-list.
 * Read denies because credentials leak via INGRESS to the agent
 * transcript; write/delete deny because they can corrupt operator
 * credentials.
 *
 * <p>{@link Capability.FileSearch} matches conditionally:
 * {@link SearchKind#GREP} rooted directly at a credential file is
 * denied (it would disclose the file's contents to the agent), while
 * {@link SearchKind#GLOB} / {@link SearchKind#LIST} are not — listing
 * a directory that contains a {@code .env} doesn't disclose contents,
 * and denying glob would break the normal "find my dotfiles" flow.
 * The narrower form ("grep parent directory that happens to traverse
 * an env file") is a real but harder vector, intentionally left for a
 * follow-up rule that walks the search expansion.
 *
 * <p>{@link Capability.McpInvoke} cannot be matched here because the
 * MCP variant doesn't carry path arguments (by design — args may be
 * huge / contain secrets); MCP-side enforcement lives in the MCP
 * normaliser layer.
 */
public final class DenyEnvFileAccessRule implements Rule {

    /**
     * Filename basename patterns that trigger denial. Match is performed
     * against the {@link Path#getFileName()} portion only, so
     * directory matches (e.g. anything inside {@code .ssh/}) require the
     * {@link #DIRECTORY_SEGMENT_PATTERN} below.
     */
    private static final Pattern FILENAME_PATTERN = Pattern.compile(
            "^(?:"
                    + "\\.env"                   // .env (exact)
                    + "|\\.env\\..+"             // .env.local, .env.production, ...
                    + "|id_(?:rsa|dsa|ecdsa|ed25519)" // SSH private key conventional names
                    + "|.+\\.(?:pem|key|kdbx|keystore|jks|p12|pfx)" // cert / keystore extensions
                    + "|\\.netrc|\\.npmrc|\\.pypirc" // dot-config files with auth tokens
                    + "|\\.git-credentials"
                    + "|credentials(?:\\.json)?"  // ~/.aws/credentials, gcloud credentials.json
                    + ")$");

    /**
     * Path-segment patterns that trigger denial regardless of basename —
     * anything under {@code .ssh/}, {@code .aws/}, {@code .gnupg/},
     * {@code .docker/} (config.json carries auth), or {@code .config/gcloud/}
     * is treated as credential material.
     */
    private static final Pattern DIRECTORY_SEGMENT_PATTERN = Pattern.compile(
            "^(?:\\.ssh|\\.aws|\\.gnupg|\\.docker|gcloud)$");

    @Override
    public Optional<PermissionDecision> evaluate(
            Capability capability, Provenance provenance, PolicyContext context) {
        Path path = switch (capability) {
            case Capability.FileRead r -> r.path();
            case Capability.FileWrite w -> w.path();
            case Capability.FileDelete d -> d.path();
            // GREP rooted at a credential file reads the file's bytes
            // and ships matches into the agent transcript — same leak
            // shape as FileRead. GLOB / LIST are defer (see class
            // javadoc) — they don't disclose contents.
            case Capability.FileSearch s when s.kind() == SearchKind.GREP -> s.root();
            default -> null; // not a path-bearing filesystem op this rule covers
        };
        if (path == null) return Optional.empty();
        if (!isCredentialPath(path)) return Optional.empty();

        return Optional.of(new PermissionDecision.Denied(
                "Capability blocked by policy: " + capability.displayLabel()
                        + " (path matches credential-file deny-list — rule="
                        + name() + ")"));
    }

    /**
     * Visible for testing — true if {@code path} matches the basename or
     * directory-segment pattern set.
     */
    static boolean isCredentialPath(Path path) {
        Path fileName = path.getFileName();
        if (fileName != null && FILENAME_PATTERN.matcher(fileName.toString()).matches()) {
            return true;
        }
        for (Path segment : path) {
            if (DIRECTORY_SEGMENT_PATTERN.matcher(segment.toString()).matches()) {
                return true;
            }
        }
        return false;
    }
}
