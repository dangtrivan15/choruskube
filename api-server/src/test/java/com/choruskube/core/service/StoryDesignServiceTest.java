package com.choruskube.core.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.choruskube.core.BaseTest;
import com.choruskube.core.StorySpecFixtures;
import com.choruskube.core.config.GraphIds;
import com.choruskube.core.config.StoryDesignGraph;
import com.choruskube.core.credential.GitHubCredentialResolver;
import com.choruskube.core.dto.EpicRequest;
import com.choruskube.core.dto.EpicResponse;
import com.choruskube.core.dto.RunResponse;
import com.choruskube.core.dto.StoryRequest;
import com.choruskube.core.dto.StoryResponse;
import com.choruskube.core.dto.TaskRequest;
import com.choruskube.core.dto.TaskResponse;
import com.choruskube.core.exception.ConflictException;
import com.choruskube.core.model.GitRepo;
import com.choruskube.core.model.GraphTemplate;
import com.choruskube.core.model.NodeExecution;
import com.choruskube.core.model.StoryConflict;
import com.choruskube.core.model.TemplateNode;
import com.choruskube.core.model.WorkflowRun;
import com.choruskube.core.model.enums.NodeExecutionStatus;
import com.choruskube.core.model.enums.WorkflowRunStatus;
import com.choruskube.core.repository.AutopilotRepository;
import com.choruskube.core.repository.GitRepoRepository;
import com.choruskube.core.repository.GraphTemplateRepository;
import com.choruskube.core.repository.NodeExecutionRepository;
import com.choruskube.core.repository.TemplateNodeRepository;
import com.choruskube.core.repository.WorkflowRunRepository;
import com.choruskube.core.util.RepoNameUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import io.temporal.serviceclient.WorkflowServiceStubs;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.AbortableInputStream;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

@Transactional
class StoryDesignServiceTest extends BaseTest {

    @Autowired
    private StoryDesignService service;

    @Autowired
    private StorySpecService specService;

    @Autowired
    private EpicService epicService;

    @Autowired
    private StoryService storyService;

    @Autowired
    private TaskService taskService;

    @Autowired
    private GitRepoRepository gitRepoRepo;

    @Autowired
    private WorkflowRunRepository runRepo;

    @Autowired
    private GraphTemplateRepository templateRepo;

    @Autowired
    private TemplateNodeRepository templateNodeRepo;

    @Autowired
    private NodeExecutionRepository execRepo;

    @Autowired
    private AutopilotRepository autopilotRepo;

    @Autowired
    private JdbcTemplate jdbc;

    @PersistenceContext
    private EntityManager entityManager;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private WorkflowServiceStubs workflowServiceStubs;

    @MockitoBean
    private WorkflowClient workflowClient;

    @MockitoBean
    private RunEventPublisher runEventPublisher;

    @MockitoBean
    private GitHubAppService gitHubAppService;

    @MockitoBean
    private GitHubCredentialResolver gitHubCredentialResolver;

    @MockitoBean
    private S3Client s3Client;

    @BeforeEach
    void setUp() {
        WorkflowStub mockStub = Mockito.mock(WorkflowStub.class);
        when(workflowClient.newUntypedWorkflowStub(
                        ArgumentMatchers.anyString(), ArgumentMatchers.any(WorkflowOptions.class)))
                .thenReturn(mockStub);
    }

    @Test
    void startDesign_createsRunWithStoryAndInputs() throws Exception {
        GitRepo r = makeRepo("story-design-inputs");
        EpicResponse epic = epicService.create(
                new EpicRequest("Ship the widget", "Epic desc", "Because customers asked", r.getId()), null);
        StoryResponse story = storyService.create(epic.id(), new StoryRequest("Widget backend", "Story desc"));
        TaskResponse first = taskService.create(story.id(), new TaskRequest("Add health check", "First desc"));
        TaskResponse second = taskService.create(story.id(), new TaskRequest("Wire the endpoint", "Second desc"));
        clearInvocations(runEventPublisher);

        RunResponse started = service.startDesign(story.id());

        WorkflowRun run = runRepo.findById(started.id()).orElseThrow();
        assertThat(run.getStoryId()).isEqualTo(story.id());
        assertThat(run.getTaskId()).isNull();
        assertThat(run.getAutopilotId()).isNull();
        assertThat(run.getName()).isEqualTo("Design: Widget backend");
        GraphTemplate latest = templateRepo
                .findFirstByGraphIdOrderByVersionDesc(GraphIds.STORY_DESIGN)
                .orElseThrow();
        assertThat(run.getGraphTemplateId()).isEqualTo(latest.getId());

        JsonNode inputs = objectMapper.readTree(run.getInputs());
        assertThat(inputs.get("software_project_id").asText())
                .isEqualTo(r.getId().toString());
        assertThat(inputs.get("revision_context").asText()).isEmpty();
        String storyRequest = inputs.get("story_request").asText();
        assertThat(storyRequest).startsWith("## Widget backend");
        assertThat(storyRequest)
                .contains("Story desc")
                .contains("/roadmap/epics/" + epic.id() + "/stories/" + story.id())
                .contains("## Parent context")
                .contains("### Epic: Ship the widget", "Epic desc", "Because customers asked")
                .contains("(/roadmap/epics/" + epic.id() + ")")
                .contains("## Existing Tasks")
                .contains("[Add health check](/tasks/" + first.id() + ")", "First desc")
                .contains("[Wire the endpoint](/tasks/" + second.id() + ")", "Second desc")
                .contains("backlog");
        // Tasks are listed in the order they were created, so the drafter reads the Story's plan
        // in the order a human wrote it down.
        assertThat(storyRequest.indexOf("Add health check")).isLessThan(storyRequest.indexOf("Wire the endpoint"));
        assertThat(run.getInputArtifactRefs()).isEqualTo("{}");
        verify(runEventPublisher).publishRoadmapItemChanged("story", story.id(), "backlog");
    }

