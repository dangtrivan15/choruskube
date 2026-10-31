package com.choruskube.core.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.choruskube.core.BaseTest;
import com.choruskube.core.CommittedFixtureCleaner;
import com.choruskube.core.config.GraphIds;
import com.choruskube.core.credential.GitHubCredentialResolver;
import com.choruskube.core.dto.CandidateDependency;
import com.choruskube.core.dto.CandidateEpicProposal;
import com.choruskube.core.dto.CandidateStoryProposal;
import com.choruskube.core.dto.CandidateTaskProposal;
import com.choruskube.core.dto.RoadmapCandidatesDocument;
import com.choruskube.core.dto.SignalRequest;
import com.choruskube.core.exception.GitHubApiException;
import com.choruskube.core.exception.GitHubRateLimitHints;
import com.choruskube.core.exception.ValidationException;
import com.choruskube.core.model.Epic;
import com.choruskube.core.model.GitRepo;
import com.choruskube.core.model.GraphTemplate;
import com.choruskube.core.model.NodeExecution;
import com.choruskube.core.model.Story;
import com.choruskube.core.model.Task;
import com.choruskube.core.model.TemplateNode;
import com.choruskube.core.model.WorkflowRun;
import com.choruskube.core.model.enums.BlockableItemType;
import com.choruskube.core.model.enums.GithubIssueState;
import com.choruskube.core.model.enums.NodeExecutionStatus;
import com.choruskube.core.repository.EpicRepository;
import com.choruskube.core.repository.GateStampRepository;
import com.choruskube.core.repository.GitRepoRepository;
import com.choruskube.core.repository.GraphTemplateRepository;
import com.choruskube.core.repository.NodeExecutionRepository;
import com.choruskube.core.repository.StoryRepository;
import com.choruskube.core.repository.TaskGithubIssueRepository;
import com.choruskube.core.repository.TaskRepository;
import com.choruskube.core.repository.TemplateNodeRepository;
import com.choruskube.core.repository.WorkItemDependencyRepository;
import com.choruskube.core.repository.WorkflowRunRepository;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowStub;
import io.temporal.serviceclient.WorkflowServiceStubs;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Approve-time materialization on the real, seeded v43 Final Approval gate — the whole chain
 * (signal, then plan/complete through {@link GateFinalizeService}) against a real database, with
 * only Temporal stubbed. {@code complete} refuses to run inside a transaction, so this class
 * commits its fixtures instead of rolling them back, and cleans them up itself via {@link
 * CommittedFixtureCleaner}.
 */
class RoadmapExtensionMaterializationIntegrationTest extends BaseTest {

    @MockitoBean
    private WorkflowServiceStubs workflowServiceStubs;

    @MockitoBean
    private WorkflowClient workflowClient;

    @MockitoBean
    private GitHubAppService gitHubAppService;

    @MockitoBean
    private GitHubCredentialResolver gitHubCredentialResolver;

    @Autowired
    private RunService runService;

    @Autowired
    private GateFinalizeService gateFinalize;

    @Autowired
    private GateStampRepository stampRepo;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private GraphTemplateRepository templateRepo;

    @Autowired
    private TemplateNodeRepository templateNodeRepo;

    @Autowired
    private WorkflowRunRepository runRepo;

    @Autowired
    private NodeExecutionRepository execRepo;

    @Autowired
    private GitRepoRepository gitRepoRepo;

    @Autowired
    private EpicRepository epicRepo;

    @Autowired
    private StoryRepository storyRepo;

    @Autowired
    private TaskRepository taskRepo;

    @Autowired
    private TaskGithubIssueRepository taskGithubIssueRepo;

    @Autowired
    private WorkItemDependencyRepository dependencyRepo;

    private WorkflowStub mockStub;
    private CommittedFixtureCleaner cleaner;
    private GitRepo project;
    private Epic runsEpic;
    private Story runsStory;
    private Task runsTask;
    private TemplateNode finalApproval;

