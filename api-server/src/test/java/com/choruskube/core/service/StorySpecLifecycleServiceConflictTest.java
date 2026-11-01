package com.choruskube.core.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.choruskube.core.BaseTest;
import com.choruskube.core.config.GraphIds;
import com.choruskube.core.config.TaskDeliveryGraph;
import com.choruskube.core.credential.GitHubCredentialResolver;
import com.choruskube.core.dto.EpicRequest;
import com.choruskube.core.dto.EpicResponse;
import com.choruskube.core.dto.SignalRequest;
import com.choruskube.core.dto.SignalResponse;
import com.choruskube.core.dto.StoryRequest;
import com.choruskube.core.dto.StoryResponse;
import com.choruskube.core.dto.TaskRequest;
import com.choruskube.core.dto.TaskResponse;
import com.choruskube.core.exception.ConflictException;
import com.choruskube.core.model.GitRepo;
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
import com.choruskube.core.repository.StoryConflictRepository;
import com.choruskube.core.repository.TemplateNodeRepository;
import com.choruskube.core.repository.WorkflowRunRepository;
import com.choruskube.core.util.RepoNameUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import io.temporal.serviceclient.WorkflowServiceStubs;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.annotation.Transactional;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;

/**
 * The conflict state machine: wait while a revision is open, release a conflict once its Story's
 * spec has moved past the version it was raised against, and otherwise start one revision covering
 * every conflict no revision covers yet.
 */
@Transactional
class StorySpecLifecycleServiceConflictTest extends BaseTest {

    @Autowired
    private StorySpecLifecycleService lifecycle;

    @Autowired
    private StorySpecService specService;

    @Autowired
    private StoryConflictRepository conflictRepo;

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

    @Autowired
    private ObjectMapper objectMapper;

    @PersistenceContext
    private EntityManager entityManager;

    @MockitoSpyBean
    private RunService runService;

    @MockitoSpyBean
    private StoryDesignService storyDesignService;

    @MockitoSpyBean
    private UploadService uploadService;

    @MockitoSpyBean
    private RunScopeBinder runScopeBinder;

