package com.choruskube.core.exception;

/** The gate was not awaiting a decision, or a concurrent decision claimed it first. */
public class GateClaimConflictException extends ConflictException {

    public GateClaimConflictException(String message) {
        super(message);
    }
}
