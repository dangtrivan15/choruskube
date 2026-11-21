package com.choruskube.core.credential;

import java.util.Optional;

/**
 * The optional seam a deployment provides as a Spring bean of this type to become a control-plane
 * Temporal token source. {@link ControlPlaneTemporalTokens} looks up this type at construction
 * time; with no such bean present, core presents no credential on any Temporal call and the
 * roster reports every namespace "none" — today's behaviour, unchanged.
 *
 * <p>Contract: an empty {@link Optional} means this deployment presents no credential for the
 * namespace at all — callers must send no credential and not treat it as a failure. A thrown exception means
 * the supplier could not obtain a token for the namespace right now — callers must treat that as
 * "unavailable", never as "none".
 */
@FunctionalInterface
public interface TemporalTokenSupplier {

    /**
     * @param namespace the Temporal namespace to obtain a token for
     * @return the namespace's current token, or empty if this deployment presents no credential
     * @throws Exception if a token could not be obtained for the namespace right now
     */
    Optional<TemporalBearerToken> supply(String namespace) throws Exception;
}