    @MockitoSpyBean
    private StoryScopeBinder storyScopeBinder;

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
        when(workflowClient.newUntypedWorkflowStub(anyString(), any(WorkflowOptions.class)))
                .thenReturn(Mockito.mock(WorkflowStub.class));
        when(s3Client.getObject(any(GetObjectRequest.class)))
                .thenThrow(NoSuchKeyException.builder().message("missing").build());
        doReturn(SignalResponse.signaled()).when(runService).decideGateAsSystem(any(), any(), any());
    }

    @Test
    void openRevision_waits() {
        UUID storyId = makeStory("conflict-open-revision");
        approveSpec(storyId, "# Spec v1");
        Parked releasable = parkedTaskRun(storyId, 1);
        approveSpec(storyId, "# Spec v2");
        Parked unassigned = parkedTaskRun(storyId, 2);
        WorkflowRun revision = designRun(storyId, WorkflowRunStatus.running);

        lifecycle.processConflicts();

        verify(storyDesignService, never()).startRevisionForConflicts(eq(storyId), any(), any());
        verify(runService, never()).decideGateAsSystem(eq(releasable.run().getId()), any(), any());
        assertThat(conflict(releasable)).satisfies(c -> {
            assertThat(c.getReleasedAt()).isNull();
            assertThat(c.getRevisionRunId()).isNull();
        });
        assertThat(conflict(unassigned).getRevisionRunId()).isNull();
        assertThat(specService.openDesignRun(storyId)).map(WorkflowRun::getId).contains(revision.getId());
    }

    @Test
    void newerVersion_releases() throws Exception {
        UUID storyId = makeStory("conflict-release");
        approveSpec(storyId, "# Spec v1");
        Parked parked = parkedTaskRun(storyId, 1);
        approveSpec(storyId, "# Spec v2");
        UUID runId = parked.run().getId();

        lifecycle.processConflicts();

        verify(uploadService).overwriteRunInput(runId, "story_spec.md", "# Spec v2");
        assertThat(reloadRun(runId).getStorySpecVersion()).isEqualTo(2);
        ArgumentCaptor<String> guidance = ArgumentCaptor.forClass(String.class);
        verify(uploadService)
                .uploadGateText(
                        anyString(), eq(runId), eq(parked.gate().getId()), eq("human_guidance.md"), guidance.capture());
        assertThat(guidance.getValue()).contains("v1", "v2", "Revisions");
        ArgumentCaptor<SignalRequest> signal = ArgumentCaptor.forClass(SignalRequest.class);
        verify(runService).decideGateAsSystem(eq(runId), eq(parked.gate().getId()), signal.capture());
        assertThat(signal.getValue().decision()).isEqualTo(TaskDeliveryGraph.RESUME);
        assertThat(objectMapper.readTree(signal.getValue().attachmentRefs()).has("human_guidance.md"))
                .isTrue();
        verify(runScopeBinder).runInScopeOf(eq(runId), any());
        assertThat(conflict(parked).getReleasedAt()).isNotNull();
    }

    @Test
    void newerVersion_guidanceNamesEveryRevisionSinceTheRaisedVersion() {
        UUID storyId = makeStory("conflict-release-range");
        approveSpec(storyId, "# Spec v1");
        Parked parked = parkedTaskRun(storyId, 1);
        approveSpec(storyId, "# Spec v2");
        approveSpec(storyId, "# Spec v3");

        lifecycle.processConflicts();

        ArgumentCaptor<String> guidance = ArgumentCaptor.forClass(String.class);
        verify(uploadService)
                .uploadGateText(
                        anyString(), eq(parked.run().getId()), any(), eq("human_guidance.md"), guidance.capture());
        assertThat(guidance.getValue())
                .isEqualTo("The Story spec moved from v1 to v3 while this Task waited. Read the Revisions entries for"
                        + " v2 through v3 in /workspace/in/run_input/story_spec.md and check what they say about the"
                        + " conflict you raised.");
    }

    @Test
    void equalVersion_unassigned_startsOneRevisionForAll() {
        UUID storyId = makeStory("conflict-start-one");
        approveSpec(storyId, "# Spec v1");
        Parked first = parkedTaskRun(storyId, 1);
        Parked second = parkedTaskRun(storyId, 1);

        lifecycle.processConflicts();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<StoryConflict>> covered = ArgumentCaptor.forClass(List.class);
        verify(storyDesignService, times(1)).startRevisionForConflicts(eq(storyId), covered.capture(), any());
        assertThat(covered.getValue())
                .extracting(StoryConflict::getId)
                .containsExactlyInAnyOrder(
                        first.conflict().getId(), second.conflict().getId());
        verify(storyScopeBinder).runInScopeOf(eq(storyId), any());
        UUID revisionId = specService.openDesignRun(storyId).orElseThrow().getId();
        assertThat(conflict(first).getRevisionRunId()).isEqualTo(revisionId);
        assertThat(conflict(second).getRevisionRunId()).isEqualTo(revisionId);
        verify(runService, never()).decideGateAsSystem(any(), any(), any());
    }

    @Test
    void conflictRaisedMidRevision_notJoined() {
        UUID storyId = makeStory("conflict-mid-revision");
        approveSpec(storyId, "# Spec v1");
        Parked early = parkedTaskRun(storyId, 1);
        lifecycle.processConflicts();
        UUID revisionId = specService.openDesignRun(storyId).orElseThrow().getId();

        Parked late = parkedTaskRun(storyId, 1);
        lifecycle.processConflicts();

        assertThat(conflict(late).getRevisionRunId()).isNull();
        verify(storyDesignService, times(1)).startRevisionForConflicts(eq(storyId), any(), any());

        completeRevision(storyId, revisionId, "# Spec v2");
        lifecycle.processConflicts();

        assertThat(conflict(late).getReleasedAt()).isNotNull();
        assertThat(conflict(early).getReleasedAt()).isNotNull();
        verify(uploadService).overwriteRunInput(late.run().getId(), "story_spec.md", "# Spec v2");
        verify(runService)
                .decideGateAsSystem(eq(late.run().getId()), eq(late.gate().getId()), any());
        verify(storyDesignService, times(1)).startRevisionForConflicts(eq(storyId), any(), any());
    }

    @Test
    void awaitGateNotOpen_skipped() {
        UUID storyId = makeStory("conflict-gate-not-open");
        approveSpec(storyId, "# Spec v1");
        Parked parked = taskRunWithConflict(storyId, 1, null);
        approveSpec(storyId, "# Spec v2");

        lifecycle.processConflicts();

        verify(uploadService, never()).overwriteRunInput(eq(parked.run().getId()), any(), any());
        verify(runService, never()).decideGateAsSystem(eq(parked.run().getId()), any(), any());
        assertThat(reloadRun(parked.run().getId()).getStorySpecVersion()).isEqualTo(1);
        assertThat(conflict(parked).getReleasedAt()).isNull();
    }

    @Test
    void revisionInheritsAutopilotId() {
        UUID storyId = makeStory("conflict-autopilot");
        approveSpec(storyId, "# Spec v1");
        UUID autopilotId = makeAutopilot();
        Parked manual = parkedTaskRun(storyId, 1);
        Parked autopiloted = parkedTaskRun(storyId, 1);
        WorkflowRun run = reloadRun(autopiloted.run().getId());
        run.setAutopilotId(autopilotId);
        runRepo.saveAndFlush(run);

        lifecycle.processConflicts();

        verify(storyDesignService).startRevisionForConflicts(eq(storyId), any(), eq(autopilotId));
        assertThat(specService.openDesignRun(storyId))
                .map(WorkflowRun::getAutopilotId)
                .contains(autopilotId);
        assertThat(conflict(manual).getRevisionRunId()).isNotNull();
    }

    /** Review Focus: a revision that ends without a version must not be restarted on every pass. */
    @ParameterizedTest
    @EnumSource(
            value = WorkflowRunStatus.class,
            names = {"failed", "cancelled"})
    void processConflicts_failedRevision_doesNotRestartForAssignedConflicts(WorkflowRunStatus ended) {
        UUID storyId = makeStory("conflict-failed-revision-" + ended);
        approveSpec(storyId, "# Spec v1");
        Parked parked = parkedTaskRun(storyId, 1);
        lifecycle.processConflicts();
        UUID revisionId = specService.openDesignRun(storyId).orElseThrow().getId();
        setStatus(revisionId, ended);

        lifecycle.processConflicts();
        lifecycle.processConflicts();

        verify(storyDesignService, times(1)).startRevisionForConflicts(eq(storyId), any(), any());
        assertThat(conflict(parked)).satisfies(c -> {
            assertThat(c.getRevisionRunId()).isEqualTo(revisionId);
            assertThat(c.getReleasedAt()).isNull();
        });
        verify(runService, never()).decideGateAsSystem(any(), any(), any());
    }

    /** Review Focus: deleting a Story cascades its conflicts, before a pass or in the middle of one. */
    @Test
    void processConflicts_storyDeleted_skipsWithoutError() {
        UUID deletedBefore = makeStory("conflict-deleted-before");
        approveSpec(deletedBefore, "# Spec v1");
        Parked before = parkedTaskRun(deletedBefore, 1);
        UUID deletedDuring = makeStory("conflict-deleted-during");
        approveSpec(deletedDuring, "# Spec v1");
        Parked during = parkedTaskRun(deletedDuring, 1);
        deleteStory(deletedBefore, before);
        doAnswer(inv -> {
                    deleteStory(deletedDuring, during);
                    return inv.callRealMethod();
                })
                .when(storyDesignService)
                .startRevisionForConflicts(eq(deletedDuring), any(), any());

        assertThatCode(lifecycle::processConflicts).doesNotThrowAnyException();

        assertThat(conflictCount(deletedBefore)).isZero();
        assertThat(conflictCount(deletedDuring)).isZero();
        verify(storyDesignService, never()).startRevisionForConflicts(eq(deletedBefore), any(), any());
        verify(runService, never()).decideGateAsSystem(any(), any(), any());
    }

    @Test
    void idempotentSecondPass() {
        UUID releasing = makeStory("conflict-idempotent-release");
        approveSpec(releasing, "# Spec v1");
        Parked toRelease = parkedTaskRun(releasing, 1);
        approveSpec(releasing, "# Spec v2");
        UUID revising = makeStory("conflict-idempotent-revise");
        approveSpec(revising, "# Spec v1");
        Parked toRevise = parkedTaskRun(revising, 1);

        lifecycle.processConflicts();
        lifecycle.processConflicts();

        verify(runService, times(1)).decideGateAsSystem(eq(toRelease.run().getId()), any(), any());
        verify(uploadService, times(1)).overwriteRunInput(eq(toRelease.run().getId()), any(), any());
        verify(storyDesignService, times(1)).startRevisionForConflicts(eq(revising), any(), any());
        assertThat(conflict(toRevise).getRevisionRunId()).isNotNull();
    }

    @Test
    void startRefused_logsOneWarningNamingIdsAndRetriesNextPass() {
        UUID storyId = makeStory("conflict-start-refused");
        approveSpec(storyId, "# Spec v1");
        Parked parked = parkedTaskRun(storyId, 1);
        doThrow(new ConflictException("Run quota exceeded"))
                .doCallRealMethod()
                .when(storyDesignService)
                .startRevisionForConflicts(eq(storyId), any(), any());

        List<ILoggingEvent> warnings = captureWarnings(() -> lifecycle.processConflicts());

        assertThat(warnings)
                .filteredOn(e -> e.getFormattedMessage().contains(storyId.toString()))
                .singleElement()
                .satisfies(e -> assertThat(e.getFormattedMessage())
                        .contains(parked.conflict().getId().toString()));
        assertThat(conflict(parked).getRevisionRunId()).isNull();

        lifecycle.processConflicts();

        assertThat(conflict(parked).getRevisionRunId()).isNotNull();
    }

    @Test
    void taskRunEnded_conflictClosedWithoutRevision() {
        UUID storyId = makeStory("conflict-task-run-ended");
        approveSpec(storyId, "# Spec v1");
        Parked parked = parkedTaskRun(storyId, 1);
        setStatus(parked.run().getId(), WorkflowRunStatus.cancelled);

        lifecycle.processConflicts();

        verify(storyDesignService, never()).startRevisionForConflicts(eq(storyId), any(), any());
        verify(runService, never()).decideGateAsSystem(any(), any(), any());
        assertThat(conflict(parked).getReleasedAt()).isNotNull();
    }

    /** A run that moved past a conflict, say by a human resuming its gate, waits on its newer one only. */
    @Test
    void supersededConflict_closedWithoutDecidingTheNewerGate() {
        UUID storyId = makeStory("conflict-superseded");
        approveSpec(storyId, "# Spec v1");
        Parked older = parkedTaskRun(storyId, 1);
        older.gate().setStatus(NodeExecutionStatus.completed);
        older.gate().setDecision(TaskDeliveryGraph.RESUME);
        execRepo.saveAndFlush(older.gate());
        NodeExecution analyze = analyzeExecution(older.run(), 2);
        StoryConflict newer = saveConflict(storyId, older.run(), analyze, 1);
        NodeExecution newerGate = awaitGate(older.run(), 2);
        approveSpec(storyId, "# Spec v2");

        lifecycle.processConflicts();

        verify(runService, times(1)).decideGateAsSystem(any(), any(), any());
        verify(runService).decideGateAsSystem(eq(older.run().getId()), eq(newerGate.getId()), any());
        assertThat(conflict(older).getReleasedAt()).isNotNull();
        assertThat(conflictRepo.findById(newer.getId()).orElseThrow().getReleasedAt())
                .isNotNull();
    }

    // ---- helpers ----

    /** A parked Task Delivery run: its Analyze raised {@code conflict}, and its Await gate is open. */
    private record Parked(WorkflowRun run, NodeExecution analyze, NodeExecution gate, StoryConflict conflict) {}

    private Parked parkedTaskRun(UUID storyId, int raisedAgainst) {
        return taskRunWithConflict(storyId, raisedAgainst, NodeExecutionStatus.awaiting_human);
    }

    /** A null {@code gateStatus} leaves the run with no Await execution yet. */
    private Parked taskRunWithConflict(UUID storyId, int raisedAgainst, NodeExecutionStatus gateStatus) {
        TaskResponse task = taskService.create(storyId, new TaskRequest("Task", "D"));
        WorkflowRun run = new WorkflowRun();
        run.setGraphTemplateId(taskDeliveryTemplateId());
        run.setStatus(WorkflowRunStatus.awaiting_human);
        run.setTaskId(task.id());
        run.setStorySpecVersion(raisedAgainst);
        run = runRepo.saveAndFlush(run);
        run.setInputArtifactRefs("{\"story_spec.md\":\"system/runs/" + run.getId() + "/inputs/story_spec.md\"}");
        run = runRepo.saveAndFlush(run);
        NodeExecution analyze = analyzeExecution(run, 1);
        StoryConflict conflict = saveConflict(storyId, run, analyze, raisedAgainst);
        NodeExecution gate = null;
        if (gateStatus != null) {
            gate = awaitGate(run, 1);
            gate.setStatus(gateStatus);
            gate = execRepo.saveAndFlush(gate);
        }
        return new Parked(run, analyze, gate, conflict);
    }

    private NodeExecution analyzeExecution(WorkflowRun run, int iteration) {
        NodeExecution exec = execution(run, TaskDeliveryGraph.ANALYZE, iteration, NodeExecutionStatus.completed);
        exec.setDecision(TaskDeliveryGraph.STORY_CONFLICT);
        return execRepo.saveAndFlush(exec);
    }

    private NodeExecution awaitGate(WorkflowRun run, int iteration) {
        return execRepo.saveAndFlush(
                execution(run, TaskDeliveryGraph.AWAIT_STORY_REVISION, iteration, NodeExecutionStatus.awaiting_human));
    }

    private NodeExecution execution(WorkflowRun run, String label, int iteration, NodeExecutionStatus status) {
        TemplateNode node = templateNodeRepo.findByGraphTemplateId(run.getGraphTemplateId()).stream()
                .filter(n -> label.equals(n.getLabel()))
                .findFirst()
                .orElseThrow();
        NodeExecution exec = new NodeExecution();
        exec.setWorkflowRunId(run.getId());
        exec.setTemplateNodeId(node.getId());
        exec.setLabel(label);
        exec.setIteration(iteration);
        exec.setStatus(status);
        return exec;
    }

    private StoryConflict saveConflict(UUID storyId, WorkflowRun run, NodeExecution analyze, int raisedAgainst) {
        StoryConflict c = new StoryConflict();
        c.setStoryId(storyId);
        c.setTaskRunId(run.getId());
        c.setAnalyzeNodeExecutionId(analyze.getId());
        c.setRaisedAgainstVersion(raisedAgainst);
        c.setReport("The spec cannot be delivered as written.");
        return conflictRepo.saveAndFlush(c);
    }

    private StoryConflict conflict(Parked parked) {
        entityManager.flush();
        entityManager.clear();
        return conflictRepo.findById(parked.conflict().getId()).orElseThrow();
    }

    private int conflictCount(UUID storyId) {
        return jdbc.queryForObject("SELECT count(*) FROM story_conflict WHERE story_id = ?", Integer.class, storyId);
    }

    private WorkflowRun reloadRun(UUID runId) {
        entityManager.flush();
        entityManager.clear();
        return runRepo.findById(runId).orElseThrow();
    }

    private WorkflowRun designRun(UUID storyId, WorkflowRunStatus status) {
        WorkflowRun run = new WorkflowRun();
        run.setGraphTemplateId(templateRepo
                .findFirstByGraphIdOrderByVersionDesc(GraphIds.STORY_DESIGN)
                .orElseThrow()
                .getId());
        run.setStatus(status);
        run.setStoryId(storyId);
        return runRepo.saveAndFlush(run);
    }

    private void completeRevision(UUID storyId, UUID revisionId, String spec) {
        setStatus(revisionId, WorkflowRunStatus.completed);
        specService.recordApproved(storyId, revisionId, spec);
    }

    private void setStatus(UUID runId, WorkflowRunStatus status) {
        WorkflowRun run = reloadRun(runId);
        run.setStatus(status);
        runRepo.saveAndFlush(run);
    }

    /** Clears the run's Task link first: a Task's own runs otherwise block the Story's cascade. */
    private void deleteStory(UUID storyId, Parked parked) {
        entityManager.flush();
        jdbc.update(
                "UPDATE workflow_run SET task_id = NULL WHERE id = ?",
                parked.run().getId());
        jdbc.update("DELETE FROM story WHERE id = ?", storyId);
        entityManager.clear();
    }

    private void approveSpec(UUID storyId, String content) {
        entityManager.flush();
        jdbc.update("""
                INSERT INTO story_spec_version (story_id, version, content)
                SELECT ?, COALESCE(MAX(version), 0) + 1, ?
                FROM story_spec_version WHERE story_id = ?
                """, storyId, content, storyId);
    }

    private List<ILoggingEvent> captureWarnings(Runnable work) {
        Logger logger = (Logger) LoggerFactory.getLogger(StorySpecLifecycleService.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            work.run();
        } finally {
            logger.detachAppender(appender);
        }
        return appender.list.stream().filter(e -> e.getLevel() == Level.WARN).toList();
    }

    private UUID taskDeliveryTemplateId() {
        return templateRepo
                .findFirstByGraphIdOrderByVersionDesc(GraphIds.TASK_DELIVERY)
                .orElseThrow()
                .getId();
    }

    private UUID makeAutopilot() {
        UUID id = UUID.randomUUID();
        autopilotRepo.insertDefaults(id);
        autopilotRepo.engage(id, Instant.now());
        return id;
    }

    private UUID makeStory(String slug) {
        String url = "https://github.com/test/" + slug + ".git";
        GitRepo r = new GitRepo();
        r.setUrl(url);
        r.setName(RepoNameUtil.deriveOwnerRepoName(url));
        GitRepo repo = gitRepoRepo.save(r);
        EpicResponse epic = epicService.create(new EpicRequest("Epic", "D", null, repo.getId()), null);
        StoryResponse story = storyService.create(epic.id(), new StoryRequest("Story", "D"));
        return story.id();
    }
}
