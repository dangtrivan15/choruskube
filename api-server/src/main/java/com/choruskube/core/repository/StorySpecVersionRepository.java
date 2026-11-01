package com.choruskube.core.repository;

import com.choruskube.core.model.StorySpecVersion;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface StorySpecVersionRepository extends JpaRepository<StorySpecVersion, UUID> {
    Optional<StorySpecVersion> findFirstByStoryIdOrderByVersionDesc(UUID storyId);

    Optional<StorySpecVersion> findByStoryIdAndVersion(UUID storyId, int version);

    List<StorySpecVersion> findByStoryIdOrderByVersionAsc(UUID storyId);

    Optional<StorySpecVersion> findByRunId(UUID runId);

    @Query("select v.storyId as storyId, max(v.version) as version "
            + "from StorySpecVersion v where v.storyId in :storyIds group by v.storyId")
    List<StoryVersionView> findCurrentVersions(@Param("storyIds") Collection<UUID> storyIds);
}
