package com.choruskube.core.repository;

import com.choruskube.core.model.NodeExecution;
import com.choruskube.core.model.enums.NodeExecutionStatus;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface NodeExecutionRepository
        extends JpaRepository<NodeExecution, UUID>, JpaSpecificationExecutor<NodeExecution> {
    List<NodeExecution> findByWorkflowRunId(UUID workflowRunId);

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
