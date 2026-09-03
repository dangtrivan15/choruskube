package com.choruskube.core.service;

import com.choruskube.core.exception.ForbiddenException;
import java.util.UUID;

/**
 * The default {@link WorkerAuthorizer}: this server has exactly one Fleet, so every run is on it
 * and the only question left is whether the credential is the configured one.
 *
 * <p><b>{@code runId} is deliberately unread.</b> With one Fleet there is no run a valid Worker is
 * not entitled to; a deployment that serves more than one Fleet replaces this bean rather than
 * adding a check here.
 *
 * <p><b>Not a Spring bean.</b> {@code WorkerWorkloadController} holds it as the fallback behind an
 * {@code ObjectProvider}, the same arrangement as {@link SingleFleetWorkerRegistrar}, so an
 * implementation replaces it by existing. {@code @ConditionalOnMissingBean} would decide the
 * Worker's fate on bean scan order outside auto-configuration.
 */
public class SingleFleetWorkerAuthorizer implements WorkerAuthorizer {

    private final byte[] expectedTokenDigest;

    /**
     * @param registrationToken the shared secret every Worker presents, the same value {@link
     *     SingleFleetWorkerRegistrar} checks at registration. Blank configures no Worker that may
     *     act at all, so the routes fail closed instead of becoming anonymous.
     */
    public SingleFleetWorkerAuthorizer(String registrationToken) {
        this.expectedTokenDigest =
                (registrationToken == null || registrationToken.isBlank()) ? null : TokenDigest.of(registrationToken);
    }

    @Override
    public void requireMayActOn(String credential, UUID runId) {
        byte[] expected = expectedTokenDigest;
        if (expected == null) {
            throw new ForbiddenException("Worker registration is not configured on this server");
        }
        if (!TokenDigest.matches(expected, credential)) {
            throw new ForbiddenException("Unknown or revoked worker credential");
        }
    }
}
