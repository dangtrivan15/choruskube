package com.choruskube.core.dto;

import jakarta.annotation.Nullable;

/**
 * {@code finalizing}: the gate was claimed and the run's workflow now carries the approval out.
 * {@code signaled}: the decision was delivered to the workflow outright.
 * {@code rerouted}: an approval was not applied and became a re-review; {@code reason} says why.
 */
public record SignalResponse(String outcome, @Nullable String reason) {

    public static final String SIGNALED = "signaled";
    public static final String FINALIZING = "finalizing";
    public static final String REROUTED = "rerouted";

    public static SignalResponse signaled() {
        return new SignalResponse(SIGNALED, null);
    }

    public static SignalResponse finalizing() {
        return new SignalResponse(FINALIZING, null);
    }

    public static SignalResponse rerouted(String reason) {
        return new SignalResponse(REROUTED, reason);
    }
}
