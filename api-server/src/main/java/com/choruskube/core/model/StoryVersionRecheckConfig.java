package com.choruskube.core.model;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Optional;

/**
 * A gate's {@code config_overrides.story_version_recheck}: on approval by a Task's run, refuse while
 * its Story's spec is under revision, and when the spec moved since the run planned, turn the
 * approval into {@code route}. {@code GraphValidationService} rejects a malformed value at run start.
 */
public record StoryVersionRecheckConfig(String route) {

    public static final String CONFIG_KEY = "story_version_recheck";

    public static Optional<StoryVersionRecheckConfig> fromConfigOverrides(JsonNode configOverrides) {
        if (configOverrides == null || !configOverrides.has(CONFIG_KEY)) {
            return Optional.empty();
        }
        String route = configOverrides.get(CONFIG_KEY).path("route").asText("");
        // The route re-enters the gate's decision; `approved` would re-check itself without end.
        if (route.isBlank() || "approved".equalsIgnoreCase(route)) {
            return Optional.empty();
        }
        return Optional.of(new StoryVersionRecheckConfig(route));
    }
}
