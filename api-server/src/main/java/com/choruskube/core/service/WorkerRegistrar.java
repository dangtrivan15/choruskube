package com.choruskube.core.service;

import com.choruskube.core.dto.WorkerRegisterRequest;
import com.choruskube.core.dto.WorkerRegisterResponse;

/**
 * Answers a Worker's one question: which Fleet do I serve, and with what credential.
 *
 * <p>A Fleet is the set of Workers polling one Temporal namespace and task queue. Every
 * deployment has Fleets; how many is the only thing that varies. This server has exactly one —
 * see {@link SingleFleetWorkerRegistrar} — and an implementation that can place a Worker on one
 * of several replaces that default simply by being a bean, in the same shape as {@link
 * RunPlacementResolver}. The two are halves of the same arrangement: this decides which queue a
 * Worker polls, that decides which queue a run is dispatched to, and they have to agree.
 *
 * <p>Authentication of {@code fleetToken} belongs here rather than in {@code WorkerAuthFilter},
 * which only proves a bearer token was presented. What makes a token valid is the same knowledge
 * that decides which Fleet it names, so an unknown token is a {@code ForbiddenException} from
 * this layer, not a 401 from the filter.
 */
public interface WorkerRegistrar {

    /**
     * @param fleetToken the bearer token the Worker presented, already known to be non-blank
     * @throws com.choruskube.core.exception.ForbiddenException if the token names no Fleet
     */
    WorkerRegisterResponse register(String fleetToken, WorkerRegisterRequest request);
}
