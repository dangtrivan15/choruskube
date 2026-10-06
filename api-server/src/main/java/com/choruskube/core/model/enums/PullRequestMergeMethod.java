package com.choruskube.core.model.enums;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Optional;

/**
 * The method a merge-configured human gate's {@code config_overrides.merge_pull_requests} value
 * selects on approval — GitHub's own {@code merge_method} for the {@code PUT .../merge} call.
 * Lower-case, mirroring {@link RoadmapMaterializeMode}'s convention for a gate's JSON config
 * string rather than the {@code SCREAMING_SNAKE_CASE} convention elsewhere in this package.
 *
 * <p>Applies only on the {@code approved} decision — a gate declaring this key with no such
 * decision available is rejected at graph-validation time.
 */
public enum PullRequestMergeMethod {
    merge,
    squash,
    rebase;

    public static final String CONFIG_KEY = "merge_pull_requests";

    public static Optional<PullRequestMergeMethod> fromConfigValue(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        for (PullRequestMergeMethod method : values()) {
            if (method.name().equals(value)) {
                return Optional.of(method);
            }
        }
        return Optional.empty();
    }

    public static Optional<PullRequestMergeMethod> fromConfigOverrides(JsonNode configOverrides) {
        if (configOverrides == null || !configOverrides.has(CONFIG_KEY)) {
            return Optional.empty();
        }
        return fromConfigValue(configOverrides.get(CONFIG_KEY).asText(null));
    }
}
