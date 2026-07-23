package com.choruskube.core.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.choruskube.core.config.SingleTenant;
import com.choruskube.core.credential.CredentialPreflightChecker;
import com.choruskube.core.dto.CreateRunRequest;
import com.choruskube.core.dto.ValidationResponse;
import com.choruskube.core.exception.InvalidCredentialException;
import com.choruskube.core.model.GraphTemplate;
import com.choruskube.core.model.WorkflowRun;
import com.choruskube.core.model.enums.WorkflowRunStatus;
import com.choruskube.core.observability.UsageSink;
import com.choruskube.core.repository.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.temporal.api.common.v1.WorkflowExecution;
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
 * Verifies that {@code startRun} gates the run on {@link CredentialPreflightChecker}. The seam is
 * keyed by {@code runId}, so the check runs after the first {@code runRepo.save} (which assigns the
 * id); a failing check throws and {@code startRun}'s {@code @Transactional} rolls the insert back in
 * production. These unit tests have no real transaction, so the first save is observed before the
 * throw.
 */
@ExtendWith(MockitoExtension.class)
class RunServiceCredentialCheckTest {

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
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final UUID TEMPLATE_ID = UUID.randomUUID();
    private static final UUID SAVED_RUN_ID = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        // Initialize transaction synchronization so that
        // TransactionSynchronizationManager.registerSynchronization() does not throw.
        // The afterCommit callbacks simply never fire — which is fine since these tests
        // verify credential-check ordering, not Temporal workflow startup.
        TransactionSynchronizationManager.initSynchronization();
        lenient().when(storagePrefixResolver.storagePrefixForRun(any())).thenReturn(SingleTenant.SLUG);
        service = new RunService(
                runRepo,
                execRepo,
                edgeRepo,
                snapshotBuilder,
                workflowClient,
                graphTemplateRepo,
                templateNodeRepo,
                validationService,
                executionLogRepo,
                objectMapper,
                eventPublisher,
                gitRepoRepo,
                null,
                new AuthorizationService(new AlwaysAllowAuthorizationStrategy(), false),
                Optional.of(quotaService),
                usageEventService,
                null, // auditSink
                storagePrefixResolver,
                runPullRequestService,
                softwareProjectRepo,
                repoGroupMemberRepo,
                credentialPreflightChecker,
                null,
                null,
                null,
                mock(ApplicationEventPublisher.class),
                new com.choruskube.core.scope.NoOpScopeProvider(),
                new DecisionOptionsResolver(),
                null,
                null);
    }

    @AfterEach
    void tearDown() {
        TransactionSynchronizationManager.clearSynchronization();
    }

    /**
     * Stubs everything up to and including the first {@code runRepo.save} — the point at which the
     * run gains its id and the preflight seam is invoked. The save stub is needed by every path now
     * that preflight runs post-save.
     */
    private void stubPreCredentialPath() {
        GraphTemplate template = new GraphTemplate();
        template.setId(TEMPLATE_ID);
        template.setName("Test Template");
        template.setInputSchema("[]");
        when(graphTemplateRepo.findById(TEMPLATE_ID)).thenReturn(Optional.of(template));
        when(templateNodeRepo.findByGraphTemplateId(TEMPLATE_ID)).thenReturn(List.of());
        when(edgeRepo.findByGraphTemplateId(TEMPLATE_ID)).thenReturn(List.of());
        when(validationService.validate(any(), any())).thenReturn(new ValidationResponse(true, List.of()));
        doNothing().when(quotaService).checkRunQuota();

        WorkflowRun savedRun = new WorkflowRun();
        savedRun.setId(SAVED_RUN_ID);
        savedRun.setGraphTemplateId(TEMPLATE_ID);
        savedRun.setStatus(WorkflowRunStatus.pending);
        lenient().when(runRepo.save(any())).thenReturn(savedRun);
    }

    private void stubPostCredentialPath() {
        lenient()
                .when(workflowClient.newUntypedWorkflowStub(anyString(), any()))
                .thenReturn(workflowStub);
        lenient().when(workflowStub.start(any())).thenReturn(WorkflowExecution.getDefaultInstance());
        lenient().when(runPullRequestService.getPullRequests(any())).thenReturn(List.of());
    }

    @Test
    void startRun_expiredCredential_throwsInvalidCredentialException() {
        stubPreCredentialPath();
        doThrow(new InvalidCredentialException(
                        "GitHub credential is invalid — update it in Org Settings → Integrations"))
                .when(credentialPreflightChecker)
                .checkPreflight(SAVED_RUN_ID);

        CreateRunRequest request = new CreateRunRequest(TEMPLATE_ID, Map.of(), "test-run", null);
        assertThatThrownBy(() -> service.startRun(request)).isInstanceOf(InvalidCredentialException.class);

        // Preflight runs after the first save; @Transactional rolls it back in production.
        verify(credentialPreflightChecker).checkPreflight(SAVED_RUN_ID);
    }

    @Test
    void startRun_insufficientPermissions_throwsInvalidCredentialException() {
        stubPreCredentialPath();
        doThrow(new InvalidCredentialException("GitHub credential is invalid"))
                .when(credentialPreflightChecker)
                .checkPreflight(SAVED_RUN_ID);

        CreateRunRequest request = new CreateRunRequest(TEMPLATE_ID, Map.of(), "test-run", null);
        assertThatThrownBy(() -> service.startRun(request)).isInstanceOf(InvalidCredentialException.class);

        verify(credentialPreflightChecker).checkPreflight(SAVED_RUN_ID);
    }

    @Test
    void startRun_validCredential_proceeds() {
        stubPreCredentialPath();
        doNothing().when(credentialPreflightChecker).checkPreflight(SAVED_RUN_ID);
        stubPostCredentialPath();

        CreateRunRequest request = new CreateRunRequest(TEMPLATE_ID, Map.of(), "test-run", null);
        assertThatCode(() -> service.startRun(request)).doesNotThrowAnyException();

        verify(runRepo, atLeastOnce()).save(any());
    }

    @Test
    void startRun_noCredential_proceeds() {
        stubPreCredentialPath();
        // checkPreflight does nothing when no credential configured
        doNothing().when(credentialPreflightChecker).checkPreflight(SAVED_RUN_ID);
        stubPostCredentialPath();

        CreateRunRequest request = new CreateRunRequest(TEMPLATE_ID, Map.of(), "test-run", null);
        assertThatCode(() -> service.startRun(request)).doesNotThrowAnyException();
    }

    @Test
    void startRun_unreachableCredential_proceeds() {
        stubPreCredentialPath();
        // UNREACHABLE status does not throw — only EXPIRED and INSUFFICIENT_PERMISSIONS do
        doNothing().when(credentialPreflightChecker).checkPreflight(SAVED_RUN_ID);
        stubPostCredentialPath();

        CreateRunRequest request = new CreateRunRequest(TEMPLATE_ID, Map.of(), "test-run", null);
        assertThatCode(() -> service.startRun(request)).doesNotThrowAnyException();
    }

    @Test
    void startRun_nullHealthStatus_proceeds() {
        stubPreCredentialPath();
        doNothing().when(credentialPreflightChecker).checkPreflight(SAVED_RUN_ID);
        stubPostCredentialPath();

        CreateRunRequest request = new CreateRunRequest(TEMPLATE_ID, Map.of(), "test-run", null);
        assertThatCode(() -> service.startRun(request)).doesNotThrowAnyException();
    }

    @Test
    void startRun_credentialCheckCalledAfterQuotaCheck() {
        stubPreCredentialPath();
        doThrow(new InvalidCredentialException("invalid"))
                .when(credentialPreflightChecker)
                .checkPreflight(SAVED_RUN_ID);

        CreateRunRequest request = new CreateRunRequest(TEMPLATE_ID, Map.of(), "test-run", null);
        assertThatThrownBy(() -> service.startRun(request)).isInstanceOf(InvalidCredentialException.class);

        // Quota gate (pre-save) and the credential preflight (post-save) both ran.
        verify(quotaService).checkRunQuota();
        verify(credentialPreflightChecker).checkPreflight(SAVED_RUN_ID);
    }
}
