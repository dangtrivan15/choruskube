package com.choruskube.core.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.choruskube.core.dto.FeatureProposalResponse;
import com.choruskube.core.dto.InternalCreateFeatureProposalRequest;
import com.choruskube.core.dto.InternalUpdateFeatureProposalRequest;
import com.choruskube.core.dto.SoftwareProjectRef;
import com.choruskube.core.exception.NotFoundException;
import com.choruskube.core.model.GraphTemplate;
import com.choruskube.core.model.WorkflowRun;
import com.choruskube.core.repository.GitRepoRepository;
import com.choruskube.core.repository.GraphTemplateRepository;
import com.choruskube.core.repository.SoftwareProjectRepository;
import com.choruskube.core.repository.WorkflowRunRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Tests for {@link InternalRunService#resolveSoftwareProjectIdFromRun} and the
 * agent-facing feature-proposal endpoints. Verifies schema-driven discovery for
 * {@code software_project_id} typed fields and backwards compatibility with legacy
 * {@code git_repo_id} inputs (post-V45, git_repo.id IS software_project.id).
 */
@ExtendWith(MockitoExtension.class)
class InternalRunServiceFeatureProposalTest {

    @Mock
    private WorkflowRunRepository runRepo;

    @Mock
    private GitRepoRepository gitRepoRepo;

    @Mock
    private GraphTemplateRepository graphTemplateRepo;

    @Mock
    private SoftwareProjectRepository softwareProjectRepo;

    @Mock
    private FeatureProposalService featureProposalService;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private InternalRunService service;

    private static final UUID PROJECT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID PROJECT_ID_2 = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID TEMPLATE_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");

    @BeforeEach
    void setUp() {
        service = new InternalRunService(
                runRepo,
                null,
                null,
                null,
                null,
                objectMapper,
                featureProposalService,
                null,
                Optional.empty(),
                null,
                gitRepoRepo,
                null,
                graphTemplateRepo,
                softwareProjectRepo,
                null,
                null);
    }

    @Test
    void resolve_withSoftwareProjectIdField_directInputs_resolves() {
        UUID runId = UUID.randomUUID();
        WorkflowRun run = createRun(
                runId, TEMPLATE_ID, "{\"software_project_id\":\"" + PROJECT_ID + "\",\"feature_request\":\"x\"}");

        when(runRepo.findById(runId)).thenReturn(Optional.of(run));
        when(softwareProjectRepo.existsById(PROJECT_ID)).thenReturn(true);

        var req = new InternalCreateFeatureProposalRequest("title", "desc", "motivation");
        when(featureProposalService.create(any(), any())).thenReturn(proposalResponseFor(PROJECT_ID));

        assertThatCode(() -> service.createFeatureProposal(runId, req)).doesNotThrowAnyException();

        verify(featureProposalService)
                .create(argThat(proposal -> PROJECT_ID.equals(proposal.softwareProjectId())), eq(runId));
    }

    @Test
    void resolve_withSoftwareProjectIdField_schemaDiscovery_resolves() {
        UUID runId = UUID.randomUUID();
        // Input is named "target_project" rather than the conventional "software_project_id" —
        // schema-driven discovery must still pick it up because the schema declares the field as
        // type "software_project_id".
        WorkflowRun run = createRun(
                runId, TEMPLATE_ID, "{\"target_project\":\"" + PROJECT_ID_2 + "\",\"feature_request\":\"x\"}");
        GraphTemplate template = new GraphTemplate();
        template.setInputSchema("[{\"name\":\"target_project\",\"type\":\"software_project_id\",\"required\":true},"
                + "{\"name\":\"feature_request\",\"type\":\"textarea\",\"required\":true}]");

        when(runRepo.findById(runId)).thenReturn(Optional.of(run));
        when(graphTemplateRepo.findById(TEMPLATE_ID)).thenReturn(Optional.of(template));
        when(softwareProjectRepo.existsById(PROJECT_ID_2)).thenReturn(true);

        var req = new InternalCreateFeatureProposalRequest("title", "desc", "motivation");
        when(featureProposalService.create(any(), any())).thenReturn(proposalResponseFor(PROJECT_ID_2));

        assertThatCode(() -> service.createFeatureProposal(runId, req)).doesNotThrowAnyException();

        verify(featureProposalService)
                .create(argThat(proposal -> PROJECT_ID_2.equals(proposal.softwareProjectId())), eq(runId));
    }

    @Test
    void resolve_withLegacyGitRepoIdField_resolves() {
        // Backwards-compat: legacy templates still emit git_repo_id. Post-V45, git_repo.id IS the
        // software_project.id, so the same UUID resolves cleanly.
        UUID runId = UUID.randomUUID();
        WorkflowRun run =
                createRun(runId, TEMPLATE_ID, "{\"git_repo_id\":\"" + PROJECT_ID + "\",\"feature_request\":\"x\"}");
        // Provide a template with no software_project_id-typed fields so the resolver falls
        // through to the legacy branch.
        GraphTemplate template = new GraphTemplate();
        template.setInputSchema("[{\"name\":\"git_repo_id\",\"type\":\"git_repo\",\"required\":true}]");

        when(runRepo.findById(runId)).thenReturn(Optional.of(run));
        when(graphTemplateRepo.findById(TEMPLATE_ID)).thenReturn(Optional.of(template));
        when(softwareProjectRepo.existsById(PROJECT_ID)).thenReturn(true);

        var req = new InternalCreateFeatureProposalRequest("title", "desc", "motivation");
        when(featureProposalService.create(any(), any())).thenReturn(proposalResponseFor(PROJECT_ID));

        assertThatCode(() -> service.createFeatureProposal(runId, req)).doesNotThrowAnyException();

        verify(featureProposalService)
                .create(argThat(proposal -> PROJECT_ID.equals(proposal.softwareProjectId())), eq(runId));
    }

    @Test
    void resolve_withNoResolvableInput_throws() {
        UUID runId = UUID.randomUUID();
        WorkflowRun run = createRun(runId, TEMPLATE_ID, "{\"feature_request\":\"no project here\"}");
        GraphTemplate template = new GraphTemplate();
        template.setInputSchema("[{\"name\":\"feature_request\",\"type\":\"textarea\",\"required\":true}]");

        when(runRepo.findById(runId)).thenReturn(Optional.of(run));
        when(graphTemplateRepo.findById(TEMPLATE_ID)).thenReturn(Optional.of(template));

        var req = new InternalCreateFeatureProposalRequest("title", "desc", "motivation");

        assertThatThrownBy(() -> service.createFeatureProposal(runId, req))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining("Could not resolve software_project_id");
    }

    @Test
    void listFeatureProposals_returnsProposalsByResolvedSoftwareProjectId() {
        UUID runId = UUID.randomUUID();
        WorkflowRun run = createRun(
                runId, TEMPLATE_ID, "{\"software_project_id\":\"" + PROJECT_ID + "\",\"feature_request\":\"x\"}");
        when(runRepo.findById(runId)).thenReturn(Optional.of(run));
        when(softwareProjectRepo.existsById(PROJECT_ID)).thenReturn(true);

        var p1 = proposalResponseFor(PROJECT_ID);
        var p2 = proposalResponseFor(PROJECT_ID);
        when(featureProposalService.listBySoftwareProjectId(PROJECT_ID)).thenReturn(List.of(p1, p2));

        List<FeatureProposalResponse> result = service.listFeatureProposals(runId);

        assertThat(result).extracting(FeatureProposalResponse::id).containsExactly(p1.id(), p2.id());
        verify(featureProposalService).listBySoftwareProjectId(PROJECT_ID);
    }

    // ── updateFeatureProposal: delegation ─────────────────────────────────────────

    @Test
    void updateFeatureProposal_delegatesWithResolvedProjectIdAndRunId() {
        UUID runId = UUID.randomUUID();
        UUID proposalId = UUID.randomUUID();
        WorkflowRun run = createRun(
                runId, TEMPLATE_ID, "{\"software_project_id\":\"" + PROJECT_ID + "\",\"feature_request\":\"x\"}");

        when(runRepo.findById(runId)).thenReturn(Optional.of(run));
        when(softwareProjectRepo.existsById(PROJECT_ID)).thenReturn(true);

        var req = new InternalUpdateFeatureProposalRequest("New Title", null, null);
        var expected = proposalResponseFor(PROJECT_ID);
        when(featureProposalService.updateInternal(eq(proposalId), eq(PROJECT_ID), eq(runId), eq(req)))
                .thenReturn(expected);

        FeatureProposalResponse result = service.updateFeatureProposal(runId, proposalId, req);

        assertThat(result.id()).isEqualTo(expected.id());
        verify(featureProposalService).updateInternal(eq(proposalId), eq(PROJECT_ID), eq(runId), eq(req));
    }

    @Test
    void updateFeatureProposal_withUnknownRunId_throwsNotFound() {
        UUID unknownRunId = UUID.randomUUID();
        when(runRepo.findById(unknownRunId)).thenReturn(Optional.empty());

        var req = new InternalUpdateFeatureProposalRequest("T", null, null);

        assertThatThrownBy(() -> service.updateFeatureProposal(unknownRunId, UUID.randomUUID(), req))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining("Workflow run not found");
    }

    private FeatureProposalResponse proposalResponseFor(UUID projectId) {
        return new FeatureProposalResponse(
                UUID.randomUUID(),
                "title",
                "desc",
                "motivation",
                "pending",
                new SoftwareProjectRef(projectId, "git_repo", "name"),
                List.of(),
                null,
                null,
                null,
                null);
    }

    private WorkflowRun createRun(UUID runId, UUID templateId, String inputs) {
        WorkflowRun run = new WorkflowRun();
        try {
            var idField = WorkflowRun.class.getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(run, runId);
        } catch (Exception e) {
            throw new RuntimeException("Failed to set 'id' field on WorkflowRun via reflection", e);
        }
        run.setGraphTemplateId(templateId);
        run.setInputs(inputs);
        return run;
    }
}