    @Test
    void startDesign_storyWithNoTasks_saysSo() throws Exception {
        StoryResponse story = makeStory("story-design-no-tasks", "Empty story");

        RunResponse started = service.startDesign(story.id());

        String storyRequest = inputOf(started.id(), "story_request");
        assertThat(storyRequest).contains("## Existing Tasks\n\nNone yet.");
        assertThat(storyRequest).doesNotContain("null");
    }

    @Test
    void startDesign_specExists_conflicts() {
        StoryResponse story = makeStory("story-design-spec-exists", "Designed story");
        approveSpec(story.id());

        assertThatThrownBy(() -> service.startDesign(story.id()))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("already has an approved spec");
        assertThat(designRunsOf(story.id())).isEmpty();
    }

    @Test
    void startDesign_openDesignRun_conflicts() {
        StoryResponse story = makeStory("story-design-open-run", "Story");
        RunResponse first = service.startDesign(story.id());
        setStatus(first.id(), WorkflowRunStatus.awaiting_human);

        assertThatThrownBy(() -> service.startDesign(story.id()))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining(first.id().toString());
    }

    @Test
    void startDesign_completedButUnrecordedDesignRun_conflicts() {
        StoryResponse story = makeStory("story-design-unrecorded", "Story");
        RunResponse approved = service.startDesign(story.id());
        setStatus(approved.id(), WorkflowRunStatus.completed);

        assertThatThrownBy(() -> service.startDesign(story.id()))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining(approved.id().toString());
        assertThat(designRunsOf(story.id())).hasSize(1);
    }

    @Test
    void startRevision_completedButUnrecordedRevision_conflicts() {
        StoryResponse story = makeStory("story-revision-unrecorded", "Story");
        approveSpec(story.id());
        RunResponse approved = service.startRevision(story.id(), "First");
        setStatus(approved.id(), WorkflowRunStatus.completed);

        assertThatThrownBy(() -> service.startRevision(story.id(), "Second"))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining(approved.id().toString());
        assertThat(designRunsOf(story.id())).hasSize(1);
    }

    @Test
    void startDesign_afterFailedDesignRun_startsAgain() {
        StoryResponse story = makeStory("story-design-after-failure", "Story");
        RunResponse first = service.startDesign(story.id());
        setStatus(first.id(), WorkflowRunStatus.failed);

        RunResponse second = service.startDesign(story.id());

        assertThat(second.id()).isNotEqualTo(first.id());
        assertThat(designRunsOf(story.id())).hasSize(2);
    }

    @Test
    void startDesign_lockedStoryRow_secondStartConflicts() {
        StoryResponse story = makeStory("story-design-locked", "Story");

        List<Throwable> failures = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            try {
                service.startDesign(story.id());
            } catch (ConflictException e) {
                failures.add(e);
            }
        }

