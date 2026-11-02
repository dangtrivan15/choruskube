package com.choruskube.core.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.choruskube.core.BaseTest;
import com.choruskube.core.CommittedFixtureCleaner;
import com.choruskube.core.credential.GitHubCredentialResolver;
import com.choruskube.core.dto.EpicRequest;
import com.choruskube.core.dto.EpicResponse;
import com.choruskube.core.dto.StoryRequest;
import com.choruskube.core.dto.StoryResponse;
import com.choruskube.core.exception.ConflictException;
import com.choruskube.core.model.GitRepo;
import com.choruskube.core.model.WorkflowRun;
import com.choruskube.core.model.enums.WorkflowRunStatus;
import com.choruskube.core.repository.AutopilotRepository;
import com.choruskube.core.repository.GitRepoRepository;
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
import java.util.UUID;
import java.util.concurrent.Callable;
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
    private JdbcTemplate jdbc;

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
