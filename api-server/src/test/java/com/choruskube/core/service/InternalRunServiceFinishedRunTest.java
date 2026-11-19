package com.choruskube.core.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.choruskube.core.SharedContextTest;
import com.choruskube.core.config.WorkflowClientRegistry;
import com.choruskube.core.dto.InternalUpdateRunStatusRequest;
import com.choruskube.core.exception.ValidationException;
import com.choruskube.core.model.GraphTemplate;
import com.choruskube.core.model.WorkflowRun;
import com.choruskube.core.model.enums.WorkflowRunStatus;
import com.choruskube.core.repository.GraphTemplateRepository;
import com.choruskube.core.repository.WorkflowRunRepository;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowStub;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

/**
 * A finished run stays finished. The orchestrator's run-status write must refuse it as every user
 * operation does, and a user's cancel must refuse a run the workflow has already finished.
 */
@Transactional
class InternalRunServiceFinishedRunTest extends SharedContextTest {

    private static final Instant ENDED_AT = Instant.parse("2026-01-02T03:04:05.123456Z");

    @Autowired
    private InternalRunService internalRunService;

    @Autowired
    private RunService runService;

    @Autowired
    private WorkflowRunRepository runRepo;

    @Autowired
    private GraphTemplateRepository graphTemplateRepo;

    @PersistenceContext
    private EntityManager entityManager;

    @MockitoBean
    private WorkflowClientRegistry workflowClientRegistry;

    @MockitoBean
    private RunEventPublisher runEventPublisher;

    private GraphTemplate template;

    @BeforeEach
    void setUp() {
        WorkflowClient client = mock(WorkflowClient.class);
        when(client.newUntypedWorkflowStub(anyString())).thenReturn(mock(WorkflowStub.class));
        when(workflowClientRegistry.clientFor(any())).thenReturn(client);

        template = new GraphTemplate();
        template.setName("Finished Run Test Template");
        template.setGraphId("finished-run-test-" + UUID.randomUUID());
        template.setVersion(1);
        template = graphTemplateRepo.save(template);
    }

    @ParameterizedTest
    @ValueSource(strings = {"pending", "running", "paused", "awaiting_human", "awaiting_retry"})
    void aCancelledRun_receivingAnActiveStatus_staysCancelled(String late) {
        WorkflowRun run = endedRun(WorkflowRunStatus.cancelled);

        internalRunService.updateRunStatus(run.getId(), new InternalUpdateRunStatusRequest(late));

        WorkflowRun reloaded = reload(run);
        assertThat(reloaded.getStatus()).isEqualTo(WorkflowRunStatus.cancelled);
        assertThat(reloaded.getCompletedAt()).isEqualTo(ENDED_AT);
        verifyNoInteractions(runEventPublisher);
    }

    @Test
    void aCompletedRun_receivingFailed_staysCompleted() {
        WorkflowRun run = endedRun(WorkflowRunStatus.completed);

        internalRunService.updateRunStatus(run.getId(), new InternalUpdateRunStatusRequest("failed"));

        WorkflowRun reloaded = reload(run);
        assertThat(reloaded.getStatus()).isEqualTo(WorkflowRunStatus.completed);
        assertThat(reloaded.getCompletedAt()).isEqualTo(ENDED_AT);
        verifyNoInteractions(runEventPublisher);
    }

    @Test
    void aCancelledRun_receivingCancelledAgain_changesNothing() {
        WorkflowRun run = endedRun(WorkflowRunStatus.cancelled);

        internalRunService.updateRunStatus(run.getId(), new InternalUpdateRunStatusRequest("cancelled"));

        WorkflowRun reloaded = reload(run);
        assertThat(reloaded.getStatus()).isEqualTo(WorkflowRunStatus.cancelled);
        assertThat(reloaded.getCompletedAt()).isEqualTo(ENDED_AT);
        verifyNoInteractions(runEventPublisher);
    }

    /** The workflow's own "cancelled" write that follows a user's cancel no longer stamps the end. */
    @Test
    void aUserCancel_recordsWhenTheRunEnded() {
        WorkflowRun run = liveRun();

        runService.cancelRun(run.getId());
        internalRunService.updateRunStatus(run.getId(), new InternalUpdateRunStatusRequest("cancelled"));

        WorkflowRun reloaded = reload(run);
        assertThat(reloaded.getStatus()).isEqualTo(WorkflowRunStatus.cancelled);
        assertThat(reloaded.getCompletedAt()).isNotNull();
    }

    @Test
    void aRunTheWorkflowAlreadyCompleted_refusesALaterCancel() {
        WorkflowRun run = liveRun();
        internalRunService.updateRunStatus(run.getId(), new InternalUpdateRunStatusRequest("completed"));
        Instant completedAt = reload(run).getCompletedAt();

        assertThatThrownBy(() -> runService.cancelRun(run.getId())).isInstanceOf(ValidationException.class);

        WorkflowRun reloaded = reload(run);
        assertThat(reloaded.getStatus()).isEqualTo(WorkflowRunStatus.completed);
        assertThat(reloaded.getCompletedAt()).isEqualTo(completedAt);
        verify(workflowClientRegistry, never()).clientFor(any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"awaiting_retry", "completed"})
    void aLiveRun_takesTheStatusItIsSent(String next) {
        WorkflowRun run = liveRun();

        internalRunService.updateRunStatus(run.getId(), new InternalUpdateRunStatusRequest(next));

        assertThat(reload(run).getStatus()).isEqualTo(WorkflowRunStatus.valueOf(next));
        verify(runEventPublisher).publishRunStatusChanged(run.getId(), next);
    }

    private WorkflowRun liveRun() {
        WorkflowRun run = new WorkflowRun();
        run.setGraphTemplateId(template.getId());
        run.setStatus(WorkflowRunStatus.running);
        run.setStartedAt(ENDED_AT.minus(1, ChronoUnit.HOURS));
        run.setExternalRunId("choruskube-run-" + UUID.randomUUID());
        return runRepo.saveAndFlush(run);
    }

    private WorkflowRun endedRun(WorkflowRunStatus status) {
        WorkflowRun run = liveRun();
        run.setStatus(status);
        run.setCompletedAt(ENDED_AT);
        run = runRepo.saveAndFlush(run);
        entityManager.clear();
        return run;
    }

    private WorkflowRun reload(WorkflowRun run) {
        entityManager.flush();
        entityManager.clear();
        return runRepo.findById(run.getId()).orElseThrow();
    }
}
