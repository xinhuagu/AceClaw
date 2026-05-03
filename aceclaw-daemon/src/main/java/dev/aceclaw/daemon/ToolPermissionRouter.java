package dev.aceclaw.daemon;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.aceclaw.core.agent.Tool;
import dev.aceclaw.security.Capability;
import dev.aceclaw.security.CapabilityAware;
import dev.aceclaw.security.PermissionDecision;
import dev.aceclaw.security.PermissionLevel;
import dev.aceclaw.security.PermissionManager;
import dev.aceclaw.security.PermissionRequest;
import dev.aceclaw.security.Provenance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Objects;

/**
 * Picks the right entry point on {@link PermissionManager} for a given tool
 * call (#480 PR 2). Extracted from {@link StreamingAgentHandler} so the
 * branching contract — "tools that implement {@link CapabilityAware} take
 * the structured path; everything else takes legacy" — is testable without
 * standing up the full agent loop.
 *
 * <h3>Three outcomes for a {@link CapabilityAware} tool</h3>
 *
 * <ol>
 *   <li>{@code toCapability(...)} returns a non-null {@link Capability} →
 *       call {@link PermissionManager#check(Capability, Provenance, String, String)}.
 *       The originating tool's name is the allowlist key (so historical
 *       "always allow {@code write_file}" approvals keep applying), and the
 *       caller-supplied human description carries through to the user prompt.</li>
 *   <li>{@code toCapability(...)} throws {@link RuntimeException} or
 *       {@link IOException} (parse failure, bad args) → log a warning and
 *       fall back to the legacy {@link PermissionRequest} path so the user
 *       still gets a meaningful approval prompt instead of an opaque crash.</li>
 *   <li>{@code toCapability(...)} returns {@code null} → contract violation;
 *       throw {@link IllegalStateException}. Silently downgrading to legacy
 *       here would mask a broken migration and drop structured policy/audit
 *       data. (Codex + CodeRabbit reviews on #482.)</li>
 * </ol>
 *
 * <h3>What does NOT trigger the fallback</h3>
 *
 * Errors raised by {@code permissionManager.check} itself (policy / audit /
 * runtime) are propagated as-is. Re-running the legacy path on those would
 * mask real bugs and could change the decision pipeline behind the user's
 * back. The fallback is strictly for the args-to-Capability conversion step.
 */
final class ToolPermissionRouter {

    private static final Logger log = LoggerFactory.getLogger(ToolPermissionRouter.class);

    private ToolPermissionRouter() { /* static-only */ }

    /**
     * Routes a tool call to the structured or legacy permission entry point.
     *
     * @param delegate        the tool being invoked (may or may not be {@link CapabilityAware})
     * @param inputJson       the JSON args the LLM supplied to the tool
     * @param sessionId       the owning session id, or {@code null} for daemon-internal calls
     * @param description     rich human-readable description for the approval prompt
     * @param fallbackLevel   risk level used when going through the legacy path
     * @param permissionManager the manager that evaluates policy
     * @param mapper          Jackson mapper, used to parse {@code inputJson} for capability conversion
     * @return the manager's decision
     * @throws IllegalStateException if a {@link CapabilityAware} tool's
     *         {@code toCapability(...)} returns {@code null} (contract violation)
     */
    static PermissionDecision check(
            Tool delegate,
            String inputJson,
            String sessionId,
            String description,
            PermissionLevel fallbackLevel,
            PermissionManager permissionManager,
            ObjectMapper mapper) {
        Objects.requireNonNull(delegate, "delegate");
        Objects.requireNonNull(inputJson, "inputJson");
        Objects.requireNonNull(description, "description");
        Objects.requireNonNull(fallbackLevel, "fallbackLevel");
        Objects.requireNonNull(permissionManager, "permissionManager");
        Objects.requireNonNull(mapper, "mapper");

        if (!(delegate instanceof CapabilityAware capAware)) {
            var legacyRequest = new PermissionRequest(delegate.name(), description, fallbackLevel);
            return permissionManager.check(legacyRequest, sessionId);
        }

        Capability capability;
        boolean conversionThrew = false;
        try {
            capability = capAware.toCapability(mapper.readTree(inputJson));
        } catch (RuntimeException | IOException toCapErr) {
            log.warn("CapabilityAware tool {} rejected args; falling back to legacy permission path: {}",
                    delegate.name(), toCapErr.getMessage());
            capability = null;
            conversionThrew = true;
        }
        if (capability == null && !conversionThrew) {
            throw new IllegalStateException(
                    "CapabilityAware tool " + delegate.name()
                            + " returned null capability (contract violation)");
        }
        if (capability != null) {
            var provenance = Provenance.fromNullableSessionId(sessionId);
            return permissionManager.check(capability, provenance, delegate.name(), description);
        }
        var legacyRequest = new PermissionRequest(delegate.name(), description, fallbackLevel);
        return permissionManager.check(legacyRequest, sessionId);
    }
}
