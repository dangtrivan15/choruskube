package com.choruskube.core.repository;

import com.choruskube.core.model.StorySpecVersion;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface StorySpecVersionRepository extends JpaRepository<StorySpecVersion, UUID> {
    Optional<StorySpecVersion> findFirstByStoryIdOrderByVersionDesc(UUID storyId);

    Optional<StorySpecVersion> findByStoryIdAndVersion(UUID storyId, int version);

    List<StorySpecVersion> findByStoryIdOrderByVersionAsc(UUID storyId);

    Optional<StorySpecVersion> findByRunId(UUID runId);

    List<StorySpecVersion> findByReviewCapturedAtIsNullOrderByApprovedAtAsc();

    @Query("select v.storyId as storyId, max(v.version) as version "
            + "from StorySpecVersion v where v.storyId in :storyIds group by v.storyId")
    List<StoryVersionView> findCurrentVersions(@Param("storyIds") Collection<UUID> storyIds);

    /**
     * Writes only while still uncaptured, so a concurrent pass's capture is never overwritten. Clears
     * the persistence context, or a version loaded earlier in the caller's transaction keeps its stale copy.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update StorySpecVersion v set v.finalReview = :finalReview, v.reviewCapturedAt = :capturedAt "
            + "where v.id = :id and v.reviewCapturedAt is null")
    int captureReview(
            @Param("id") UUID id, @Param("finalReview") String finalReview, @Param("capturedAt") Instant capturedAt);
}
