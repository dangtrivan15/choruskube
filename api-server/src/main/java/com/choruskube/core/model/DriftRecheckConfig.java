package com.choruskube.core.model;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Optional;

/**
 * A gate's {@code config_overrides.drift_recheck}: on approval, check the run against its repos'
 * current default branches, and when one moved, hold the approval and fire {@code route}.
 * {@code GraphValidationService} rejects a malformed value at run start.
 */
public record DriftRecheckConfig(String route, StampMode stamp) {

    public static final String CONFIG_KEY = "drift_recheck";

    /** When a held approval re-applies itself: always, or only if the routed-to node approved unchanged. */
    public enum StampMode {
        always,
        if_unchanged;

        public static Optional<StampMode> fromValue(String value) {
            for (StampMode mode : values()) {
                if (mode.name().equals(value)) {
                    return Optional.of(mode);
                }
            }
            return Optional.empty();
        }
    }

    public static Optional<DriftRecheckConfig> fromConfigOverrides(JsonNode configOverrides) {
        if (configOverrides == null || !configOverrides.has(CONFIG_KEY)) {
            return Optional.empty();
        }
        JsonNode node = configOverrides.get(CONFIG_KEY);
        String route = node.path("route").asText("");
        Optional<StampMode> stamp = StampMode.fromValue(node.path("stamp").asText(""));
        if (route.isBlank() || stamp.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new DriftRecheckConfig(route, stamp.get()));
    }
}
