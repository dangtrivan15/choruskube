package com.choruskube.core.service;

import java.net.URI;
import java.util.Optional;
import java.util.UUID;

/**
 * Default {@link ArtifactSigningEndpointResolver}: every run signs against the server's own
 * object-store endpoint.
 *
 * <p><b>Not a Spring bean.</b> {@code PresignService} holds it as the {@code ObjectProvider}
 * fallback, so a real implementation replaces it by existing rather than by bean-scan ordering.
 */
public class OwnArtifactSigningEndpoint implements ArtifactSigningEndpointResolver {

    @Override
    public Optional<URI> endpointFor(UUID runId) {
        return Optional.empty();
    }
}
