package com.choruskube.core.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.choruskube.core.dto.InternalCreateMilestoneRequest;
import com.choruskube.core.dto.MilestoneResponse;
import com.choruskube.core.model.WorkflowRun;
import com.choruskube.core.repository.EpicRepository;
import com.choruskube.core.repository.GitRepoRepository;
import com.choruskube.core.repository.GraphTemplateRepository;
import com.choruskube.core.repository.SoftwareProjectRepository;
import com.choruskube.core.repository.StoryRepository;
import com.choruskube.core.repository.TaskRepository;
import com.choruskube.core.repository.WorkflowRunRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * {@link InternalRunService#createMilestone}: the {@code JOB_SECRET}-path (agent-facing {@code
 * create-milestone} CLI) counterpart of {@link DefaultRoadmapCandidateMaterializer}'s Milestone
 * find-or-create retry. Covers the same {@code MilestoneService#findOrCreateInternal} race —
 * concurrent node executions of one run, or two runs in the same project, racing a same-named
 * Milestone — but through this method's own (deliberately non-{@code @Transactional}) call site.
 */
@ExtendWith(MockitoExtension.class)
class InternalRunServiceMilestoneTest {

    @Mock
    private WorkflowRunRepository runRepo;

    @Mock
    private GitRepoRepository gitRepoRepo;

    @Mock
    private GraphTemplateRepository graphTemplateRepo;

    @Mock
    private SoftwareProjectRepository softwareProjectRepo;

    @Mock
    private StoryRepository storyRepo;

    @Mock
    private TaskRepository taskRepo;

    @Mock
    private EpicRepository epicRepo;

    @Mock
    private MilestoneService milestoneService;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private InternalRunService service;

    private static final UUID PROJECT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
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
                null,
                null,
                null,
                null,
                Optional.empty(),
                null,
                gitRepoRepo,
                null,
                graphTemplateRepo,
                softwareProjectRepo,
                null,
                null,
                storyRepo,
                taskRepo,
                epicRepo,
                null,
                new DecisionOptionsResolver(),
                null,
                null,
                null,
                milestoneService);
    }

    @Test
    void createMilestone_raceLoss_retriesOnceThroughTheProxyAndReturnsWinner() {
        UUID runId = UUID.randomUUID();
        WorkflowRun run = createRun(runId, TEMPLATE_ID, "{\"software_project_id\":\"" + PROJECT_ID + "\"}");
        when(runRepo.findById(runId)).thenReturn(Optional.of(run));
        when(softwareProjectRepo.existsById(PROJECT_ID)).thenReturn(true);

        MilestoneResponse winner = milestoneResponse(UUID.randomUUID(), "Beta");
        // First call loses the find-then-save race to a concurrent creator; the retry (a fresh call
        // through the milestoneService proxy) finds the winner's now-committed row.
        when(milestoneService.findOrCreateInternal(eq(PROJECT_ID), eq("Beta"), any(), any(), eq(runId)))
                .thenThrow(new DataIntegrityViolationException("duplicate key"))
                .thenReturn(winner);

        var req = new InternalCreateMilestoneRequest("Beta", "desc", null);
        MilestoneResponse result = service.createMilestone(runId, req);

        assertThat(result).isEqualTo(winner);
        verify(milestoneService, times(2)).findOrCreateInternal(eq(PROJECT_ID), eq("Beta"), any(), any(), eq(runId));
    }

    @Test
    void createMilestone_raceLossTwice_propagatesTheSecondFailure() {
        UUID runId = UUID.randomUUID();
        WorkflowRun run = createRun(runId, TEMPLATE_ID, "{\"software_project_id\":\"" + PROJECT_ID + "\"}");
        when(runRepo.findById(runId)).thenReturn(Optional.of(run));
        when(softwareProjectRepo.existsById(PROJECT_ID)).thenReturn(true);

        when(milestoneService.findOrCreateInternal(eq(PROJECT_ID), eq("Beta"), any(), any(), eq(runId)))
                .thenThrow(new DataIntegrityViolationException("duplicate key 1"))
                .thenThrow(new DataIntegrityViolationException("duplicate key 2"));

        var req = new InternalCreateMilestoneRequest("Beta", "desc", null);

        assertThatThrownBy(() -> service.createMilestone(runId, req))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("duplicate key 2");
        verify(milestoneService, times(2)).findOrCreateInternal(eq(PROJECT_ID), eq("Beta"), any(), any(), eq(runId));
    }

    private static MilestoneResponse milestoneResponse(UUID id, String name) {
        return new MilestoneResponse(
                id,
                name,
                "d",
                PROJECT_ID,
                null,
                0,
                new MilestoneResponse.Progress(0, 0, 0, 0),
                false,
                0,
                Instant.now(),
                Instant.now());
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
