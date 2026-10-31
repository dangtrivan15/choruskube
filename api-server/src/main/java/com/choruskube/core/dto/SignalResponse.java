package com.choruskube.core.dto;

/**
 * {@code finalizing}: the gate was claimed and the run's workflow now carries the approval out.
 * {@code signaled}: the decision was delivered to the workflow outright.
 */
public record SignalResponse(String outcome) {

    public static final String SIGNALED = "signaled";
    public static final String FINALIZING = "finalizing";

    public static SignalResponse signaled() {
        return new SignalResponse(SIGNALED);
    }

    public static SignalResponse finalizing() {
        return new SignalResponse(FINALIZING);
    }
}
