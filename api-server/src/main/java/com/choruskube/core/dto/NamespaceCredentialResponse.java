package com.choruskube.core.dto;

import com.choruskube.core.credential.TemporalTokenState;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;

/**
 * One namespace's entry in {@link InternalNamespacesResponse#credentials}. {@code state} is
 * exactly one of {@code "bearer"}, {@code "none"} or {@code "unavailable"}; {@code token} and
 * {@code expiresAt} are present only for {@code "bearer"} — {@code @JsonInclude(NON_NULL)} omits
 * them for the others rather than serializing {@code null}, which an orchestrator predating this
 * field must not have to distinguish from "no value yet".
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record NamespaceCredentialResponse(String state, String token, Instant expiresAt) {

    /** Exhaustive over the sealed states, so no state, and no null, can fall through to "none". */
    public static NamespaceCredentialResponse from(TemporalTokenState state) {
        return switch (state) {
            case TemporalTokenState.Bearer bearer ->
                new NamespaceCredentialResponse(
                        "bearer", bearer.token().value(), bearer.token().expiresAt());
            case TemporalTokenState.None none -> none();
            case TemporalTokenState.Unavailable unavailable -> unavailable();
        };
    }

    public static NamespaceCredentialResponse none() {
        return new NamespaceCredentialResponse("none", null, null);
    }

    public static NamespaceCredentialResponse unavailable() {
        return new NamespaceCredentialResponse("unavailable", null, null);
    }

    /** Never includes {@code token} — only the state and, for a bearer, its expiry. */
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("NamespaceCredentialResponse{state=").append(state);
        if (token != null) {
            sb.append(", token=<redacted>");
        }
        if (expiresAt != null) {
            sb.append(", expiresAt=").append(expiresAt);
        }
        return sb.append("}").toString();
    }
}