    @BeforeEach
    void setUp() {
        cleaner = new CommittedFixtureCleaner(jdbc);
        mockStub = Mockito.mock(WorkflowStub.class);
        Mockito.when(workflowClient.newUntypedWorkflowStub(ArgumentMatchers.anyString()))
                .thenReturn(mockStub);
        Mockito.lenient()
                .when(gitHubCredentialResolver.getTokenForRepo(ArgumentMatchers.any()))
                .thenReturn("tok");
        Mockito.lenient()
                .when(gitHubAppService.createIssue(
                        ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any()))
                .thenReturn(new GitHubAppService.CreatedIssue(1, "https://github.com/acme/repo/issues/1"));

        project = new GitRepo();
        project.setUrl("https://github.com/acme/extension-materialization-test-" + UUID.randomUUID());
        project.setName("acme/extension-materialization-test");
        project.setSecrets("[]");
        project = gitRepoRepo.save(project);
        cleaner.trackSoftwareProject(project.getId());

        runsEpic = makeEpic(project.getId(), "Run's Epic");
        runsStory = makeStory(runsEpic.getId(), "Run's Story");
        runsTask = makeTask(runsStory.getId(), project.getId(), "Run's Task");

        GraphTemplate v43 = templateRepo
                .findFirstByGraphIdOrderByVersionDesc(GraphIds.FEATURE_DEVELOPMENT)
                .orElseThrow();
        finalApproval = templateNodeRepo.findByGraphTemplateId(v43.getId()).stream()
                .filter(n -> "final_approval".equals(n.getLabel()))
                .findFirst()
                .orElseThrow();
    }

    @AfterEach
    void removeEverythingThisTestCommitted() {
        epicRepo.findBySoftwareProjectIdOrderByCreatedAtDesc(project.getId())
                .forEach(e -> cleaner.trackEpic(e.getId()));
        cleaner.deleteAll();
    }

    private Epic makeEpic(UUID projectId, String title) {
        Epic e = new Epic();
        e.setSoftwareProjectId(projectId);
        e.setTitle(title);
        e.setDescription("d");
        return epicRepo.save(e);
    }

    private Story makeStory(UUID epicId, String title) {
        Story s = new Story();
        s.setEpicId(epicId);
        s.setTitle(title);
        s.setDescription("d");
        return storyRepo.save(s);
    }

    private Task makeTask(UUID storyId, UUID projectId, String title) {
        Task t = new Task();
        t.setStoryId(storyId);
        t.setSoftwareProjectId(projectId);
        t.setTitle(title);
        t.setDescription("d");
        return taskRepo.save(t);
    }

    private WorkflowRun makeTaskTriggeredRun(GraphTemplate template, UUID projectId, UUID taskId) {
        WorkflowRun run = new WorkflowRun();
        run.setGraphTemplateId(template.getId());
        run.setStatus(com.choruskube.core.model.enums.WorkflowRunStatus.running);
        run.setExternalRunId("test-extension-run-" + UUID.randomUUID());
        run.setTaskId(taskId);
        run.setInputs("{\"software_project_id\":\"" + projectId + "\"}");
        return runRepo.save(run);
    }

    private NodeExecution makeAwaitingHumanExec(WorkflowRun run, TemplateNode node) {
        NodeExecution exec = new NodeExecution();
        exec.setWorkflowRunId(run.getId());
        exec.setTemplateNodeId(node.getId());
        exec.setGraphVersion(1);
        exec.setIteration(1);
        exec.setStatus(NodeExecutionStatus.awaiting_human);
        exec.setArtifactRefs("{}");
        // The seeded gate declares merge/recheck/roadmap work, so an approval on it is refused
        // with a 409 unless the workflow marked it async-finalizable.
        exec.setFinalizeMode("async");
        return execRepo.save(exec);
    }

    /**
     * Drives an approved decision all the way through {@link GateFinalizeService}'s one-step
     * protocol (signal, then plan, then complete) — the same path the shipped gate's config
     * (merge + drift-recheck + roadmap materialize, none of which this run's fixtures trigger)
     * resolves to when nothing needs a Worker round trip. Returns the gate's final result note.
     */
    private String approveThroughFinalize(WorkflowRun run, NodeExecution exec, RoadmapCandidatesDocument edited) {
        runService.signalHumanDecision(run.getId(), exec.getId(), new SignalRequest("approved", null, null, edited));
        UUID approvalId = stampRepo
                .findByWorkflowRunIdOrderByCreatedAtAsc(run.getId())
                .get(0)
                .getId();
        gateFinalize.plan(run.getId(), exec.getId(), approvalId);
        return gateFinalize
                .complete(run.getId(), exec.getId(), approvalId, List.of(), List.of())
                .result();
    }

