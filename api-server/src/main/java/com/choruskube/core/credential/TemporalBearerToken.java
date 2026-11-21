package com.choruskube.core.credential;

import java.time.Instant;

/**
 * A control-plane bearer token for one Temporal namespace, with its expiry. {@link #toString()}
 * never includes {@code value} — only the expiry and a redaction marker.
 *
 * @param value the raw bearer token
 * @param expiresAt when {@code value} stops being valid
 */
public record TemporalBearerToken(String value, Instant expiresAt) {

    public TemporalBearerToken {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("value is required");
        }
        if (expiresAt == null) {
            throw new IllegalArgumentException("expiresAt is required");
        }
    }

    @Override
    public String toString() {
        return "TemporalBearerToken{value=<redacted>, expiresAt=" + expiresAt + "}";
    }
}
