package com.choruskube.core.repository;

import com.choruskube.core.model.StoryConflict;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface StoryConflictRepository extends JpaRepository<StoryConflict, UUID> {
    List<StoryConflict> findByReleasedAtIsNull();

    List<StoryConflict> findByStoryIdAndReleasedAtIsNullOrderByCreatedAtAsc(UUID storyId);

    boolean existsByAnalyzeNodeExecutionId(UUID analyzeNodeExecutionId);

    /**
     * Returns how many it assigned; only the Story's open, unassigned conflicts qualify. Column-targeted
     * so a concurrent release is never written back as unreleased.
     */
    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE StoryConflict c SET c.revisionRunId = :revisionRunId WHERE c.id IN :ids AND c.storyId = :storyId"
            + " AND c.revisionRunId IS NULL AND c.releasedAt IS NULL")
    int assignToRevision(
            @Param("ids") Collection<UUID> ids,
            @Param("storyId") UUID storyId,
            @Param("revisionRunId") UUID revisionRunId);

    /** Column-targeted so a concurrent revision assignment is never written back as unassigned. */
    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE StoryConflict c SET c.releasedAt = :releasedAt WHERE c.id = :id AND c.releasedAt IS NULL")
    int markReleased(@Param("id") UUID id, @Param("releasedAt") Instant releasedAt);
}