    @Test
    void approve_createsStoriesAndTasksUnderRunsEpic_andEdgeTouchingRunsTask() {
        GraphTemplate v43 = templateRepo
                .findFirstByGraphIdOrderByVersionDesc(GraphIds.FEATURE_DEVELOPMENT)
                .orElseThrow();
        WorkflowRun run = makeTaskTriggeredRun(v43, project.getId(), runsTask.getId());
        NodeExecution exec = makeAwaitingHumanExec(run, finalApproval);

        RoadmapCandidatesDocument edited = new RoadmapCandidatesDocument(
                null,
                List.of(new CandidateEpicProposal(
                        null,
                        null,
                        null,
                        null,
                        null,
                        List.of(new CandidateStoryProposal(
                                null,
                                null,
                                List.of(
                                        new CandidateTaskProposal(
                                                null, null, "existing-task", null, runsTask.getId(), null),
                                        new CandidateTaskProposal("Follow-up", "d", "follow-up", null, null, null)),
                                null,
                                null,
                                runsStory.getId())),
                        null,
                        null,
                        runsEpic.getId())),
                List.of(new CandidateDependency("existing-task", "follow-up")));

        String result = approveThroughFinalize(run, exec, edited);

        List<Story> stories = storyRepo.findAll().stream()
                .filter(s -> s.getEpicId().equals(runsEpic.getId()))
                .toList();
        assertThat(stories).hasSize(1);
        List<Task> tasks = taskRepo.findAll().stream()
                .filter(t -> t.getStoryId().equals(runsStory.getId()))
                .toList();
        assertThat(tasks).hasSize(2); // runsTask + Follow-up
        Task followUp = tasks.stream()
                .filter(t -> "Follow-up".equals(t.getTitle()))
                .findFirst()
                .orElseThrow();
        assertThat(dependencyRepo.findByBlockingItemTypeAndBlockingItemIdAndBlockedItemTypeAndBlockedItemId(
                        BlockableItemType.task, runsTask.getId(), BlockableItemType.task, followUp.getId()))
                .isPresent();
        assertThat(taskGithubIssueRepo.findByTaskId(followUp.getId())).hasValueSatisfying(linkage -> {
            assertThat(linkage.getGitRepoId()).isEqualTo(project.getId());
            assertThat(linkage.getIssueNumber()).isEqualTo(1);
            assertThat(linkage.getState()).isEqualTo(GithubIssueState.open);
        });
        assertThat(result)
                .contains(
                        "Roadmap extension approved: created 0 Epics, 0 Stories, 1 Tasks and 1 dependency edges (0 skipped)");
    }

    @Test
    void approve_issueFilingFails_taskStillCreated_noLinkage_failureInResultNote() {
        Mockito.when(gitHubAppService.createIssue(
                        ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any()))
                .thenThrow(
                        new GitHubApiException(503, "acme/extension-materialization-test", GitHubRateLimitHints.NONE));
        GraphTemplate v43 = templateRepo
                .findFirstByGraphIdOrderByVersionDesc(GraphIds.FEATURE_DEVELOPMENT)
                .orElseThrow();
        WorkflowRun run = makeTaskTriggeredRun(v43, project.getId(), runsTask.getId());
        NodeExecution exec = makeAwaitingHumanExec(run, finalApproval);

        RoadmapCandidatesDocument edited = new RoadmapCandidatesDocument(
                null,
                List.of(new CandidateEpicProposal(
                        null,
                        null,
                        null,
                        null,
                        null,
                        List.of(new CandidateStoryProposal(
                                null,
                                null,
                                List.of(new CandidateTaskProposal("Follow-up", "d", null, null, null, null)),
                                null,
                                null,
                                runsStory.getId())),
                        null,
                        null,
                        runsEpic.getId())),
                null);

        String result = approveThroughFinalize(run, exec, edited);

        Task followUp = taskRepo.findAll().stream()
                .filter(t -> t.getStoryId().equals(runsStory.getId()) && "Follow-up".equals(t.getTitle()))
                .findFirst()
                .orElseThrow();
        assertThat(taskGithubIssueRepo.findByTaskId(followUp.getId())).isEmpty();
        assertThat(result)
                .contains("created 0 Epics, 0 Stories, 1 Tasks and 0 dependency edges (1 skipped)")
                .contains("Skipped: Failed to file GitHub issue for Task 'Follow-up'");
    }

