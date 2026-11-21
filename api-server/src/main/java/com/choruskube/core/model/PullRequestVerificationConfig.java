package com.choruskube.core.model;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * A gate that verifies instead of merges. {@code verify_pull_requests_merged: true} refuses the
 * approval unless every registered pull request is already merged into its repository's default
 * branch, and merges nothing. {@code approve_on_arrival: true} has the server approve the gate as
 * it opens; it counts only beside the first key, so an approval no one gave can confirm only what
 * already merged. {@code GraphValidationService} rejects any other value or combination.
 */
public final class PullRequestVerificationConfig {

    public static final String VERIFY_KEY = "verify_pull_requests_merged";
    public static final String APPROVE_ON_ARRIVAL_KEY = "approve_on_arrival";

    private PullRequestVerificationConfig() {}

    public static boolean verifiesMerged(JsonNode configOverrides) {
        return isTrue(configOverrides, VERIFY_KEY);
    }

    public static boolean approvesOnArrival(JsonNode configOverrides) {
        return verifiesMerged(configOverrides) && isTrue(configOverrides, APPROVE_ON_ARRIVAL_KEY);
    }

    private static boolean isTrue(JsonNode configOverrides, String key) {
        if (configOverrides == null) {
            return false;
        }
        JsonNode value = configOverrides.get(key);
        return value != null && value.isBoolean() && value.booleanValue();
    }
}
