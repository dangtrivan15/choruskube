package com.choruskube.core.exception;

/** The gate could not tell whether main moved; nothing was decided and the gate is still open. */
public class GateRecheckUnavailableException extends ConflictException {

    public GateRecheckUnavailableException(String ownerRepo, Throwable cause) {
        super("Could not check " + ownerRepo + " against its default branch (" + cause.getMessage()
                + "). The gate is still open — approve again.");
        initCause(cause);
    }
}