    @Test
    void approve_whollyNewTopLevelEpic_alongsideRunsEpic_withDependencyBetweenThem() {
        GraphTemplate v43 = templateRepo
                .findFirstByGraphIdOrderByVersionDesc(GraphIds.FEATURE_DEVELOPMENT)
                .orElseThrow();
        WorkflowRun run = makeTaskTriggeredRun(v43, project.getId(), runsTask.getId());
        NodeExecution exec = makeAwaitingHumanExec(run, finalApproval);

        CandidateEpicProposal ownEpic = new CandidateEpicProposal(
                null,
                null,
                null,
                null,
                null,
                List.of(new CandidateStoryProposal(
                        null,
                        null,
                        List.of(new CandidateTaskProposal(null, null, "own-task", null, runsTask.getId(), null)),
                        null,
                        null,
                        runsStory.getId())),
                "own",
                null,
                runsEpic.getId());
        CandidateEpicProposal newEpic = new CandidateEpicProposal(
                "New Initiative",
                "d",
                "m",
                null,
                "High",
                List.of(new CandidateStoryProposal(
                        "New Story",
                        "d",
                        List.of(new CandidateTaskProposal("New Task", "d", "new-task", null, null, null)),
                        "new-story",
                        null,
                        null)),
                "new-epic",
                null,
                null);
        RoadmapCandidatesDocument edited = new RoadmapCandidatesDocument(
                null, List.of(ownEpic, newEpic), List.of(new CandidateDependency("own-task", "new-task")));

        approveThroughFinalize(run, exec, edited);

        List<Epic> allEpicsInProject = epicRepo.findAll().stream()
                .filter(e -> e.getSoftwareProjectId().equals(project.getId()))
                .toList();
        assertThat(allEpicsInProject).hasSize(2); // runsEpic + New Initiative
        assertThat(allEpicsInProject).anyMatch(e -> "New Initiative".equals(e.getTitle()));
    }

