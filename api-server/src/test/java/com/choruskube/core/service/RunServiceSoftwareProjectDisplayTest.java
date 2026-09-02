package com.choruskube.core.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.choruskube.core.dto.RunResponse;
import com.choruskube.core.dto.RunSummary;
import com.choruskube.core.dto.SoftwareProjectRef;
import com.choruskube.core.model.GitRepo;
import com.choruskube.core.model.RepoGroup;
import com.choruskube.core.model.Task;
import com.choruskube.core.model.WorkflowRun;
import com.choruskube.core.model.enums.WorkItemStatus;
import com.choruskube.core.model.enums.WorkflowRunStatus;
import com.choruskube.core.repository.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;

/**
 * Unit tests for RunService covering the softwareProject projection in both the listing
 * (listRuns → RunSummary) and detail (getRun → RunResponse) paths, now sourced from the Task
 * a run's forward {@code task_id} FK points at rather than a reverse
 * feature-proposal lookup.
 */
@ExtendWith(MockitoExtension.class)
class RunServiceSoftwareProjectDisplayTest {

    @Mock
    private WorkflowRunRepository runRepo;

    @Mock
    private NodeExecutionRepository execRepo;

    @Mock
    private TemplateEdgeRepository edgeRepo;

    @Mock
    private GraphSnapshotBuilder snapshotBuilder;

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
    private TaskRepository taskRepo;

    @Mock
    private StoryRepository storyRepo;

    @Mock
    private EpicRepository epicRepo;

    @Mock
    private SoftwareProjectRepository softwareProjectRepo;

    @Mock
    private ArtifactResolutionService artifactResolutionService;

    private RunService service;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final UUID orgId = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @BeforeEach
    void setUp() {
        service = new RunService(
                runRepo,
                execRepo,
                edgeRepo,
                snapshotBuilder,
                graphTemplateRepo,
                templateNodeRepo,
                validationService,
                executionLogRepo,
                objectMapper,
                eventPublisher,
                gitRepoRepo,
                null, // workloadService
                new AuthorizationService(new AlwaysAllowAuthorizationStrategy(), false),
                Optional.empty(), // quotaService
                null, // placements
                null, // workflowClients
                null, // usageSink
                null, // auditSink
                null, // storagePrefixResolver — not invoked by getRun
                null, // runPullRequestService — NPE caught gracefully in toResponse()
                softwareProjectRepo,
                null, // repoGroupMemberRepo
                null, // credentialService
                null, // uploadService
                taskRepo,
                storyRepo,
                epicRepo,
                artifactResolutionService,
                null, // applicationEventPublisher — not needed for listing/detail tests
                new com.choruskube.core.scope.NoOpScopeProvider(),
                new DecisionOptionsResolver(),
                null,
                null,
                null, // nodeExecutionClaimService — unused (signalHumanDecision not exercised)
                null); // escalationContextResolver — unused (escalation not exercised)
    }

    // -------------------------------------------------------------------------
    // Helper builders
    // -------------------------------------------------------------------------

    private WorkflowRun makeRun(UUID id, String inputs) {
        WorkflowRun run = new WorkflowRun();
        run.setId(id);
        run.setStatus(WorkflowRunStatus.pending);
        UUID templateId = UUID.randomUUID();
        run.setGraphTemplateId(templateId);
        run.setInputs(inputs);
        return run;
    }

    private GitRepo makeGitRepo(UUID id, String name) {
        GitRepo repo = new GitRepo();
        repo.setId(id);
        repo.setName(name);
        repo.setUrl("https://github.com/example/" + name);
        return repo;
    }

    private RepoGroup makeRepoGroup(UUID id, String name) {
        RepoGroup rg = new RepoGroup();
        rg.setId(id);
        rg.setName(name);
        return rg;
    }

