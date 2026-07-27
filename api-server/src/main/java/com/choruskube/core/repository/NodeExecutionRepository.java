package com.choruskube.core.repository;

import com.choruskube.core.model.NodeExecution;
import com.choruskube.core.model.enums.NodeExecutionStatus;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface NodeExecutionRepository
        extends JpaRepository<NodeExecution, UUID>, JpaSpecificationExecutor<NodeExecution> {
    List<NodeExecution> findByWorkflowRunId(UUID workflowRunId);

    /**
     * Atomic compare-and-set on {@code status}, used to guard exactly-once handling of a human
     * decision signal ({@link com.choruskube.core.service.RunService#signalHumanDecision}).
     *
     * <p>Two concurrent/duplicate decision submissions for the same node execution (double-click,
     * two open tabs, a client retry) would otherwise both pass validation and both independently
     * trigger materialization, silently creating duplicate Epic/Story/Task rows. The
     * read-compare-write is a single atomic statement at the database level: only the caller whose
     * {@code expectedStatus} still matches wins, and the loser sees {@code 0} rows affected.
     *
     * <p>Unlike CRUD methods inherited from {@link JpaRepository}, a custom {@code @Modifying
     * @Query} method like this one is <em>not</em> wrapped in a transaction by Spring Data on its
     * own — invoking it with no ambient transaction active throws {@code
     * TransactionRequiredException}. Call it only through {@link
     * com.choruskube.core.service.NodeExecutionClaimService}, which gives it a transactional entry
     * point.
     *
     * @return the number of rows updated — {@code 1} if this call won the race, {@code 0} if the
     *     node execution was already claimed (or was never in {@code expectedStatus})
     */
    @Modifying
    @Query("UPDATE NodeExecution n SET n.status = :newStatus " + "WHERE n.id = :id AND n.status = :expectedStatus")
    int compareAndSetStatus(
            @Param("id") UUID id,
            @Param("expectedStatus") NodeExecutionStatus expectedStatus,
            @Param("newStatus") NodeExecutionStatus newStatus);

    List<NodeExecution> findByWorkflowRunIdIn(Collection<UUID> workflowRunIds);

    List<NodeExecution> findByWorkflowRunIdAndStatus(UUID workflowRunId, NodeExecutionStatus status);

    List<NodeExecution> findByStatus(NodeExecutionStatus status);

    List<NodeExecution> findByStatusIn(Collection<NodeExecutionStatus> statuses);

    List<NodeExecution> findByWorkflowRunIdAndLoopGroupIsNotNullOrderByCompletedAtAsc(UUID workflowRunId);

    List<NodeExecution> findByWorkflowRunIdAndLoopGroupOrderByIterationAsc(UUID workflowRunId, String loopGroup);

    boolean existsByJobSecretHash(String jobSecretHash);

    // --- Analytics queries ---

    @Query(value = """
            SELECT
                COALESCE(ne.label, 'unknown')                              AS node_label,
                COUNT(*)                                                    AS execution_count,
                COUNT(*) FILTER (WHERE ne.status = 'completed')            AS completed_count,
                COUNT(*) FILTER (WHERE ne.status = 'failed')               AS failed_count
            FROM node_execution ne
            JOIN workflow_run wr ON wr.id = ne.workflow_run_id
            WHERE wr.created_at >= :since
            GROUP BY COALESCE(ne.label, 'unknown')
            ORDER BY execution_count DESC
            """, nativeQuery = true)
    List<Object[]> getNodeAnalytics(@Param("since") Instant since);

    @Query(value = """
            SELECT
                COALESCE(ne.label, 'unknown')                              AS node_label,
                AVG(EXTRACT(EPOCH FROM (ne.completed_at - ne.started_at))) AS avg_duration,
                PERCENTILE_CONT(0.50) WITHIN GROUP (ORDER BY EXTRACT(EPOCH FROM (ne.completed_at - ne.started_at))) AS p50_duration,
                PERCENTILE_CONT(0.95) WITHIN GROUP (ORDER BY EXTRACT(EPOCH FROM (ne.completed_at - ne.started_at))) AS p95_duration,
                COUNT(*)                                                    AS sample_size
            FROM node_execution ne
            JOIN workflow_run wr ON wr.id = ne.workflow_run_id
            WHERE ne.completed_at IS NOT NULL
              AND ne.started_at IS NOT NULL
              AND wr.created_at >= :since
            GROUP BY COALESCE(ne.label, 'unknown')
            ORDER BY avg_duration DESC
            """, nativeQuery = true)
    List<Object[]> getBottleneckNodes(@Param("since") Instant since);
}
