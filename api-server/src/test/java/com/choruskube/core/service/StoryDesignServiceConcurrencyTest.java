package com.choruskube.core.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

import com.choruskube.core.BaseTest;
import com.choruskube.core.CommittedFixtureCleaner;
import com.choruskube.core.StorySpecFixtures;
import com.choruskube.core.config.GraphIds;
import com.choruskube.core.dto.EpicRequest;
import com.choruskube.core.dto.EpicResponse;
import com.choruskube.core.dto.RunResponse;
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
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import io.temporal.serviceclient.WorkflowServiceStubs;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import software.amazon.awssdk.services.s3.S3Client;

/**
 * Not {@code @Transactional}: a second starter is held off only by the first one's committed row
 * lock, which a single test transaction cannot show. {@link #removeEverythingThisTestCommitted()}
 * undoes the commits, since the suite shares one database.
 */
class StoryDesignServiceConcurrencyTest extends BaseTest {

    @Autowired
    private StoryDesignService service;

    @Autowired
    private EpicService epicService;

    @Autowired
    private StoryService storyService;

    @Autowired
    private GitRepoRepository gitRepoRepo;

    @Autowired
    private WorkflowRunRepository runRepo;

    @Autowired
    private AutopilotRepository autopilotRepo;

    @Autowired
    private TaskService taskService;

    @Autowired
    private GraphTemplateRepository templateRepo;

    @Autowired
    private TemplateNodeRepository templateNodeRepo;

    @Autowired
    private NodeExecutionRepository execRepo;

    @Autowired
    private StoryConflictRepository conflictRepo;

    @Autowired
    private JdbcTemplate jdbc;

    @MockitoBean
    private WorkflowServiceStubs workflowServiceStubs;

    @MockitoBean
    private WorkflowClient workflowClient;

    @MockitoBean
    private RunEventPublisher runEventPublisher;

    @MockitoBean
    private S3Client s3Client;

    @MockitoSpyBean
    private UploadService uploadService;

    private CommittedFixtureCleaner cleaner;

    @BeforeEach
    void setUp() {
        cleaner = new CommittedFixtureCleaner(jdbc);
        WorkflowStub mockStub = Mockito.mock(WorkflowStub.class);
        Mockito.when(workflowClient.newUntypedWorkflowStub(
                        ArgumentMatchers.anyString(), ArgumentMatchers.any(WorkflowOptions.class)))
                .thenReturn(mockStub);
    }

    @AfterEach
    void removeEverythingThisTestCommitted() {
        cleaner.deleteAll();
    }

    @Test
    void concurrentManualAndAutopilotDesignStarts_produceExactlyOneRun() throws Exception {
        StoryResponse story = makeStory("story-design-race");
        UUID autopilotId = cleaner.trackAutopilot(makeAutopilot());

        CyclicBarrier startLine = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<Throwable> outcomes = new ArrayList<>();
        try {
            List<Future<Throwable>> futures = pool.invokeAll(List.of(
                    attempt(startLine, () -> service.startDesign(story.id())),
                    attempt(startLine, () -> service.startDesignForAutopilot(story.id(), autopilotId))));
            for (Future<Throwable> f : futures) {
                outcomes.add(f.get(30, TimeUnit.SECONDS));
            }
        } finally {
            pool.shutdownNow();
        }

        List<WorkflowRun> runs = runRepo.findByStoryIdInAndStatusInAndDeletedAtIsNull(
                List.of(story.id()), List.of(WorkflowRunStatus.values()));
        runs.forEach(run -> cleaner.trackWorkflowRun(run.getId()));
        assertThat(runs).as("exactly one design run may be open").hasSize(1);
        assertThat(outcomes.stream().filter(Objects::isNull).count()).isEqualTo(1);
        assertThat(outcomes.stream().filter(Objects::nonNull).toList())
                .as("the loser is refused by the open-run guard, not by a database error")
                .allMatch(ConflictException.class::isInstance);
    }

