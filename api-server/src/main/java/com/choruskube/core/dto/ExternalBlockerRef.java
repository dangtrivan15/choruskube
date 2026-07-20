package com.choruskube.core.dto;

import java.util.UUID;

/**
 * A reference to a Story/Task OUTSIDE the requested Epic that participates in a dependency edge
 * touching this Epic's tree — e.g. a Task in another Epic that blocks one of this Epic's Tasks.
 * Carries enough context (title, owning Epic id/title) for the UI to render "blocked by a Task in
 * another Epic" without a follow-up lookup.
 */
public record ExternalBlockerRef(String itemType, UUID itemId, String title, UUID epicId, String epicTitle) {}
