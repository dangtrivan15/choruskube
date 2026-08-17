package com.choruskube.core.repository;

import com.choruskube.core.model.Task;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TaskRepository extends JpaRepository<Task, UUID>, JpaSpecificationExecutor<Task> {
    List<Task> findByStoryIdOrderByCreatedAtDesc(UUID storyId);

    /**
     * {@code SELECT ... FOR UPDATE} on one Task, held until the surrounding transaction ends. Used
     * by every path that starts a workflow run for a Task, so the manual Start button and the
     * Autopilot tick serialise against each other: under READ COMMITTED both would otherwise read
     * the Task as {@code backlog}, both pass the status guard, and both commit — two agent
     * containers for one Task. The Autopilot's own tick lease does not cover this, since it is
     * keyed on the Autopilot and only serialises pass against pass.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<Task> findWithLockById(UUID id);

    /** Batch finder used to avoid N+1 when computing a Story/Epic list's rollup status/progress. */
    List<Task> findByStoryIdIn(Collection<UUID> storyIds);

    /**
     * The same batch, ordered the way {@link #findByStoryIdOrderByCreatedAtDesc} orders one Story's
     * Tasks — so a caller can replace a per-Story loop with one query and group the result without
     * changing what any Story's list looks like. The sort is global, but grouping a sorted stream
     * preserves encounter order inside each group, so each Story's list comes out identical.
     */
    List<Task> findByStoryIdInOrderByCreatedAtDesc(Collection<UUID> storyIds);

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
