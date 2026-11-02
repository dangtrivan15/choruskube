package com.choruskube.core.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.choruskube.core.config.SingleTenant;
import com.choruskube.core.config.WorkflowClientRegistry;
import com.choruskube.core.credential.CredentialPreflightChecker;
import com.choruskube.core.dto.CreateRunRequest;
import com.choruskube.core.dto.ValidationResponse;
import com.choruskube.core.exception.QuotaExceededException;
import com.choruskube.core.model.GraphTemplate;
import com.choruskube.core.model.WorkflowRun;
import com.choruskube.core.model.enums.WorkflowRunStatus;
import com.choruskube.core.observability.UsageSink;
import com.choruskube.core.repository.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowStub;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Which run-start quota check each start entry asks the {@link QuotaChecker} for. A system start
 * that unblocks runs already holding concurrent-run slots skips only that cap; every other start
 * keeps the full check.
 */
@ExtendWith(MockitoExtension.class)
class RunServiceRunQuotaTest {

    @Mock
    private WorkflowRunRepository runRepo;

    @Mock
    private NodeExecutionRepository execRepo;

    @Mock
    private TemplateEdgeRepository edgeRepo;

    @Mock
    private GraphSnapshotBuilder snapshotBuilder;

    @Mock
    private WorkflowClient workflowClient;

    @Mock
    private GraphTemplateRepository graphTemplateRepo;

    @Mock
    private TemplateNodeRepository templateNodeRepo;

    @Mock
    private GraphValidationService validationService;

    @Mock
    private ExecutionLogRepository executionLogRepo;

    @Mock
    private RunEventPublisher eventPublisher;

    @Mock
    private GitRepoRepository gitRepoRepo;

    @Mock
    private QuotaChecker quotaService;

    @Mock
    private RunPlacementService placements;

    @Mock
    private WorkflowClientRegistry workflowClients;

    @Mock
    private UsageSink usageEventService;

    @Mock
    private StoragePrefixResolver storagePrefixResolver;

    @Mock
    private RunPullRequestService runPullRequestService;

    @Mock
    private SoftwareProjectRepository softwareProjectRepo;

    @Mock
    private RepoGroupMemberRepository repoGroupMemberRepo;

    @Mock
    private CredentialPreflightChecker credentialPreflightChecker;

    @Mock
    private WorkflowStub workflowStub;

    private RunService service;

    private static final UUID TEMPLATE_ID = UUID.randomUUID();
    private static final UUID SAVED_RUN_ID = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        // startRun registers an afterCommit hook; with no real transaction it simply never fires.
        TransactionSynchronizationManager.initSynchronization();
        lenient().when(storagePrefixResolver.storagePrefixForRun(any())).thenReturn(SingleTenant.SLUG);
        service = new RunService(
                runRepo,
                execRepo,
                edgeRepo,
                snapshotBuilder,
                graphTemplateRepo,
                templateNodeRepo,
                validationService,
                executionLogRepo,
                new ObjectMapper(),
                eventPublisher,
                gitRepoRepo,
                new AuthorizationService(new AlwaysAllowAuthorizationStrategy(), false),
                Optional.of(quotaService),
                placements,
                workflowClients,
                usageEventService,
                null, // auditSink
                storagePrefixResolver,
                runPullRequestService,
                softwareProjectRepo,
                repoGroupMemberRepo,
                credentialPreflightChecker,
                null,
                null,
                null, // storyRepo
                null, // epicRepo
                null,
                mock(ApplicationEventPublisher.class),
                new com.choruskube.core.scope.NoOpScopeProvider(),
                new DecisionOptionsResolver(),
                null,
                null,
                null, // nodeExecutionClaimService
                null, // escalationContextResolver
                null, // roadmapProposalValidator
                null, // taskSettlement
                mock(GateRecheckService.class),
                mock(GateFinalizeService.class),
                null // storySpecService
                );
    }

    @AfterEach
    void tearDown() {
        TransactionSynchronizationManager.clearSynchronization();
    }

    @Test
    void startRun_checksTheFullRunQuota() {
        stubStartPath();

        service.startRun(request());

        verify(quotaService).checkRunQuota();
        verifyNoMoreInteractions(quotaService);
    }

    @Test
    void startRunExemptFromConcurrentQuota_checksEveryRunQuotaButTheConcurrentCap() {
        stubStartPath();

        service.startRunExemptFromConcurrentQuota(request());

        verify(quotaService).checkRunQuotaExceptConcurrency();
        verifyNoMoreInteractions(quotaService);
    }

    @Test
    void startRunExemptFromConcurrentQuota_refusedByTheQuota_savesNoRun() {
        stubTemplate();
        doThrow(new QuotaExceededException("monthly runs")).when(quotaService).checkRunQuotaExceptConcurrency();

        assertThatThrownBy(() -> service.startRunExemptFromConcurrentQuota(request()))
                .isInstanceOf(QuotaExceededException.class);

        verify(runRepo, never()).save(any());
    }

    private static CreateRunRequest request() {
        return new CreateRunRequest(TEMPLATE_ID, Map.of(), "quota-run", null);
    }

    private void stubTemplate() {
        GraphTemplate template = new GraphTemplate();
        template.setId(TEMPLATE_ID);
        template.setName("Test Template");
        template.setInputSchema("[]");
        when(graphTemplateRepo.findById(TEMPLATE_ID)).thenReturn(Optional.of(template));
        when(templateNodeRepo.findByGraphTemplateId(TEMPLATE_ID)).thenReturn(List.of());
        when(edgeRepo.findByGraphTemplateId(TEMPLATE_ID)).thenReturn(List.of());
        when(validationService.validate(any(), any())).thenReturn(new ValidationResponse(true, List.of()));
    }

    private void stubStartPath() {
        stubTemplate();
        WorkflowRun savedRun = new WorkflowRun();
        savedRun.setId(SAVED_RUN_ID);
        savedRun.setGraphTemplateId(TEMPLATE_ID);
        savedRun.setStatus(WorkflowRunStatus.pending);
        when(runRepo.save(any())).thenReturn(savedRun);
        when(placements.placeFor(any())).thenReturn(new RunPlacement("choruskube", "choruskube"));
        when(workflowClients.clientFor(any())).thenReturn(workflowClient);
        when(workflowClient.newUntypedWorkflowStub(anyString(), any())).thenReturn(workflowStub);
        lenient().when(runPullRequestService.getPullRequests(any())).thenReturn(List.of());
    }
}
