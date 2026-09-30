package com.choruskube.core.model.enums;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Optional;

/**
 * The behaviour a roadmap gate's {@code config_overrides.materialize} value selects on approval:
 * {@code roadmap_candidates} creates only new Epic trees (the Roadmap Provisioner's original
 * behaviour); {@code roadmap_extension} additionally allows anchoring new children under an
 * existing item, scoped to the triggering run's own Epic. Lower-case, mirroring the gate's JSON
 * config string rather than the {@code SCREAMING_SNAKE_CASE} convention elsewhere in this package.
 */
public enum RoadmapMaterializeMode {
    roadmap_candidates,
    roadmap_extension;

    public static final String CONFIG_KEY = "materialize";

    public static Optional<RoadmapMaterializeMode> fromConfigValue(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        for (RoadmapMaterializeMode mode : values()) {
            if (mode.name().equals(value)) {
                return Optional.of(mode);
            }
        }
        return Optional.empty();
    }

    public static Optional<RoadmapMaterializeMode> fromConfigOverrides(JsonNode configOverrides) {
        if (configOverrides == null || !configOverrides.has(CONFIG_KEY)) {
            return Optional.empty();
        }
        return fromConfigValue(configOverrides.get(CONFIG_KEY).asText(null));
    }
}
