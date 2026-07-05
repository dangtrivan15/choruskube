package com.choruskube.core.repository;

import com.choruskube.core.model.WorkflowRun;
import com.choruskube.core.model.enums.WorkflowRunStatus;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface WorkflowRunRepository extends JpaRepository<WorkflowRun, UUID>, JpaSpecificationExecutor<WorkflowRun> {
    List<WorkflowRun> findByStatus(WorkflowRunStatus status);

    boolean existsByGraphTemplateIdAndStatusIn(UUID graphTemplateId, Collection<WorkflowRunStatus> statuses);

    /**
     * Count non-terminal runs whose {@code inputs} jsonb references the given SoftwareProject id
     * via the {@code software_project_id} key. Used by RepoGroup delete-safety.
     * Native SQL because the jsonb path extraction is Postgres-specific and we want an explicit
     * cast so the comparison binds reliably regardless of the JDBC driver's UUID handling.
     */
    @Query(
            value = "SELECT count(*) FROM workflow_run "
                    + "WHERE deleted_at IS NULL "
                    + "  AND status NOT IN ('completed', 'failed', 'cancelled') "
                    + "  AND inputs ->> 'software_project_id' = CAST(:id AS text)",
            nativeQuery = true)
    long countNonTerminalBySoftwareProjectId(@Param("id") UUID id);

    // --- Analytics queries ---

    @Query(value = """
            WITH run_durations AS (
                SELECT
                    wr.id,
                    wr.status,
                    COALESCE(SUM(EXTRACT(EPOCH FROM (ne.completed_at - ne.started_at)))
                        FILTER (WHERE ne.completed_at IS NOT NULL AND ne.started_at IS NOT NULL), 0) AS duration
                FROM workflow_run wr
                LEFT JOIN node_execution ne ON ne.workflow_run_id = wr.id
                WHERE wr.created_at >= :since
                  AND wr.deleted_at IS NULL
                GROUP BY wr.id, wr.status
            )
            SELECT
                COUNT(*)                                                    AS total_runs,
                COUNT(*) FILTER (WHERE status = 'completed')               AS completed_runs,
                COUNT(*) FILTER (WHERE status = 'failed')                  AS failed_runs,
                COALESCE(AVG(duration) FILTER (WHERE duration > 0), 0)     AS avg_duration,
                COALESCE(PERCENTILE_CONT(0.50) WITHIN GROUP (ORDER BY duration)
                    FILTER (WHERE duration > 0), 0)                        AS p50_duration,
                COALESCE(PERCENTILE_CONT(0.95) WITHIN GROUP (ORDER BY duration)
                    FILTER (WHERE duration > 0), 0)                        AS p95_duration
            FROM run_durations
            """, nativeQuery = true)
    Object[] getOverviewStats(@Param("since") Instant since);

    @Query(value = """
            SELECT
                TO_CHAR(DATE_TRUNC('day', created_at), 'YYYY-MM-DD')      AS day,
                COUNT(*)                                                    AS total,
                COUNT(*) FILTER (WHERE status = 'completed')               AS completed,
                COUNT(*) FILTER (WHERE status = 'failed')                  AS failed
            FROM workflow_run
            WHERE created_at >= :since
              AND deleted_at IS NULL
            GROUP BY DATE_TRUNC('day', created_at)
            ORDER BY DATE_TRUNC('day', created_at)
            """, nativeQuery = true)
    List<Object[]> getDailyRunTrend(@Param("since") Instant since);

    @Query(value = """
            SELECT
                COALESCE(gt.name, 'Unknown')                               AS template_name,
                COUNT(*)                                                    AS run_count,
                COUNT(*) FILTER (WHERE wr.status = 'completed')            AS completed_count,
                COUNT(*) FILTER (WHERE wr.status = 'failed')               AS failed_count
            FROM workflow_run wr
            LEFT JOIN graph_template gt ON gt.id = wr.graph_template_id
            WHERE wr.created_at >= :since
              AND wr.deleted_at IS NULL
            GROUP BY gt.name
            ORDER BY run_count DESC
            """, nativeQuery = true)
    List<Object[]> getTemplateAnalytics(@Param("since") Instant since);

    // --- Public landing-page metrics queries ---

    /**
     * Total run count (live rows only). Used by the public landing-metrics endpoint.
     * Native SQL keeps the path explicit; soft-delete is filtered via {@code deleted_at IS NULL}.
     */
    @Query(value = "SELECT COUNT(*) FROM workflow_run WHERE deleted_at IS NULL", nativeQuery = true)
    long countAllRuns();

    /**
     * Global, live-rows-only run count over a trailing window — telemetry runCount. Counts
     * {@code workflow_run} rows across all orgs created at-or-after {@code since}, excluding
     * soft-deleted rows. Named "Since" (not "After") because the comparison is {@code >=}.
     */
    @Query(
            value = "SELECT COUNT(*) FROM workflow_run WHERE deleted_at IS NULL AND created_at >= :since",
            nativeQuery = true)
    long countAllRunsSince(@Param("since") Instant since);

    /**
     * Cross-org completed/terminal counts over a window. Returned as a two-element
     * {@code Object[]} of {@code [completed, terminal]} (BIGINT). The aggregator
     * computes {@code completed / terminal * 100} or returns {@code null} when
     * {@code terminal == 0}.
     */
    @Query(value = """
            SELECT
                COUNT(*) FILTER (WHERE status = 'completed')              AS completed,
                COUNT(*) FILTER (WHERE status IN ('completed', 'failed')) AS terminal
            FROM workflow_run
            WHERE created_at >= :since AND deleted_at IS NULL
            """, nativeQuery = true)
    Object[] getGlobalSuccessRateStats(@Param("since") Instant since);

    /**
     * P50 of wall-clock workflow_run duration over the window.
     *
     * <p>Reads {@code workflow_run.completed_at - started_at} directly, NOT aggregated over
     * {@code node_execution}. This measures end-to-end run wall-clock — the right number
     * for "median run time" on the landing page. The existing analytics p50 in
     * {@link #getOverviewStats} sums per-node durations, which is a different metric
     * (CPU-time across nodes).
     *
     * <p>Returns {@code null} when there are no qualifying rows; the service maps null
     * through to {@code LandingMetricsResponse.medianRunSeconds} (boxed Long).
     */
    @Query(value = """
            SELECT PERCENTILE_CONT(0.50) WITHIN GROUP (
                ORDER BY EXTRACT(EPOCH FROM (completed_at - started_at))
            )
            FROM workflow_run
            WHERE created_at >= :since
              AND deleted_at IS NULL
              AND status = 'completed'
              AND started_at IS NOT NULL
              AND completed_at IS NOT NULL
            """, nativeQuery = true)
    Double getGlobalMedianRunSeconds(@Param("since") Instant since);

    // --- Soft-delete cascade primitives ---

    @Modifying
    @Query(
            value = "UPDATE workflow_run SET deleted_at = :deletedAt " + "WHERE id IN (:ids) AND deleted_at IS NULL",
            nativeQuery = true)
    int softDeleteAllByIds(@Param("ids") Collection<UUID> ids, @Param("deletedAt") Instant deletedAt);

    /**
     * Hard-delete a single tombstoned run. Idempotent: scoped to {@code deleted_at IS NOT NULL}
     * so a second call affects zero rows. Used by both the afterCommit hook in
     * WorkflowRunService and
     * {@link com.choruskube.core.reconciler.WorkflowRunReconciler}. The V1 {@code ON DELETE CASCADE}
     * on {@code node_execution.workflow_run_id} and {@code execution_log.node_execution_id}
     * takes care of the child rows transparently.
     */
    @Modifying
    @Query(value = "DELETE FROM workflow_run WHERE id = :id AND deleted_at IS NOT NULL", nativeQuery = true)
    int hardDeleteTombstoneById(@Param("id") UUID id);

    /**
     * Reconciler driver query — returns tombstoned runs with their Temporal workflow ID for
     * batch processing. Native SQL bypasses the entity-level
     * {@code @SQLRestriction("deleted_at IS NULL")}.
     */
    @Query(
            value = "SELECT id AS id, external_run_id AS externalRunId FROM workflow_run "
                    + "WHERE deleted_at IS NOT NULL LIMIT :batchSize",
            nativeQuery = true)
    List<TombstonedWorkflowRunRef> findTombstonedBatch(@Param("batchSize") int batchSize);
}