        assertThat(failures).hasSize(1);
        assertThat(designRunsOf(story.id())).hasSize(1);
    }

    @Test
    void startDesignForAutopilot_stampsAutopilotId() {
        StoryResponse story = makeStory("story-design-autopilot", "Story");
        UUID autopilotId = makeAutopilot();

        RunResponse started = service.startDesignForAutopilot(story.id(), autopilotId);

        WorkflowRun run = runRepo.findById(started.id()).orElseThrow();
        assertThat(run.getStoryId()).isEqualTo(story.id());
        assertThat(run.getAutopilotId()).isEqualTo(autopilotId);
        assertThat(started.autopilotId()).isEqualTo(autopilotId);
    }

    @Test
    void startRevision_stagesCurrentSpecAndPriorReviews() throws Exception {
        StoryResponse story = makeStory("story-revision-staging", "Widget backend");
        WorkflowRun designRun = completedDesignRun(story.id());
        String reviewPrefix = "system/runs/" + designRun.getId() + "/outputs/review/";
        reviewExecution(designRun, reviewPrefix);
        specService.recordApproved(story.id(), designRun.getId(), "# Spec v1 body");
        when(s3Client.getObject(any(GetObjectRequest.class))).thenReturn(objectBody("Reasoning for fixes v1"));

        RunResponse started = service.startRevision(story.id(), "Use a hard cutoff, no fallback");

        WorkflowRun run = runRepo.findById(started.id()).orElseThrow();
        assertThat(run.getStoryId()).isEqualTo(story.id());
        assertThat(run.getName()).isEqualTo("Revise spec v1: Widget backend");
        JsonNode refs = objectMapper.readTree(run.getInputArtifactRefs());
        List<String> keys = new ArrayList<>();
        refs.fieldNames().forEachRemaining(keys::add);
        assertThat(keys).containsExactlyInAnyOrder("story_spec.md", "prior_review_v1.md");
        assertThat(refs.get("story_spec.md").asText())
                .isEqualTo("system/runs/" + run.getId() + "/inputs/story_spec.md");

        ArgumentCaptor<GetObjectRequest> read = ArgumentCaptor.forClass(GetObjectRequest.class);
        verify(s3Client).getObject(read.capture());
        assertThat(read.getValue().key()).isEqualTo(reviewPrefix + "story_spec_review.md");
        Map<String, String> staged = stagedTexts();
        assertThat(staged).containsEntry("story_spec.md", "# Spec v1 body");
        assertThat(staged).containsEntry("prior_review_v1.md", "Reasoning for fixes v1");

        String revisionContext = inputOf(started.id(), "revision_context");
        assertThat(revisionContext).startsWith("## Current version: v1");
        assertThat(revisionContext).contains("## Conflicts to answer");
        assertThat(revisionContext).contains("## Human guidance\n\nUse a hard cutoff, no fallback");
        assertThat(inputOf(started.id(), "story_request")).startsWith("## Widget backend");
    }

    @Test
    void startRevision_versionWithoutDesignRun_stagesOnlyTheSpec() throws Exception {
        StoryResponse story = makeStory("story-revision-fixture", "Story");
        approveSpec(story.id());

        RunResponse started = service.startRevision(story.id(), "Tighten the API");

        JsonNode refs = objectMapper.readTree(
                runRepo.findById(started.id()).orElseThrow().getInputArtifactRefs());
        List<String> keys = new ArrayList<>();
        refs.fieldNames().forEachRemaining(keys::add);
        assertThat(keys).containsExactly("story_spec.md");
    }

    @Test
    void startRevision_noSpec_conflicts() {
        StoryResponse story = makeStory("story-revision-no-spec", "Story");

        assertThatThrownBy(() -> service.startRevision(story.id(), "Guidance"))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("no approved spec");
        assertThat(designRunsOf(story.id())).isEmpty();
    }

    @Test
    void startRevision_whileRevisionOpen_conflicts() {
        StoryResponse story = makeStory("story-revision-open", "Story");
        approveSpec(story.id());
        RunResponse open = service.startRevision(story.id(), "First");

        assertThatThrownBy(() -> service.startRevision(story.id(), "Second"))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining(open.id().toString());
    }

    @Test
    void startRevisionForConflicts_answersEachConflictAndInheritsAutopilot() throws Exception {
        StoryResponse story = makeStory("story-revision-conflicts", "Story");
        TaskResponse task = taskService.create(story.id(), new TaskRequest("Add health check", "D"));
        approveSpec(story.id());
        approveSpec(story.id());
        WorkflowRun taskRun = taskRun(task.id());
        StoryConflict conflict = conflict(story.id(), taskRun.getId(), "The spec's cache cannot work: X.");
        UUID autopilotId = makeAutopilot();

        RunResponse started = service.startRevisionForConflicts(story.id(), List.of(conflict), autopilotId);

        WorkflowRun run = runRepo.findById(started.id()).orElseThrow();
        assertThat(run.getAutopilotId()).isEqualTo(autopilotId);
        assertThat(run.getName()).startsWith("Revise spec v2: ");
        String revisionContext = inputOf(started.id(), "revision_context");
        assertThat(revisionContext).startsWith("## Current version: v2");
        assertThat(revisionContext)
                .contains("### Conflict " + conflict.getId() + " from Task \"Add health check\" (run " + taskRun.getId()
                        + ")")
                .contains("/tasks/" + task.id())
                .contains("The spec's cache cannot work: X.")
                .contains("## Human guidance\n\nNone.");
    }

    // ---- helpers ----

    private void approveSpec(UUID storyId) {
        entityManager.flush();
        StorySpecFixtures.approve(jdbc, storyId);
    }

    private Map<String, String> stagedTexts() throws Exception {
        ArgumentCaptor<PutObjectRequest> requests = ArgumentCaptor.forClass(PutObjectRequest.class);
        ArgumentCaptor<RequestBody> bodies = ArgumentCaptor.forClass(RequestBody.class);
        verify(s3Client, atLeastOnce()).putObject(requests.capture(), bodies.capture());
        Map<String, String> byName = new HashMap<>();
        for (int i = 0; i < requests.getAllValues().size(); i++) {
            String key = requests.getAllValues().get(i).key();
            String name = key.substring(key.lastIndexOf('/') + 1);
            byName.put(
                    name,
                    new String(
                            bodies.getAllValues()
                                    .get(i)
                                    .contentStreamProvider()
                                    .newStream()
                                    .readAllBytes(),
                            StandardCharsets.UTF_8));
        }
        return byName;
    }

    private static ResponseInputStream<GetObjectResponse> objectBody(String text) {
        return new ResponseInputStream<>(
                GetObjectResponse.builder().build(),
                AbortableInputStream.create(new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8))));
    }

    private WorkflowRun completedDesignRun(UUID storyId) {
        RunResponse started = service.startDesign(storyId);
        return setStatus(started.id(), WorkflowRunStatus.completed);
    }

    private void reviewExecution(WorkflowRun run, String outputPrefix) {
        TemplateNode reviewNode = templateNodeRepo.findByGraphTemplateId(run.getGraphTemplateId()).stream()
                .filter(n -> StoryDesignGraph.REVIEW.equals(n.getLabel()))
                .findFirst()
                .orElseThrow();
        NodeExecution exec = new NodeExecution();
        exec.setWorkflowRunId(run.getId());
        exec.setTemplateNodeId(reviewNode.getId());
        exec.setLabel(StoryDesignGraph.REVIEW);
        exec.setStatus(NodeExecutionStatus.completed);
        exec.setIteration(2);
        exec.setCompletedAt(Instant.now());
        exec.setArtifactRefs("{\"output\":\"" + outputPrefix + "\"}");
        execRepo.saveAndFlush(exec);
    }

    private WorkflowRun taskRun(UUID taskId) {
        WorkflowRun run = new WorkflowRun();
        run.setGraphTemplateId(templateRepo
                .findFirstByGraphIdOrderByVersionDesc(GraphIds.FEATURE_DEVELOPMENT)
                .orElseThrow()
                .getId());
        run.setStatus(WorkflowRunStatus.awaiting_human);
        run.setTaskId(taskId);
        return runRepo.saveAndFlush(run);
    }

    private static StoryConflict conflict(UUID storyId, UUID taskRunId, String report) {
        StoryConflict c = new StoryConflict();
        c.setId(UUID.randomUUID());
        c.setStoryId(storyId);
        c.setTaskRunId(taskRunId);
        c.setRaisedAgainstVersion(2);
        c.setReport(report);
        return c;
    }

    private WorkflowRun setStatus(UUID runId, WorkflowRunStatus status) {
        WorkflowRun run = runRepo.findById(runId).orElseThrow();
        run.setStatus(status);
        return runRepo.saveAndFlush(run);
    }

    private List<WorkflowRun> designRunsOf(UUID storyId) {
        return runRepo.findByStoryIdInAndStatusInAndDeletedAtIsNull(
                List.of(storyId), List.of(WorkflowRunStatus.values()));
    }

    private String inputOf(UUID runId, String key) throws Exception {
        return objectMapper
                .readTree(runRepo.findById(runId).orElseThrow().getInputs())
                .get(key)
                .asText();
    }

    private StoryResponse makeStory(String slug, String title) {
        GitRepo r = makeRepo(slug);
        EpicResponse epic = epicService.create(new EpicRequest("Epic", "Epic desc", null, r.getId()), null);
        return storyService.create(epic.id(), new StoryRequest(title, "Story desc"));
    }

    private GitRepo makeRepo(String slug) {
        String url = "https://github.com/test/" + slug + ".git";
        GitRepo r = new GitRepo();
        r.setUrl(url);
        r.setName(RepoNameUtil.deriveOwnerRepoName(url));
        return gitRepoRepo.save(r);
    }

    private UUID makeAutopilot() {
        UUID id = UUID.randomUUID();
        autopilotRepo.insertDefaults(id);
        autopilotRepo.engage(id, Instant.now());
        return id;
    }
}
