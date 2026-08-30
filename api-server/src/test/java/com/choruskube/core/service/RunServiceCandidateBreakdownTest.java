package com.choruskube.core.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.choruskube.core.dto.CandidateEpicProposal;
import com.choruskube.core.dto.ResolvedArtifactGroup;
import com.choruskube.core.dto.RoadmapCandidatesDocument;
import com.choruskube.core.dto.RunResponse;
import com.choruskube.core.model.NodeExecution;
import com.choruskube.core.model.WorkflowRun;
import com.choruskube.core.model.enums.NodeExecutionStatus;
import com.choruskube.core.model.enums.WorkflowRunStatus;
import com.choruskube.core.repository.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.temporal.client.WorkflowClient;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Covers a gap surfaced during code review: {@code HumanGatePanel} (rendered by {@code
 * DetailPanel} on the Run Detail page) accepts a {@code candidateBreakdown} prop, but {@link
 * RunService#getRun} previously never populated it on {@link
 * com.choruskube.core.dto.NodeExecutionResponse} — only {@link PendingGateService} (used by the
 * Approvals dashboard) did. That left the Run Detail page's gate surface unable to ever show the
 * editable breakdown, silently forwarding no {@code editedCandidates} on approval. {@code
 * RunService.toResponse} now mirrors {@code PendingGateService}'s resolution so both gate surfaces
 * behave the same way.
 */
@ExtendWith(MockitoExtension.class)
class RunServiceCandidateBreakdownTest {

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
    private ArtifactResolutionService artifactResolutionService;

    @Mock
    private RoadmapCandidateMaterializer roadmapCandidateMaterializer;

    @Mock
    private RoadmapCandidatesArtifactResolver roadmapCandidatesArtifactResolver;

    private RunService service;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final UUID runId = UUID.randomUUID();
    private final UUID nodeExecId = UUID.randomUUID();
    private final UUID templateNodeId = UUID.randomUUID();

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
                null,
                new AuthorizationService(new AlwaysAllowAuthorizationStrategy(), false),
                Optional.empty(),
                Optional.empty(),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null, // storyRepo
                null, // epicRepo
                artifactResolutionService,
                null,
                new com.choruskube.core.scope.NoOpScopeProvider(),
                new DecisionOptionsResolver(),
                roadmapCandidateMaterializer,
                roadmapCandidatesArtifactResolver,
                null, // nodeExecutionClaimService — unused (signalHumanDecision not exercised)
                null); // escalationContextResolver — unused; see RunServiceEscalationContextTest
    }

    private WorkflowRun stubRun() {
        WorkflowRun run = new WorkflowRun();
        run.setId(runId);
        run.setStatus(WorkflowRunStatus.running);
        run.setExternalRunId("test-workflow-id");
        when(runRepo.findById(runId)).thenReturn(Optional.of(run));
        return run;
    }

    private NodeExecution stubAwaitingHumanExec() {
        NodeExecution exec = new NodeExecution();
        exec.setId(nodeExecId);
        exec.setWorkflowRunId(runId);
        exec.setTemplateNodeId(templateNodeId);
        exec.setStatus(NodeExecutionStatus.awaiting_human);
        exec.setGraphVersion(1);
        when(execRepo.findByWorkflowRunId(runId)).thenReturn(List.of(exec));
        return exec;
    }

    private static RoadmapCandidatesDocument sampleCandidates() {
        return new RoadmapCandidatesDocument(
                null,
                List.of(new CandidateEpicProposal(
                        "Bulk Import", "desc", "why", List.of("repo-a"), "High", List.of(), null, null)),
                null);
    }

    @Test
    void awaitingHumanNode_populatesCandidateBreakdownFromResolvedRequiredArtifacts() {
        stubRun();
        stubAwaitingHumanExec();
        List<ResolvedArtifactGroup> requiredArtifacts = List.of();
        when(artifactResolutionService.resolveRequiredArtifacts(templateNodeId, runId))
                .thenReturn(requiredArtifacts);
        RoadmapCandidatesDocument candidates = sampleCandidates();
        when(roadmapCandidatesArtifactResolver.resolve(runId, requiredArtifacts))
                .thenReturn(candidates);

        RunResponse response = service.getRun(runId);

        assertThat(response.nodeExecutions()).hasSize(1);
        assertThat(response.nodeExecutions().get(0).candidateBreakdown()).isEqualTo(candidates);
    }

    @Test
    void awaitingHumanNode_withNoResolvableBreakdown_leavesCandidateBreakdownNull() {
        stubRun();
        stubAwaitingHumanExec();
        List<ResolvedArtifactGroup> requiredArtifacts = List.of();
        when(artifactResolutionService.resolveRequiredArtifacts(templateNodeId, runId))
                .thenReturn(requiredArtifacts);
        when(roadmapCandidatesArtifactResolver.resolve(runId, requiredArtifacts))
                .thenReturn(null);

        RunResponse response = service.getRun(runId);

        assertThat(response.nodeExecutions().get(0).candidateBreakdown()).isNull();
    }

    @Test
    void nonAwaitingHumanNode_neverResolvesCandidateBreakdown() {
        stubRun();
        NodeExecution exec = new NodeExecution();
        exec.setId(nodeExecId);
        exec.setWorkflowRunId(runId);
        exec.setTemplateNodeId(templateNodeId);
        exec.setStatus(NodeExecutionStatus.completed);
        exec.setGraphVersion(1);
        when(execRepo.findByWorkflowRunId(runId)).thenReturn(List.of(exec));

        RunResponse response = service.getRun(runId);

        assertThat(response.nodeExecutions().get(0).candidateBreakdown()).isNull();
        verifyNoInteractions(roadmapCandidatesArtifactResolver);
        verifyNoInteractions(artifactResolutionService);
    }
}
