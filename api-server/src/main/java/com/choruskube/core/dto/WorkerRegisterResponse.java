package com.choruskube.core.dto;

import java.util.UUID;

/**
 * Everything a Worker needs to start polling. Dispatch comes from Temporal, not from the API
 * server, so this response is the only API-server call a Worker ever makes.
 *
 * @param token the Temporal credential for {@code temporalNamespace}. Blank means the Worker
 *     presents no credential at all — a Temporal with no authorizer, which is the single-Fleet
 *     default. The Worker must omit credentials rather than send an empty one: the Temporal SDK
 *     turns TLS on whenever credentials are present, whatever they contain.
 * @param expiresInSeconds lifetime of {@code token}. Non-positive means it does not expire.
 * @param endpoint the Temporal frontend address to dial. Blank means the Worker falls back to its
 *     own configured address — the in-cluster case, where every Worker already points at the same
 *     Temporal service by convention.
 */
public record WorkerRegisterResponse(
        UUID workerId,
        String temporalNamespace,
        String taskQueue,
        String token,
        long expiresInSeconds,
        String endpoint) {}
