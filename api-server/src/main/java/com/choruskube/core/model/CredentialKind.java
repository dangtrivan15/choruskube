package com.choruskube.core.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * One of the credentials a Worker may hold and report the health of: its GitHub credential (App
 * or PAT), its Claude token, or its registry login. The wire string is the contract a Worker
 * binary and this server agree on, so it is fixed here rather than derived from the enum name.
 */
public enum CredentialKind {
    GITHUB("github"),
    CLAUDE("claude"),
    REGISTRY("registry");

    private final String wire;

    CredentialKind(String wire) {
        this.wire = wire;
    }

    @JsonValue
    public String wire() {
        return wire;
    }

    /** Throws on anything other than one of the three known wire values — there is no default. */
    @JsonCreator
    public static CredentialKind fromWire(String value) {
        for (CredentialKind kind : values()) {
            if (kind.wire.equals(value)) {
                return kind;
            }
        }
        throw new IllegalArgumentException("Unknown credential kind: " + value);
    }
}