    private Task makeTask(UUID id, UUID projectId) {
        Task t = new Task();
        t.setId(id);
        t.setSoftwareProjectId(projectId);
        t.setStatus(WorkItemStatus.backlog);
        t.setTitle("Some task");
        t.setDescription("desc");
        return t;
    }

    @SuppressWarnings("unchecked")
    private Page<WorkflowRun> stubListRuns(List<WorkflowRun> runs) {
        Page<WorkflowRun> pageResult = new PageImpl<>(runs, PageRequest.of(0, 20), runs.size());
        when(runRepo.findAll(any(Specification.class), any(Pageable.class))).thenReturn(pageResult);
        // Template batch-fetch: return empty list (templateName defaults to "Unknown")
        when(graphTemplateRepo.findAllById(any())).thenReturn(List.of());
        return pageResult;
    }

    // -------------------------------------------------------------------------
    // listRuns() — softwareProject via task (GitRepo)
    // -------------------------------------------------------------------------

    @Test
    void listRuns_taskLinkedGitRepo_softwareProjectIsGitRepo() {
        UUID runId = UUID.randomUUID();
        UUID projectId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        WorkflowRun run = makeRun(runId, "{}");
        run.setTaskId(taskId);
        GitRepo repo = makeGitRepo(projectId, "my-api-repo");
        Task task = makeTask(taskId, projectId);

        stubListRuns(List.of(run));
        when(taskRepo.findAllById(anyCollection())).thenReturn(List.of(task));
        when(softwareProjectRepo.findAllById(anyCollection())).thenReturn(List.of(repo));

        Page<RunSummary> result = service.listRuns(null, null, PageRequest.of(0, 20));

        assertThat(result.getContent()).hasSize(1);
        SoftwareProjectRef ref = result.getContent().get(0).softwareProject();
        assertThat(ref).isNotNull();
        assertThat(ref.id()).isEqualTo(projectId);
        assertThat(ref.type()).isEqualTo("git_repo");
        assertThat(ref.name()).isEqualTo("my-api-repo");
    }

    // -------------------------------------------------------------------------
    // listRuns() — softwareProject via task (RepoGroup)
    // -------------------------------------------------------------------------

    @Test
    void listRuns_taskLinkedRepoGroup_softwareProjectTypeIsRepoGroup() {
        UUID runId = UUID.randomUUID();
        UUID projectId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        WorkflowRun run = makeRun(runId, "{}");
        run.setTaskId(taskId);
        RepoGroup rg = makeRepoGroup(projectId, "my-monorepo");
        Task task = makeTask(taskId, projectId);

        stubListRuns(List.of(run));
        when(taskRepo.findAllById(anyCollection())).thenReturn(List.of(task));
        when(softwareProjectRepo.findAllById(anyCollection())).thenReturn(List.of(rg));

        Page<RunSummary> result = service.listRuns(null, null, PageRequest.of(0, 20));

        SoftwareProjectRef ref = result.getContent().get(0).softwareProject();
        assertThat(ref).isNotNull();
        assertThat(ref.type()).isEqualTo("repo_group");
        assertThat(ref.name()).isEqualTo("my-monorepo");
    }

    // -------------------------------------------------------------------------
    // listRuns() — softwareProject via inputs fallback (no task)
    // -------------------------------------------------------------------------

    @Test
    void listRuns_noTask_validUuidInInputs_softwareProjectFromInputs() {
        UUID runId = UUID.randomUUID();
        UUID projectId = UUID.randomUUID();
        WorkflowRun run = makeRun(runId, "{\"software_project_id\":\"" + projectId + "\"}");
        GitRepo repo = makeGitRepo(projectId, "inputs-repo");

        stubListRuns(List.of(run));
        // No task linked (run.getTaskId() is null) — the batch fetch short-circuits before ever
        // calling taskRepo.findAllById, so no stub is needed for it here.
        when(softwareProjectRepo.findAllById(anyCollection())).thenReturn(List.of(repo));

        Page<RunSummary> result = service.listRuns(null, null, PageRequest.of(0, 20));

        SoftwareProjectRef ref = result.getContent().get(0).softwareProject();
        assertThat(ref).isNotNull();
        assertThat(ref.id()).isEqualTo(projectId);
        assertThat(ref.type()).isEqualTo("git_repo");
        assertThat(ref.name()).isEqualTo("inputs-repo");
    }

