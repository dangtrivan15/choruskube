package com.choruskube.core.service;

import java.util.UUID;

/**
 * Answers the only authorization question a Worker's application calls raise: may the credential
 * this process presented act on this run?
 *
 * <p>The sibling of {@link WorkerRegistrar}, and replaced the same way — by an implementation
 * existing as a bean. That one decides which Fleet a Worker serves; this one decides which runs
 * follow from that. They have to agree, so they are shaped alike.
 *
 * <p>Authentication and authorization are one call because they are one question: what makes a
 * credential valid is the same knowledge that decides what it may reach. Answering them separately
 * would let a caller distinguish "unknown credential" from "valid credential, wrong run", which
 * turns the endpoint into a probe for which runs exist.
 */
public interface WorkerAuthorizer {

    /**
     * @param credential the bearer token the Worker presented, taken from the request attribute
     *     {@code WorkerAuthFilter.FLEET_TOKEN_ATTRIBUTE}
     * @throws com.choruskube.core.exception.ForbiddenException if the credential is unknown, or
     *     known but not entitled to {@code runId}
     */
    void requireMayActOn(String credential, UUID runId);
}
