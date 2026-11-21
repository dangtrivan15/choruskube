package com.choruskube.core.repository;

import java.time.Instant;
import java.util.UUID;

/** A spec version without its spec or review text, which an entity load would read on every query. */
public interface StorySpecVersionMetaView {
    UUID getId();

    UUID getStoryId();

    Integer getVersion();

    UUID getRunId();

    Instant getApprovedAt();

    Instant getReviewCapturedAt();
}
