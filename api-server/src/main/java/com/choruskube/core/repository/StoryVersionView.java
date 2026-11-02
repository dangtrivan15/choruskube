package com.choruskube.core.repository;

import java.util.UUID;

/** A Story's newest approved spec version, as {@link StorySpecVersionRepository#findCurrentVersions} returns it. */
public interface StoryVersionView {
    UUID getStoryId();

    Integer getVersion();
}
