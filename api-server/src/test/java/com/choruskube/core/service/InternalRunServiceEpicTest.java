package com.choruskube.core.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.choruskube.core.dto.EpicResponse;
import com.choruskube.core.dto.InternalCreateEpicRequest;
import com.choruskube.core.dto.InternalCreateStoryRequest;
import com.choruskube.core.dto.InternalCreateTaskRequest;
import com.choruskube.core.dto.InternalUpdateEpicRequest;
import com.choruskube.core.dto.SoftwareProjectRef;
import com.choruskube.core.dto.StoryResponse;
import com.choruskube.core.dto.TaskResponse;
import com.choruskube.core.exception.NotFoundException;
import com.choruskube.core.model.GraphTemplate;
import com.choruskube.core.model.Story;
import com.choruskube.core.model.WorkflowRun;
import com.choruskube.core.repository.GitRepoRepository;
import com.choruskube.core.repository.GraphTemplateRepository;
import com.choruskube.core.repository.SoftwareProjectRepository;
import com.choruskube.core.repository.StoryRepository;
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
 * Tests for {@link InternalRunService#resolveSoftwareProjectIdFromRun} and the agent-facing
 * Epic/Story/Task endpoints (renamed from {@code InternalRunServiceFeatureProposalTest}).
 * Verifies schema-driven discovery for {@code software_project_id} typed fields and backwards
 * compatibility with legacy {@code git_repo_id} inputs (post-V45, git_repo.id IS
 * software_project.id), plus Story/Task creation resolving through their ancestor Epic.
 */
@ExtendWith(MockitoExtension.class)
class InternalRunServiceEpicTest {

    @Mock
    private WorkflowRunRepository runRepo;

    @Mock
    private GitRepoRepository gitRepoRepo;

    @Mock
    private GraphTemplateRepository graphTemplateRepo;

    @Mock
    private SoftwareProjectRepository softwareProjectRepo;

    @Mock
    private EpicService epicService;

    @Mock
    private StoryService storyService;

    @Mock
    private TaskService taskService;

    @Mock
    private StoryRepository storyRepo;

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
                epicService,
                storyService,
                taskService,
                null,
                Optional.empty(),
                null,
                gitRepoRepo,
                null,
                graphTemplateRepo,
                softwareProjectRepo,
                null,
                null,
                storyRepo);
    }

    @Test
    void resolve_withSoftwareProjectIdField_directInputs_resolves() {
        UUID runId = UUID.randomUUID();
        WorkflowRun run = createRun(
                runId, TEMPLATE_ID, "{\"software_project_id\":\"" + PROJECT_ID + "\",\"feature_request\":\"x\"}");

        when(runRepo.findById(runId)).thenReturn(Optional.of(run));
        when(softwareProjectRepo.existsById(PROJECT_ID)).thenReturn(true);

        var req = new InternalCreateEpicRequest("title", "desc", "motivation");
        when(epicService.create(any(), any())).thenReturn(epicResponseFor(PROJECT_ID));

        assertThatCode(() -> service.createEpic(runId, req)).doesNotThrowAnyException();

        verify(epicService).create(argThat(epic -> PROJECT_ID.equals(epic.softwareProjectId())), eq(runId));
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

        var req = new InternalCreateEpicRequest("title", "desc", "motivation");
        when(epicService.create(any(), any())).thenReturn(epicResponseFor(PROJECT_ID_2));

        assertThatCode(() -> service.createEpic(runId, req)).doesNotThrowAnyException();

        verify(epicService).create(argThat(epic -> PROJECT_ID_2.equals(epic.softwareProjectId())), eq(runId));
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

        var req = new InternalCreateEpicRequest("title", "desc", "motivation");
        when(epicService.create(any(), any())).thenReturn(epicResponseFor(PROJECT_ID));

        assertThatCode(() -> service.createEpic(runId, req)).doesNotThrowAnyException();

        verify(epicService).create(argThat(epic -> PROJECT_ID.equals(epic.softwareProjectId())), eq(runId));
    }

    @Test
    void resolve_withNoResolvableInput_throws() {
        UUID runId = UUID.randomUUID();
        WorkflowRun run = createRun(runId, TEMPLATE_ID, "{\"feature_request\":\"no project here\"}");
        GraphTemplate template = new GraphTemplate();
        template.setInputSchema("[{\"name\":\"feature_request\",\"type\":\"textarea\",\"required\":true}]");

        when(runRepo.findById(runId)).thenReturn(Optional.of(run));
        when(graphTemplateRepo.findById(TEMPLATE_ID)).thenReturn(Optional.of(template));

        var req = new InternalCreateEpicRequest("title", "desc", "motivation");

        assertThatThrownBy(() -> service.createEpic(runId, req))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining("Could not resolve software_project_id");
    }

    @Test
    void listEpics_returnsEpicsByResolvedSoftwareProjectId() {
        UUID runId = UUID.randomUUID();
        WorkflowRun run = createRun(
                runId, TEMPLATE_ID, "{\"software_project_id\":\"" + PROJECT_ID + "\",\"feature_request\":\"x\"}");
        when(runRepo.findById(runId)).thenReturn(Optional.of(run));
        when(softwareProjectRepo.existsById(PROJECT_ID)).thenReturn(true);

        var e1 = epicResponseFor(PROJECT_ID);
        var e2 = epicResponseFor(PROJECT_ID);
        when(epicService.listBySoftwareProjectId(PROJECT_ID)).thenReturn(List.of(e1, e2));

        List<EpicResponse> result = service.listEpics(runId);

        assertThat(result).extracting(EpicResponse::id).containsExactly(e1.id(), e2.id());
        verify(epicService).listBySoftwareProjectId(PROJECT_ID);
    }

    // ── updateEpic: delegation ─────────────────────────────────────────

    @Test
    void updateEpic_delegatesWithResolvedProjectIdAndRunId() {
        UUID runId = UUID.randomUUID();
        UUID epicId = UUID.randomUUID();
        WorkflowRun run = createRun(
                runId, TEMPLATE_ID, "{\"software_project_id\":\"" + PROJECT_ID + "\",\"feature_request\":\"x\"}");

        when(runRepo.findById(runId)).thenReturn(Optional.of(run));
        when(softwareProjectRepo.existsById(PROJECT_ID)).thenReturn(true);

        var req = new InternalUpdateEpicRequest("New Title", null, null);
        var expected = epicResponseFor(PROJECT_ID);
        when(epicService.updateInternal(eq(epicId), eq(PROJECT_ID), eq(runId), eq(req)))
                .thenReturn(expected);

        EpicResponse result = service.updateEpic(runId, epicId, req);

        assertThat(result.id()).isEqualTo(expected.id());
        verify(epicService).updateInternal(eq(epicId), eq(PROJECT_ID), eq(runId), eq(req));
    }

    @Test
    void updateEpic_withUnknownRunId_throwsNotFound() {
        UUID unknownRunId = UUID.randomUUID();
        when(runRepo.findById(unknownRunId)).thenReturn(Optional.empty());

        var req = new InternalUpdateEpicRequest("T", null, null);

        assertThatThrownBy(() -> service.updateEpic(unknownRunId, UUID.randomUUID(), req))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining("Workflow run not found");
    }

    // ── createStory / createTask: resolve through ancestor Epic/Story ────────────

    @Test
    void createStory_delegatesToStoryServiceWithRunIdAndResolvedSoftwareProjectId() {
        UUID runId = UUID.randomUUID();
        UUID epicId = UUID.randomUUID();
        WorkflowRun run = createRun(
                runId, TEMPLATE_ID, "{\"software_project_id\":\"" + PROJECT_ID + "\",\"feature_request\":\"x\"}");
        when(runRepo.findById(runId)).thenReturn(Optional.of(run));
        when(softwareProjectRepo.existsById(PROJECT_ID)).thenReturn(true);

        var req = new InternalCreateStoryRequest("Story title", "Story desc");
        var expected =
                new StoryResponse(UUID.randomUUID(), epicId, "Story title", "Story desc", "backlog", null, null, null);
        when(storyService.create(eq(epicId), any(), eq(runId), eq(PROJECT_ID))).thenReturn(expected);

        StoryResponse result = service.createStory(runId, epicId, req);

        assertThat(result.id()).isEqualTo(expected.id());
        verify(storyService)
                .create(
                        eq(epicId),
                        argThat(story -> story.title().equals("Story title")
                                && story.description().equals("Story desc")),
                        eq(runId),
                        eq(PROJECT_ID));
    }

    @Test
    void createStory_withUnknownRunId_throwsNotFound() {
        UUID unknownRunId = UUID.randomUUID();
        when(runRepo.findById(unknownRunId)).thenReturn(Optional.empty());

        var req = new InternalCreateStoryRequest("T", "D");

        assertThatThrownBy(() -> service.createStory(unknownRunId, UUID.randomUUID(), req))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining("Workflow run not found");
    }

    @Test
    void createTask_delegatesToTaskServiceWithRunIdAndResolvedSoftwareProjectId() {
        UUID runId = UUID.randomUUID();
        UUID epicId = UUID.randomUUID();
        UUID storyId = UUID.randomUUID();
        WorkflowRun run = createRun(
                runId, TEMPLATE_ID, "{\"software_project_id\":\"" + PROJECT_ID + "\",\"feature_request\":\"x\"}");
        when(runRepo.findById(runId)).thenReturn(Optional.of(run));
        when(softwareProjectRepo.existsById(PROJECT_ID)).thenReturn(true);
        when(storyRepo.findById(storyId)).thenReturn(Optional.of(storyWithEpic(storyId, epicId)));

        var req = new InternalCreateTaskRequest("Task title", "Task desc");
        var expected = new TaskResponse(
                UUID.randomUUID(),
                storyId,
                "Task title",
                "Task desc",
                "backlog",
                new SoftwareProjectRef(PROJECT_ID, "git_repo", "name"),
                List.of(),
                null,
                null,
                null,
                null);
        when(taskService.create(eq(storyId), any(), eq(runId), eq(PROJECT_ID))).thenReturn(expected);

        TaskResponse result = service.createTask(runId, epicId, storyId, req);

        assertThat(result.id()).isEqualTo(expected.id());
        verify(taskService)
                .create(
                        eq(storyId),
                        argThat(task -> task.title().equals("Task title")
                                && task.description().equals("Task desc")),
                        eq(runId),
                        eq(PROJECT_ID));
        // The Story lookup must go through the repository, not StoryService#get: get() calls
        // checkOrgAccess, which reads the request-scoped tenant context that doesn't exist on the
        // JOB_SECRET agent path this method is reached from (see the javadoc on createTask).
        verifyNoInteractions(storyService);
    }

    @Test
    void createTask_withUnknownStoryId_throwsNotFound() {
        UUID runId = UUID.randomUUID();
        UUID storyId = UUID.randomUUID();
        WorkflowRun run = createRun(runId, TEMPLATE_ID, "{}");
        when(runRepo.findById(runId)).thenReturn(Optional.of(run));
        when(storyRepo.findById(storyId)).thenReturn(Optional.empty());

        var req = new InternalCreateTaskRequest("T", "D");

        assertThatThrownBy(() -> service.createTask(runId, UUID.randomUUID(), storyId, req))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining("Story not found")
                .hasMessageContaining(storyId.toString());

        verifyNoInteractions(taskService, storyService);
    }

    @Test
    void createTask_withUnknownRunId_throwsNotFound() {
        UUID unknownRunId = UUID.randomUUID();
        when(runRepo.findById(unknownRunId)).thenReturn(Optional.empty());

        var req = new InternalCreateTaskRequest("T", "D");

        assertThatThrownBy(() -> service.createTask(unknownRunId, UUID.randomUUID(), UUID.randomUUID(), req))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining("Workflow run not found");
    }

    @Test
    void createTask_withEpicIdNotMatchingStorysActualEpic_throwsNotFound() {
        UUID runId = UUID.randomUUID();
        UUID storyId = UUID.randomUUID();
        UUID actualEpicId = UUID.randomUUID();
        UUID wrongEpicId = UUID.randomUUID();
        WorkflowRun run = createRun(runId, TEMPLATE_ID, "{}");
        when(runRepo.findById(runId)).thenReturn(Optional.of(run));
        when(storyRepo.findById(storyId)).thenReturn(Optional.of(storyWithEpic(storyId, actualEpicId)));

        var req = new InternalCreateTaskRequest("T", "D");

        assertThatThrownBy(() -> service.createTask(runId, wrongEpicId, storyId, req))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining(storyId.toString())
                .hasMessageContaining(wrongEpicId.toString());

        verify(taskService, never()).create(any(), any(), any(), any());
    }

    private Story storyWithEpic(UUID storyId, UUID epicId) {
        Story story = new Story();
        story.setId(storyId);
        story.setEpicId(epicId);
        story.setTitle("Story title");
        story.setDescription("Story desc");
        return story;
    }

    private EpicResponse epicResponseFor(UUID projectId) {
        return new EpicResponse(
                UUID.randomUUID(),
                "title",
                "desc",
                "motivation",
                "backlog",
                new EpicResponse.Progress(0, 0),
                new SoftwareProjectRef(projectId, "git_repo", "name"),
                List.of(),
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