    @Test
    void approve_childrenUnderAnotherExistingEpic_rejectedWith400_nothingCreated_gateStaysAwaitingHuman() {
        GraphTemplate v43 = templateRepo
                .findFirstByGraphIdOrderByVersionDesc(GraphIds.FEATURE_DEVELOPMENT)
                .orElseThrow();
        WorkflowRun run = makeTaskTriggeredRun(v43, project.getId(), runsTask.getId());
        NodeExecution exec = makeAwaitingHumanExec(run, finalApproval);

        Epic otherEpic = makeEpic(project.getId(), "Other Epic In Same Project");
        long storyCountBefore = storyRepo.count();

        CandidateEpicProposal candidate = new CandidateEpicProposal(
                null,
                null,
                null,
                null,
                null,
                List.of(new CandidateStoryProposal(
                        "New Story",
                        "d",
                        List.of(new CandidateTaskProposal("New Task", "d", "t", null, null, null)),
                        "s",
                        null,
                        null)),
                "e",
                null,
                otherEpic.getId());
        RoadmapCandidatesDocument edited = new RoadmapCandidatesDocument(null, List.of(candidate), null);

        assertThatThrownBy(() -> runService.signalHumanDecision(
                        run.getId(), exec.getId(), new SignalRequest("approved", null, null, edited)))
                .isInstanceOf(ValidationException.class);

        assertThat(storyRepo.count()).isEqualTo(storyCountBefore);
        NodeExecution reloaded = execRepo.findById(exec.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(NodeExecutionStatus.awaiting_human);
    }

    @Test
    void approve_foreignProjectAnchor_rejectedAsNotFound() {
        GraphTemplate v43 = templateRepo
                .findFirstByGraphIdOrderByVersionDesc(GraphIds.FEATURE_DEVELOPMENT)
                .orElseThrow();
        WorkflowRun run = makeTaskTriggeredRun(v43, project.getId(), runsTask.getId());
        NodeExecution exec = makeAwaitingHumanExec(run, finalApproval);

        GitRepo otherProject = new GitRepo();
        otherProject.setUrl("https://github.com/acme/other-project-" + UUID.randomUUID());
        otherProject.setName("acme/other-project");
        otherProject.setSecrets("[]");
        otherProject = gitRepoRepo.save(otherProject);
        cleaner.trackSoftwareProject(otherProject.getId());
        Epic foreignEpic = makeEpic(otherProject.getId(), "Foreign Epic");
        cleaner.trackEpic(foreignEpic.getId());

        CandidateEpicProposal candidate =
                new CandidateEpicProposal(null, null, null, null, null, List.of(), "e", null, foreignEpic.getId());
        RoadmapCandidatesDocument edited = new RoadmapCandidatesDocument(null, List.of(candidate), null);

        assertThatThrownBy(() -> runService.signalHumanDecision(
                        run.getId(), exec.getId(), new SignalRequest("approved", null, null, edited)))
                .isInstanceOf(ValidationException.class)
                .satisfies(thrown -> assertThat(((ValidationException) thrown).getErrors())
                        .anyMatch(e -> e.contains("not found in this run's software project")));
    }

    @Test
    void approve_emptyEditedDocument_createsNothing() {
        GraphTemplate v43 = templateRepo
                .findFirstByGraphIdOrderByVersionDesc(GraphIds.FEATURE_DEVELOPMENT)
                .orElseThrow();
        WorkflowRun run = makeTaskTriggeredRun(v43, project.getId(), runsTask.getId());
        NodeExecution exec = makeAwaitingHumanExec(run, finalApproval);
        long epicCountBefore = epicRepo.count();

        RoadmapCandidatesDocument empty = new RoadmapCandidatesDocument(List.of(), List.of(), List.of());

        String result = approveThroughFinalize(run, exec, empty);

        assertThat(epicRepo.count()).isEqualTo(epicCountBefore);
        assertThat(result).contains("Roadmap proposal: nothing to create");
    }

    @Test
    void approve_edgeNamingUndeclaredKey_taskStillCreated_edgeSkippedAndRecorded() {
        GraphTemplate v43 = templateRepo
                .findFirstByGraphIdOrderByVersionDesc(GraphIds.FEATURE_DEVELOPMENT)
                .orElseThrow();
        WorkflowRun run = makeTaskTriggeredRun(v43, project.getId(), runsTask.getId());
        NodeExecution exec = makeAwaitingHumanExec(run, finalApproval);

        CandidateEpicProposal candidate = new CandidateEpicProposal(
                null,
                null,
                null,
                null,
                null,
                List.of(new CandidateStoryProposal(
                        null,
                        null,
                        List.of(new CandidateTaskProposal("New Task", "d", "t", null, null, null)),
                        null,
                        null,
                        runsStory.getId())),
                null,
                null,
                runsEpic.getId());
        RoadmapCandidatesDocument edited = new RoadmapCandidatesDocument(
                null, List.of(candidate), List.of(new CandidateDependency("t", "no-such-key")));

        String result = approveThroughFinalize(run, exec, edited);

        List<Task> tasks = taskRepo.findAll().stream()
                .filter(t -> t.getStoryId().equals(runsStory.getId()))
                .toList();
        assertThat(tasks).anyMatch(t -> "New Task".equals(t.getTitle()));
        assertThat(result).contains("Skipped:");
    }

    @Test
    void approve_missingArtifactWithNoEdits_approvesSilently() {
        GraphTemplate v43 = templateRepo
                .findFirstByGraphIdOrderByVersionDesc(GraphIds.FEATURE_DEVELOPMENT)
                .orElseThrow();
        WorkflowRun run = makeTaskTriggeredRun(v43, project.getId(), runsTask.getId());
        NodeExecution exec = makeAwaitingHumanExec(run, finalApproval);

        runService.signalHumanDecision(run.getId(), exec.getId(), new SignalRequest("approved", null, null, null));

        Mockito.verify(mockStub).signal(ArgumentMatchers.eq("human-decision-" + exec.getId()), ArgumentMatchers.any());
    }
}