    // -------------------------------------------------------------------------
    // listRuns() — null when no task and empty inputs
    // -------------------------------------------------------------------------

    @Test
    void listRuns_noTaskEmptyInputs_softwareProjectIsNull() {
        UUID runId = UUID.randomUUID();
        WorkflowRun run = makeRun(runId, "{}");

        stubListRuns(List.of(run));
        // No task linked — the batch fetch short-circuits before calling taskRepo.findAllById.
        when(softwareProjectRepo.findAllById(anyCollection())).thenReturn(List.of());

        Page<RunSummary> result = service.listRuns(null, null, PageRequest.of(0, 20));

        assertThat(result.getContent().get(0).softwareProject()).isNull();
    }

    // -------------------------------------------------------------------------
    // listRuns() — null (and no exception) when inputs have a non-UUID value
    // -------------------------------------------------------------------------

    @Test
    void listRuns_noTask_nonUuidInInputs_softwareProjectIsNull() {
        UUID runId = UUID.randomUUID();
        WorkflowRun run = makeRun(runId, "{\"software_project_id\":\"not-a-uuid\"}");

        stubListRuns(List.of(run));
        // No task linked — the batch fetch short-circuits before calling taskRepo.findAllById.
        when(softwareProjectRepo.findAllById(anyCollection())).thenReturn(List.of());

        Page<RunSummary> result = service.listRuns(null, null, PageRequest.of(0, 20));

        assertThat(result.getContent().get(0).softwareProject()).isNull();
    }

    // -------------------------------------------------------------------------
    // listRuns() — batch-fetch called exactly once each for a mixed page
    // -------------------------------------------------------------------------

    @Test
    @SuppressWarnings("unchecked")
    void listRuns_mixedPage_batchFetchCalledOnce() {
        UUID run1Id = UUID.randomUUID();
        UUID run2Id = UUID.randomUUID();
        UUID run3Id = UUID.randomUUID();
        UUID project1Id = UUID.randomUUID();
        UUID project2Id = UUID.randomUUID();
        UUID task1Id = UUID.randomUUID();

        WorkflowRun run1 = makeRun(run1Id, "{}"); // has task
        run1.setTaskId(task1Id);
        WorkflowRun run2 = makeRun(run2Id, "{\"software_project_id\":\"" + project2Id + "\"}"); // inputs only
        WorkflowRun run3 = makeRun(run3Id, "{}"); // no project at all

        Task task1 = makeTask(task1Id, project1Id);
        GitRepo repo1 = makeGitRepo(project1Id, "task-repo");
        GitRepo repo2 = makeGitRepo(project2Id, "inputs-repo");

        stubListRuns(List.of(run1, run2, run3));
        when(taskRepo.findAllById(anyCollection())).thenReturn(List.of(task1));
        when(softwareProjectRepo.findAllById(anyCollection())).thenReturn(List.of(repo1, repo2));

        Page<RunSummary> result = service.listRuns(null, null, PageRequest.of(0, 20));

        // Batch fetches called exactly once each
        verify(taskRepo, times(1)).findAllById(anyCollection());
        verify(softwareProjectRepo, times(1)).findAllById(anyCollection());

        // run1 has task project
        RunSummary summary1 = result.getContent().stream()
                .filter(s -> s.id().equals(run1Id))
                .findFirst()
                .orElseThrow();
        assertThat(summary1.softwareProject()).isNotNull();
        assertThat(summary1.softwareProject().name()).isEqualTo("task-repo");

        // run2 has inputs project
        RunSummary summary2 = result.getContent().stream()
                .filter(s -> s.id().equals(run2Id))
                .findFirst()
                .orElseThrow();
        assertThat(summary2.softwareProject()).isNotNull();
        assertThat(summary2.softwareProject().name()).isEqualTo("inputs-repo");

        // run3 has no project
        RunSummary summary3 = result.getContent().stream()
                .filter(s -> s.id().equals(run3Id))
                .findFirst()
                .orElseThrow();
        assertThat(summary3.softwareProject()).isNull();
    }

