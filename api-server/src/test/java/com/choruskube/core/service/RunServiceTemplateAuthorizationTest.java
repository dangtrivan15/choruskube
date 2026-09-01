package com.choruskube.core.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.choruskube.core.dto.CreateRunRequest;
import com.choruskube.core.dto.ValidationResponse;
import com.choruskube.core.exception.ValidationException;
import com.choruskube.core.model.GraphTemplate;
import com.choruskube.core.observability.AuditSink;
import com.choruskube.core.repository.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.temporal.client.WorkflowClient;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class RunServiceTemplateAuthorizationTest {

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
    private WorkloadService workloadService;

    @Mock
    private AuthorizationService authService;

    @Mock
    private AuditSink auditService;

    private RunService service;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
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
                workloadService,
                authService,
                Optional.empty(), // quotaService
                Optional.empty(), // placementResolver
                null, // usageSink
                auditService, // auditSink
                null, // storagePrefixResolver
                null, // runPullRequestService
                null, // softwareProjectRepo
                null, // repoGroupMemberRepo
                null, // credentialPreflightChecker
                null, // uploadService
                null, // taskRepo
                null, // storyRepo
                null, // epicRepo
                null, // artifactResolutionService
                null, // applicationEventPublisher
                new com.choruskube.core.scope.NoOpScopeProvider(),
                new DecisionOptionsResolver(),
                null, // roadmapCandidateMaterializer
                null, // roadmapCandidatesArtifactResolver
                null, // nodeExecutionClaimService
                null); // escalationContextResolver
    }

    @Test
    void startRun_checksTemplateReadAccessBeforeStarting() {
        UUID templateId = UUID.randomUUID();
        GraphTemplate template = new GraphTemplate();
        template.setId(templateId);
        template.setSystem(true);
        when(graphTemplateRepo.findById(templateId)).thenReturn(Optional.of(template));
        when(templateNodeRepo.findByGraphTemplateId(templateId)).thenReturn(List.of());
        when(edgeRepo.findByGraphTemplateId(templateId)).thenReturn(List.of());
        when(validationService.validate(any(), any()))
                .thenReturn(new ValidationResponse(false, List.of("empty template")));

        // Validation fails, so the run is never created — but authorization must already
        // have happened by then.
        assertThrows(
                ValidationException.class,
                () -> service.startRun(new CreateRunRequest(templateId, Map.of(), "n", null)));

        verify(authService).checkTemplateReadAccess(true, templateId);
    }
}
