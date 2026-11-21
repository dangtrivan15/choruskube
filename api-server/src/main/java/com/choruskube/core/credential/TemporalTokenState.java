package com.choruskube.core.credential;

/**
 * The outcome {@link ControlPlaneTemporalTokens#states} reports for one namespace: a live
 * bearer token, no credential by deployment design, or a credential that should exist but could
 * not be obtained right now. {@link Unavailable} is never conflated with {@link None} — callers
 * (the roster response, the per-call interceptor) must fail a call rather than send it
 * credential-free when a source is configured but temporarily can't answer.
 */
public sealed interface TemporalTokenState {

    record Bearer(TemporalBearerToken token) implements TemporalTokenState {
        /** Carries no token value; {@link TemporalBearerToken#toString()} already redacts it. */
        @Override
        public String toString() {
            return "Bearer{" + token + "}";
        }
    }

    record None() implements TemporalTokenState {}

    record Unavailable() implements TemporalTokenState {}
}
