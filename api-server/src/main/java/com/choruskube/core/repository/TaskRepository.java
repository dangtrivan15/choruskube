package com.choruskube.core.repository;

import com.choruskube.core.model.Task;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TaskRepository extends JpaRepository<Task, UUID>, JpaSpecificationExecutor<Task> {
    List<Task> findByStoryIdOrderByCreatedAtDesc(UUID storyId);

    /** Batch finder used to avoid N+1 when computing a Story/Epic list's rollup status/progress. */
    List<Task> findByStoryIdIn(Collection<UUID> storyIds);

    @Query("SELECT count(t) FROM Task t " + "WHERE t.status <> 'done' AND t.softwareProjectId = :id")
    long countNonDoneBySoftwareProjectId(@Param("id") UUID id);

    // --- Analytics queries ---

    @Query(value = """
            SELECT
                t.status::text                                             AS status,
                COUNT(*)                                                    AS count
            FROM task t
            GROUP BY t.status
            ORDER BY count DESC
            """, nativeQuery = true)
    List<Object[]> getStatusCounts();

    @Query(value = """
            SELECT
                TO_CHAR(DATE_TRUNC('day', t.updated_at), 'YYYY-MM-DD')     AS day,
                COUNT(*)                                                    AS count
            FROM task t
            WHERE t.status = 'done'
              AND t.updated_at >= :since
            GROUP BY DATE_TRUNC('day', t.updated_at)
            ORDER BY DATE_TRUNC('day', t.updated_at)
            """, nativeQuery = true)
    List<Object[]> getThroughput(@Param("since") Instant since);
}
