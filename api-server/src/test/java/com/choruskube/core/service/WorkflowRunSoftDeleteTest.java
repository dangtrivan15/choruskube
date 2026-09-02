package com.choruskube.core.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.choruskube.core.BaseTest;
import com.choruskube.core.config.WorkflowClientRegistry;
import com.choruskube.core.model.WorkflowRun;
import com.choruskube.core.model.enums.WorkflowRunStatus;
import com.choruskube.core.repository.WorkflowRunRepository;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowNotFoundException;
import io.temporal.client.WorkflowStub;
import io.temporal.serviceclient.WorkflowServiceStubs;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class WorkflowRunSoftDeleteTest extends BaseTest {

    @MockitoBean
    private WorkflowServiceStubs workflowServiceStubs;

    @MockitoBean
    private WorkflowClient workflowClient;

    @MockitoBean
    private WorkflowClientRegistry workflowClientRegistry;

    @Autowired
    private WorkflowRunService workflowRunService;

    @Autowired
    private WorkflowRunRepository runRepo;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PlatformTransactionManager txManager;

    // This test is NOT @Transactional (the reconciler uses REQUIRES_NEW), so every seeded
    // graph_template / workflow_run is committed. Track them and hard-delete in @AfterEach so they
    // don't leak into other suites' now-global (de-orged) counts — e.g. AnalyticsControllerTest,
    // which previously relied on per-org scoping to ignore other tests' committed runs.
    private final java.util.List<UUID> seededTemplateIds = new java.util.ArrayList<>();

    @org.junit.jupiter.api.AfterEach
    void cleanUpSeededRows() {
        for (UUID templateId : seededTemplateIds) {
            jdbc.update("DELETE FROM workflow_run WHERE graph_template_id = ?", templateId);
            jdbc.update("DELETE FROM graph_template WHERE id = ?", templateId);
        }
        seededTemplateIds.clear();
    }

    // -----------------------------------------------------------------------
    // @SQLRestriction on entity + softDelete primitive
    // -----------------------------------------------------------------------

    @Test
    void softDelete_flipsDeletedAtAndHidesFromStandardQueries() {
        UUID runId = seedRun("wf-" + UUID.randomUUID());

        new TransactionTemplate(txManager)
                .executeWithoutResult(
                        status -> runRepo.softDeleteAllByIds(java.util.List.of(runId), java.time.Instant.now()));

        // @SQLRestriction kicks in on fresh reads.
        assertThat(runRepo.findById(runId)).isEmpty();

        // Native query bypasses @SQLRestriction — row still present with deleted_at set.
        Object deletedAt = jdbc.queryForObject("SELECT deleted_at FROM workflow_run WHERE id = ?", Object.class, runId);
        assertThat(deletedAt).isNotNull();
    }

    @Test
    void deleteAllByIds_requiresOuterTransaction() {
        UUID runId = seedRun("wf-" + UUID.randomUUID());
        assertThatThrownBy(() -> workflowRunService.deleteAllByIds(java.util.List.of(runId)))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test
    void deleteAllByIds_softDeletesAllLiveRuns() {
        UUID r1 = seedRun("wf-1-" + UUID.randomUUID());
        UUID r2 = seedRun("wf-2-" + UUID.randomUUID());

        // Mock the Temporal stub so afterCommit terminate calls don't blow up post-commit.
        WorkflowStub stub = mock(WorkflowStub.class);
        when(workflowClientRegistry.clientFor(any())).thenReturn(workflowClient);
        when(workflowClient.newUntypedWorkflowStub(anyString())).thenReturn(stub);

        new TransactionTemplate(txManager)
                .executeWithoutResult(status -> workflowRunService.deleteAllByIds(java.util.List.of(r1, r2)));

        Long tombstoned = jdbc.queryForObject(
                "SELECT COUNT(*) FROM workflow_run WHERE id IN (?, ?) AND deleted_at IS NOT NULL", Long.class, r1, r2);
        assertThat(tombstoned).isEqualTo(2);
        assertThat(r1).isNotEqualTo(r2);
    }

    // -----------------------------------------------------------------------
    // reconcileTombstonedBatch — Temporal terminate + DB hard-delete
    // -----------------------------------------------------------------------

    @Test
    void reconcileTombstonedBatch_callsTemporalTerminateAndHardDeletesRow() {
        String externalId = "wf-" + UUID.randomUUID();
        UUID runId = seedRun(externalId);
        tombstoneRun(runId);
        WorkflowStub stub = mock(WorkflowStub.class);
        when(workflowClientRegistry.clientFor(any())).thenReturn(workflowClient);
        when(workflowClient.newUntypedWorkflowStub(externalId)).thenReturn(stub);

        int cleaned = workflowRunService.reconcileTombstonedBatch(100);
        assertThat(cleaned).isGreaterThanOrEqualTo(1);

        verify(stub, atLeastOnce()).terminate(anyString());

        Long remaining = jdbc.queryForObject("SELECT COUNT(*) FROM workflow_run WHERE id = ?", Long.class, runId);
        assertThat(remaining).isZero();
    }

    @Test
    void reconcileTombstonedBatch_swallowsWorkflowNotFoundAndStillHardDeletes() {
        String externalId = "wf-" + UUID.randomUUID();
        UUID runId = seedRun(externalId);
        tombstoneRun(runId);
        WorkflowStub stub = mock(WorkflowStub.class);
        when(workflowClientRegistry.clientFor(any())).thenReturn(workflowClient);
        when(workflowClient.newUntypedWorkflowStub(externalId)).thenReturn(stub);
        WorkflowExecution execution =
                WorkflowExecution.newBuilder().setWorkflowId(externalId).build();
        doThrow(new WorkflowNotFoundException(execution, "DAGExecutorWorkflow", null))
                .when(stub)
                .terminate(anyString());

        workflowRunService.reconcileTombstonedBatch(100);

        Long remaining = jdbc.queryForObject("SELECT COUNT(*) FROM workflow_run WHERE id = ?", Long.class, runId);
        assertThat(remaining).isZero();
    }

    @Test
    void cleanupAndHardDelete_isIdempotent() {
        String externalId = "wf-" + UUID.randomUUID();
        UUID runId = seedRun(externalId);
        tombstoneRun(runId);
        WorkflowStub stub = mock(WorkflowStub.class);
        when(workflowClientRegistry.clientFor(any())).thenReturn(workflowClient);
        when(workflowClient.newUntypedWorkflowStub(externalId)).thenReturn(stub);

        workflowRunService.cleanupAndHardDelete(runId, externalId, null);
        workflowRunService.cleanupAndHardDelete(runId, externalId, null); // second call: row already gone

        Long remaining = jdbc.queryForObject("SELECT COUNT(*) FROM workflow_run WHERE id = ?", Long.class, runId);
        assertThat(remaining).isZero();
    }

    /**
     * The namespace is snapshotted from the live row alongside external_run_id, before the
     * organization cascade can remove anything a later lookup would read. Re-resolving here
     * would leave the reconciler retrying a run it can never address.
     */
    @Test
    void cleanupAndHardDelete_terminatesInTheRunsRecordedNamespace() {
        WorkflowStub stub = mock(WorkflowStub.class);
        WorkflowClient tenantClient = mock(WorkflowClient.class);
        when(tenantClient.newUntypedWorkflowStub(anyString())).thenReturn(stub);
        when(workflowClientRegistry.clientFor("tenant-ns")).thenReturn(tenantClient);

        UUID runId = UUID.randomUUID();
        workflowRunService.cleanupAndHardDelete(runId, "choruskube-run-" + runId, "tenant-ns");

        verify(tenantClient).newUntypedWorkflowStub("choruskube-run-" + runId);
        verify(stub).terminate(anyString());
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private UUID seedRun(String externalRunId) {
        // Need a graph_template row to satisfy workflow_run.graph_template_id FK.
        UUID templateId = (UUID) jdbc.queryForObject("""
                INSERT INTO graph_template (graph_id, version, name, input_schema, system)
                VALUES (?, 1, ?, '[]'::jsonb, false)
                RETURNING id
                """, UUID.class, "gt-" + UUID.randomUUID(), "seed-tmpl");
        seededTemplateIds.add(templateId);

        WorkflowRun run = new WorkflowRun();
        run.setGraphTemplateId(templateId);
        run.setStatus(WorkflowRunStatus.pending);
        run.setExternalRunId(externalRunId);
        return runRepo.save(run).getId();
    }

    private void tombstoneRun(UUID runId) {
        jdbc.update("UPDATE workflow_run SET deleted_at = now() WHERE id = ? AND deleted_at IS NULL", runId);
    }
}
