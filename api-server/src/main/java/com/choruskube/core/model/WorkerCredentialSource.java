package com.choruskube.core.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Which credential mode a Worker declared at registration: whether it holds its own GitHub,
 * Claude and registry credentials ({@link #LOCAL}), or asks the API server to broker them
 * ({@link #CONTROL_PLANE}). The wire string is the contract a Worker binary and this server agree
 * on, so it is fixed here rather than derived from the enum name.
 */
public enum WorkerCredentialSource {
    CONTROL_PLANE("control-plane"),
    LOCAL("local");

    private final String wire;

    WorkerCredentialSource(String wire) {
        this.wire = wire;
    }

    @JsonValue
    public String wire() {
        return wire;
    }

    /**
     * {@code null} or {@code ""} come back as {@code null} rather than an exception: whether an
     * absent value is acceptable is the caller's call, not this method's — see {@code
     * WorkerRegisterRequest}'s compact constructor, which treats {@code null} here as a reason to
     * refuse the whole request.
     */
    @JsonCreator
    public static WorkerCredentialSource fromWire(String value) {
        if (value == null || value.isEmpty()) {
            return null;
        }
        for (WorkerCredentialSource source : values()) {
            if (source.wire.equals(value)) {
                return source;
            }
        }
        throw new IllegalArgumentException("Unknown worker credential source: " + value);
    }
}
