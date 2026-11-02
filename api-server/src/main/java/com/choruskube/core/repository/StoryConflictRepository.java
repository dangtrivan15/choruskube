package com.choruskube.core.repository;

import com.choruskube.core.model.StoryConflict;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface StoryConflictRepository extends JpaRepository<StoryConflict, UUID> {
    @Query("SELECT DISTINCT c.storyId FROM StoryConflict c WHERE c.releasedAt IS NULL")
    List<UUID> findStoryIdsWithOpenConflicts();

    List<StoryConflict> findByStoryIdAndReleasedAtIsNullOrderByCreatedAtAsc(UUID storyId);

    /** Assigned or not: a revision that ended without a version still holds the ones it was given. */
    @Query("SELECT c FROM StoryConflict c WHERE c.storyId = :storyId AND c.raisedAgainstVersion = :version"
            + " AND c.releasedAt IS NULL ORDER BY c.createdAt")
    List<StoryConflict> findOpenRaisedAgainst(@Param("storyId") UUID storyId, @Param("version") int version);

    /**
     * {@code SELECT ... FOR UPDATE}: a concurrent close or release waits for the caller's commit. Take
     * it only while holding the Story lock; nothing locks a conflict row and then its Story row.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT c FROM StoryConflict c WHERE c.id IN :ids AND c.storyId = :storyId"
            + " AND c.raisedAgainstVersion = :version AND c.revisionRunId IS NULL AND c.releasedAt IS NULL"
            + " ORDER BY c.createdAt")
    List<StoryConflict> lockOpenUnassignedRaisedAgainst(
            @Param("storyId") UUID storyId, @Param("version") int version, @Param("ids") Collection<UUID> ids);

    long countByIdInAndRevisionRunId(Collection<UUID> ids, UUID revisionRunId);

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

    /**
     * Takes a conflict from whichever revision holds it, so call it only under the Story lock once no
     * design run is open. Column-targeted so a concurrent release is never written back as unreleased.
     */
    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE StoryConflict c SET c.revisionRunId = :revisionRunId WHERE c.id IN :ids AND c.storyId = :storyId"
            + " AND c.raisedAgainstVersion = :version AND c.releasedAt IS NULL")
    int reassignToRevision(
            @Param("ids") Collection<UUID> ids,
            @Param("storyId") UUID storyId,
            @Param("version") int version,
            @Param("revisionRunId") UUID revisionRunId);

    /** Column-targeted so a concurrent revision assignment is never written back as unassigned. */
    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE StoryConflict c SET c.releasedAt = :releasedAt WHERE c.id = :id AND c.releasedAt IS NULL")
    int markReleased(@Param("id") UUID id, @Param("releasedAt") Instant releasedAt);
}
