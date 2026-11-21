package com.choruskube.core.repository;

import java.time.Instant;
import java.util.UUID;

/** A spec version's final review without its spec text, as a revision stages it. */
public interface StorySpecVersionReviewView {
    Integer getVersion();

    UUID getRunId();

    Instant getReviewCapturedAt();

    String getFinalReview();
}
