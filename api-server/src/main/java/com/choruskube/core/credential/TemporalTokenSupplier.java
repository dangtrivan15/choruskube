package com.choruskube.core.credential;

import java.util.Optional;

/**
 * The pluggable source {@link TemporalBearerTokenHolder} pulls a namespace's token from. This is
 * a plain interface, not a Spring bean — the seam that wires a concrete supplier in belongs to
 * whichever deployment has one to offer.
 *
 * <p>Contract: an empty {@link Optional} means this deployment presents no credential for the
 * namespace at all — callers must treat that as "none", never retry it. A thrown exception means
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
