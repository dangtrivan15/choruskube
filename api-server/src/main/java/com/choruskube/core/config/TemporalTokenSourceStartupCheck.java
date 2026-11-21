package com.choruskube.core.config;

import com.choruskube.core.credential.ControlPlaneTemporalTokens;
import java.util.ArrayList;
import java.util.List;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Refuses to boot once a control-plane token source is wired in unless {@code InternalAuthFilter}
 * is actually enforcing with an orchestrator secret: tokens travel to the orchestrator on {@code
 * GET /internal/placements}, and with no secret configured that filter lets every request through
 * regardless of mode. No {@code @Profile} exclusion: unlike the cloud overlay's equivalent check,
 * no test here wires a token source without also setting the two properties it requires, so the
 * check is a no-op in every context that doesn't.
 */
@Component
@Lazy(false)
class TemporalTokenSourceStartupCheck {

    TemporalTokenSourceStartupCheck(ControlPlaneTemporalTokens tokens, Environment env) {
        if (!tokens.issuesTokens()) {
            return;
        }
        String hash = env.getProperty("internal.auth.orchestrator-secret-hash");
        String mode = env.getProperty("internal.auth.mode");

        List<String> problems = new ArrayList<>();
        if (!"enforce".equalsIgnoreCase(mode)) {
            problems.add("INTERNAL_AUTH_MODE must be enforce.");
        }
        if (hash == null || hash.isBlank()) {
            problems.add("ORCHESTRATOR_SECRET_HASH must be a non-blank SHA-256 hex digest.");
        }
        if (!problems.isEmpty()) {
            throw new IllegalStateException(String.join(" ", problems));
        }
    }
}
