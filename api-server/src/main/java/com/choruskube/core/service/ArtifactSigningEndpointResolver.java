package com.choruskube.core.service;

import java.net.URI;
import java.util.Optional;
import java.util.UUID;

/**
 * Names, per run, the object-store endpoint to sign an agent's presigned URL for. A deployment
 * whose agents cannot reach the server's own object-store endpoint names, per run, the endpoint to
 * sign for instead; the default resolves every run to the server's own endpoint.
 *
 * <p>An empty result means "use the server's own endpoint" ({@code ArtifactPresigners#ownEndpoint()}).
 * A present result is scheme ({@code http}/{@code https}) plus host plus an optional port only —
 * no path (other than empty or {@code /}), query, fragment, or userinfo; {@code PresignService}
 * rejects anything else before it ever reaches a presigner. An exception thrown from {@link
 * #endpointFor} fails the presign request outright — it is never treated as an empty result, so a
 * broken seam cannot silently fall back to signing for the server's own endpoint.
 */
public interface ArtifactSigningEndpointResolver {

    Optional<URI> endpointFor(UUID runId);
}
