package com.choruskube.core.exception;

/** A held approval found main moved again after the last allowed re-check round. */
public class GateRecheckExhaustedException extends ConflictException {

    public GateRecheckExhaustedException(String message) {
        super(message);
    }
}