    // -------------------------------------------------------------------------
    // getRun() / toResponse() — task wins
    // -------------------------------------------------------------------------

    @Test
    void getRun_taskLinkedProject_softwareProjectFromTask() {
        UUID runId = UUID.randomUUID();
        UUID projectId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        WorkflowRun run = makeRun(runId, "{}");
        run.setTaskId(taskId);
        GitRepo repo = makeGitRepo(projectId, "task-linked-repo");
        Task task = makeTask(taskId, projectId);

        when(runRepo.findById(runId)).thenReturn(Optional.of(run));
        when(execRepo.findByWorkflowRunId(runId)).thenReturn(List.of());
        when(graphTemplateRepo.findById(run.getGraphTemplateId())).thenReturn(Optional.empty());
        when(taskRepo.findById(taskId)).thenReturn(Optional.of(task));
        when(softwareProjectRepo.findById(projectId)).thenReturn(Optional.of(repo));

        RunResponse response = service.getRun(runId);

        assertThat(response.softwareProject()).isNotNull();
        assertThat(response.softwareProject().id()).isEqualTo(projectId);
        assertThat(response.softwareProject().type()).isEqualTo("git_repo");
        assertThat(response.softwareProject().name()).isEqualTo("task-linked-repo");
        // task sub-field is still present and consistent
        assertThat(response.task()).isNotNull();
        assertThat(response.task().softwareProject()).isNotNull();
        assertThat(response.task().softwareProject().id()).isEqualTo(projectId);
    }

    // -------------------------------------------------------------------------
    // getRun() / toResponse() — inputs fallback when no task
    // -------------------------------------------------------------------------

    @Test
    void getRun_noTask_validUuidInInputs_softwareProjectFromInputs() {
        UUID runId = UUID.randomUUID();
        UUID projectId = UUID.randomUUID();
        WorkflowRun run = makeRun(runId, "{\"software_project_id\":\"" + projectId + "\"}");
        GitRepo repo = makeGitRepo(projectId, "inputs-fallback-repo");

        when(runRepo.findById(runId)).thenReturn(Optional.of(run));
        when(execRepo.findByWorkflowRunId(runId)).thenReturn(List.of());
        when(graphTemplateRepo.findById(run.getGraphTemplateId())).thenReturn(Optional.empty());
        when(softwareProjectRepo.findById(projectId)).thenReturn(Optional.of(repo));

        RunResponse response = service.getRun(runId);

        assertThat(response.softwareProject()).isNotNull();
        assertThat(response.softwareProject().id()).isEqualTo(projectId);
        assertThat(response.softwareProject().name()).isEqualTo("inputs-fallback-repo");
        assertThat(response.task()).isNull();
    }

    // -------------------------------------------------------------------------
    // getRun() / toResponse() — null when neither task nor inputs
    // -------------------------------------------------------------------------

    @Test
    void getRun_noTaskNoInputs_softwareProjectIsNull() {
        UUID runId = UUID.randomUUID();
        WorkflowRun run = makeRun(runId, "{}");

        when(runRepo.findById(runId)).thenReturn(Optional.of(run));
        when(execRepo.findByWorkflowRunId(runId)).thenReturn(List.of());
        when(graphTemplateRepo.findById(run.getGraphTemplateId())).thenReturn(Optional.empty());

        RunResponse response = service.getRun(runId);

        assertThat(response.softwareProject()).isNull();
        assertThat(response.task()).isNull();
    }
}
