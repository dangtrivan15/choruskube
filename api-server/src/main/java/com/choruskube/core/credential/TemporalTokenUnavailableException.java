package com.choruskube.core.credential;

/**
 * Thrown when {@link TemporalBearerTokenHolder} cannot serve a valid token for a namespace right
 * now: the supplier failed, returned a null or already-expired token, and nothing valid is
 * cached. Its message names the namespace only, never a token value or the supplier's own
 * message, which may itself carry one.
 */
public class TemporalTokenUnavailableException extends RuntimeException {

    private final String namespace;

    public TemporalTokenUnavailableException(String namespace, Throwable cause) {
        super("no valid Temporal token available for namespace '" + namespace + "'", cause);
        this.namespace = namespace;
    }

    public String namespace() {
        return namespace;
    }
}
