package com.choruskube.core.service;

import java.util.Optional;

/**
 * Why a Story's design keeps its Tasks from starting. A Task run stages the Story's approved spec,
 * so it cannot start before one exists, nor while a revision may still replace it.
 */
public final class StoryDesignReadiness {

    public static final String NOT_APPROVED = "Story spec not approved yet";
    public static final String UNDER_REVISION = "Story spec under revision";

    private StoryDesignReadiness() {}

    /** With no approved version there is nothing to revise, so an open design run is the first design. */
    static Optional<String> reason(boolean approved, boolean designRunOpen) {
        if (!approved) {
            return Optional.of(NOT_APPROVED);
        }
        return designRunOpen ? Optional.of(UNDER_REVISION) : Optional.empty();
    }

    static String refusal(String reason) {
        return "Cannot start this Task: " + reason;
    }
}