    /** Two passes, or two replicas, read the same conflicts before either took the Story lock. */
    @Test
    void concurrentSystemRevisionsForTheSameConflicts_oneStartsAndTheOtherStartsNothing() throws Exception {
        StoryResponse story = makeStory("story-revision-race");
        StorySpecFixtures.approve(jdbc, story.id());
        List<StoryConflict> conflicts = List.of(committedConflict(story), committedConflict(story));

        List<Object> outcomes = race(
                () -> service.startRevisionForConflicts(story.id(), conflicts, null),
                () -> service.startRevisionForConflicts(story.id(), conflicts, null));

        List<WorkflowRun> runs = designRunsOf(story.id());
        assertThat(runs).as("exactly one revision may start").hasSize(1);
        assertThat(outcomes)
                .as("the loser finds every conflict taken and starts nothing, rather than failing its assignment")
                .containsExactlyInAnyOrder(
                        Optional.empty(), Optional.of(runs.getFirst().getId()));
        assertThat(conflicts).allSatisfy(c -> assertThat(
                        conflictRepo.findById(c.getId()).orElseThrow().getRevisionRunId())
                .isEqualTo(runs.getFirst().getId()));
    }

    /** Either side may win; {@link #manualReviseCommittedFirst_staleSystemRevisionStartsNothing} pins one order. */
    @Test
    void concurrentManualReviseAndSystemRevision_eitherWinnerLeavesOneRunAnsweringEveryConflict() throws Exception {
        StoryResponse story = makeStory("story-revise-race");
        StorySpecFixtures.approve(jdbc, story.id());
        List<StoryConflict> conflicts = List.of(committedConflict(story), committedConflict(story));

        List<Object> outcomes = race(
                () -> service.startRevision(story.id(), "Guidance"),
                () -> service.startRevisionForConflicts(story.id(), conflicts, null));

        List<WorkflowRun> runs = designRunsOf(story.id());
        assertThat(runs).as("exactly one revision may start").hasSize(1);
        assertThat(outcomes)
                .filteredOn(Throwable.class::isInstance)
                .as("a losing manual Revise is refused by the open-run guard, not by a database error")
                .allMatch(ConflictException.class::isInstance);
        assertThat(conflicts).allSatisfy(c -> assertThat(
                        conflictRepo.findById(c.getId()).orElseThrow().getRevisionRunId())
                .isEqualTo(runs.getFirst().getId()));
    }

    @Test
    void manualReviseCommittedFirst_staleSystemRevisionStartsNothing() {
        StoryResponse story = makeStory("story-revise-then-stale-pass");
        StorySpecFixtures.approve(jdbc, story.id());
        List<StoryConflict> conflicts = List.of(committedConflict(story), committedConflict(story));

        RunResponse manual = service.startRevision(story.id(), "Guidance");
        Optional<RunResponse> stale = service.startRevisionForConflicts(story.id(), conflicts, null);

        assertThat(stale).isEmpty();
        assertThat(designRunsOf(story.id())).extracting(WorkflowRun::getId).containsExactly(manual.id());
        assertThat(conflicts).allSatisfy(c -> assertThat(
                        conflictRepo.findById(c.getId()).orElseThrow().getRevisionRunId())
                .isEqualTo(manual.id()));
    }

