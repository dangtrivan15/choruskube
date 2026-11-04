package com.choruskube.core.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * The outcome of checking one credential a Worker holds. The first four values are the ones the
 * API server's own credential checks already store; {@link #NOT_HELD} is the one new value, for
 * a Worker that holds no credential of a given kind at all.
 */
public enum CredentialHealthStatus {
    VALID,
    EXPIRED,
    INSUFFICIENT_PERMISSIONS,
    UNREACHABLE,
    NOT_HELD;

    @JsonValue
    public String wire() {
        return name();
    }

    /** Throws on anything other than one of the five known values — there is no default. */
    @JsonCreator
    public static CredentialHealthStatus fromWire(String value) {
        for (CredentialHealthStatus status : values()) {
            if (status.name().equals(value)) {
                return status;
            }
        }
        throw new IllegalArgumentException("Unknown credential health status: " + value);
    }
}
