package com.choruskube.core.dto;

import java.util.UUID;

/**
 * Everything a Worker needs to start polling. Dispatch comes from Temporal, not from the API
 * server, so a Worker never polls this one for work; the only other calls it makes here are the
 * workload routes, against runs Temporal has already handed it.
 *
 * @param token the Temporal credential for {@code temporalNamespace}. Blank means the Worker
 *     presents no credential at all — a Temporal with no authorizer, which is the single-Fleet
 *     default. The Worker must omit credentials rather than send an empty one: the Temporal SDK
 *     turns TLS on whenever credentials are present, whatever they contain.
 * @param expiresInSeconds lifetime of {@code token}. Non-positive means it does not expire.
 * @param endpoint the Temporal frontend address to dial. Blank means the Worker falls back to its
 *     own configured address — the in-cluster case, where every Worker already points at the same
 *     Temporal service by convention.
 * @param internalToken the credential this Worker presents on {@code /worker/**} application
 *     routes. Blank means the Worker keeps the credential it already holds — a minted one still
 *     fresh enough not to be reissued, or, before any mint, the Fleet token it registered with.
 */
public record WorkerRegisterResponse(
        UUID workerId,
        String temporalNamespace,
        String taskQueue,
        String token,
        long expiresInSeconds,
        String endpoint,
        String internalToken) {}