    /**
     * Pauses a system revision after it has read its conflicts. A close of one of them must wait for
     * the revision's commit, or the revision's strict assignment would find it gone and roll back.
     */
    @Test
    void closeDuringSystemRevision_waitsForTheRevisionToCommit() throws Exception {
        StoryResponse story = makeStory("story-revision-close-race");
        StorySpecFixtures.approve(jdbc, story.id());
        StoryConflict closing = committedConflict(story);
        List<StoryConflict> conflicts = List.of(closing, committedConflict(story));
        CountDownLatch paused = new CountDownLatch(1);
        CountDownLatch resume = new CountDownLatch(1);
        doAnswer(inv -> {
                    paused.countDown();
                    assertThat(resume.await(30, TimeUnit.SECONDS)).isTrue();
                    return inv.callRealMethod();
                })
                .when(uploadService)
                .stageTexts(any());

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Optional<RunResponse>> revision =
                    pool.submit(() -> service.startRevisionForConflicts(story.id(), conflicts, null));
            assertThat(paused.await(30, TimeUnit.SECONDS)).isTrue();
            Future<Integer> close = pool.submit(() -> conflictRepo.markReleased(closing.getId(), Instant.now()));
            awaitLockWaitOnStoryConflict();
            assertThat(close).isNotDone();
            resume.countDown();

            RunResponse started = revision.get(30, TimeUnit.SECONDS).orElseThrow();
            assertThat(close.get(30, TimeUnit.SECONDS)).isEqualTo(1);
            assertThat(conflicts).allSatisfy(c -> assertThat(
                            conflictRepo.findById(c.getId()).orElseThrow().getRevisionRunId())
                    .isEqualTo(started.id()));
        } finally {
            resume.countDown();
            pool.shutdownNow();
        }
    }

    private void awaitLockWaitOnStoryConflict() throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (System.nanoTime() < deadline) {
            Integer waiting = jdbc.queryForObject(
                    "SELECT count(*) FROM pg_stat_activity WHERE wait_event_type = 'Lock'"
                            + " AND query ILIKE 'update story_conflict%'",
                    Integer.class);
            if (waiting != null && waiting > 0) {
                return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("the close never waited on the revision's row lock");
    }

    /** Each outcome is the call's result (a started run as its id), or what it threw. */
    private static List<Object> race(Callable<Object> first, Callable<Object> second) throws Exception {
        CyclicBarrier startLine = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<Object> outcomes = new ArrayList<>();
        try {
            List<Future<Object>> futures =
                    pool.invokeAll(List.of(outcome(startLine, first), outcome(startLine, second)));
            for (Future<Object> f : futures) {
                outcomes.add(f.get(30, TimeUnit.SECONDS));
            }
        } finally {
            pool.shutdownNow();
        }
        return outcomes;
    }

    private static Callable<Object> outcome(CyclicBarrier startLine, Callable<Object> action) {
        return () -> {
            startLine.await(30, TimeUnit.SECONDS);
            try {
                Object result = action.call();
                if (result instanceof Optional<?> optional) {
                    return optional.map(r -> ((RunResponse) r).id());
                }
                return result instanceof RunResponse run ? run.id() : result;
            } catch (Throwable t) {
                return t;
            }
        };
    }

    private List<WorkflowRun> designRunsOf(UUID storyId) {
        List<WorkflowRun> runs = runRepo.findByStoryIdInAndStatusInAndDeletedAtIsNull(
                List.of(storyId), List.of(WorkflowRunStatus.values()));
        runs.forEach(run -> cleaner.trackWorkflowRun(run.getId()));
        return runs;
    }

    /** A committed conflict raised against the Story's v1 by a parked run of a new Task. */
    private StoryConflict committedConflict(StoryResponse story) {
        TaskResponse task = taskService.create(story.id(), new TaskRequest("Task", "D"));
        WorkflowRun run = new WorkflowRun();
        run.setGraphTemplateId(templateRepo
                .findFirstByGraphIdOrderByVersionDesc(GraphIds.TASK_DELIVERY)
                .orElseThrow()
                .getId());
        run.setStatus(WorkflowRunStatus.awaiting_human);
        run.setTaskId(task.id());
        run = runRepo.save(run);
        TemplateNode node = templateNodeRepo.findByGraphTemplateId(run.getGraphTemplateId()).stream()
                .findFirst()
                .orElseThrow();
        NodeExecution analyze = new NodeExecution();
        analyze.setWorkflowRunId(run.getId());
        analyze.setTemplateNodeId(node.getId());
        analyze.setLabel("analyze");
        analyze.setStatus(NodeExecutionStatus.completed);
        analyze = execRepo.save(analyze);
        StoryConflict conflict = new StoryConflict();
        conflict.setStoryId(story.id());
        conflict.setTaskRunId(run.getId());
        conflict.setAnalyzeNodeExecutionId(analyze.getId());
        conflict.setRaisedAgainstVersion(1);
        conflict.setReport("The spec cannot be delivered as written.");
        return conflictRepo.save(conflict);
    }

    private static Callable<Throwable> attempt(CyclicBarrier startLine, Runnable action) {
        return () -> {
            startLine.await(30, TimeUnit.SECONDS);
            try {
                action.run();
                return null;
            } catch (Throwable t) {
                return t;
            }
        };
    }

    private StoryResponse makeStory(String slug) {
        String url = "https://github.com/test/" + slug + "-"
                + UUID.randomUUID().toString().substring(0, 8) + ".git";
        GitRepo r = new GitRepo();
        r.setUrl(url);
        r.setName(RepoNameUtil.deriveOwnerRepoName(url));
        GitRepo repo = gitRepoRepo.save(r);
        cleaner.trackSoftwareProject(repo.getId());
        EpicResponse epic = epicService.create(new EpicRequest("Epic", "Epic desc", null, repo.getId()), null);
        cleaner.trackEpic(epic.id());
        return storyService.create(epic.id(), new StoryRequest("Contended story", "D"));
    }

    private UUID makeAutopilot() {
        UUID id = UUID.randomUUID();
        autopilotRepo.insertDefaults(id);
        autopilotRepo.engage(id, Instant.now());
        return id;
    }
}
