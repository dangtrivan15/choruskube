package com.choruskube.core.dto;

import com.choruskube.core.model.CredentialHealthStatus;
import com.choruskube.core.model.CredentialKind;
import java.time.Instant;

/**
 * One credential's latest self-check result, as a Worker reports it on every registration call.
 * {@code detail} never carries any part of a secret — only a short, human-readable reason.
 *
 * @param kind which credential this result is about
 * @param status the outcome: {@link CredentialHealthStatus#NOT_HELD} when the Worker holds no
 *     credential of this kind at all
 * @param detail a short reason, capped at {@link #MAX_DETAIL_LENGTH} characters
 * @param checkedAt when the Worker ran this check
 */
public record WorkerCredentialHealth(
        CredentialKind kind, CredentialHealthStatus status, String detail, Instant checkedAt) {

    /** Must equal the Go Worker's {@code credential.MaxHealthDetail} — the two sides of one wire contract. */
    public static final int MAX_DETAIL_LENGTH = 256;

    public WorkerCredentialHealth {
        if (kind == null) {
            throw new IllegalArgumentException("kind is required");
        }
        if (status == null) {
            throw new IllegalArgumentException("status is required");
        }
        if (checkedAt == null) {
            throw new IllegalArgumentException("checkedAt is required");
        }
        if (detail != null && detail.length() > MAX_DETAIL_LENGTH) {
            throw new IllegalArgumentException("detail must be at most " + MAX_DETAIL_LENGTH + " characters");
        }
    }
}
