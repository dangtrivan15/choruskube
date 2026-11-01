package com.choruskube.core.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.choruskube.core.BaseTest;
import com.choruskube.core.config.GraphIds;
import com.choruskube.core.config.StoryDesignGraph;
import com.choruskube.core.credential.GitHubCredentialResolver;
import com.choruskube.core.dto.EpicRequest;
import com.choruskube.core.dto.EpicResponse;
import com.choruskube.core.dto.StoryRequest;
import com.choruskube.core.dto.StoryResponse;
import com.choruskube.core.model.GitRepo;
import com.choruskube.core.model.NodeExecution;
import com.choruskube.core.model.StorySpecVersion;
import com.choruskube.core.model.TemplateNode;
import com.choruskube.core.model.WorkflowRun;
import com.choruskube.core.model.enums.NodeExecutionStatus;
import com.choruskube.core.model.enums.WorkflowRunStatus;
import com.choruskube.core.repository.GitRepoRepository;
import com.choruskube.core.repository.GraphTemplateRepository;
import com.choruskube.core.repository.NodeExecutionRepository;
import com.choruskube.core.repository.TemplateNodeRepository;
import com.choruskube.core.repository.WorkflowRunRepository;
import com.choruskube.core.util.RepoNameUtil;
import io.temporal.client.WorkflowClient;
import io.temporal.serviceclient.WorkflowServiceStubs;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.http.AbortableInputStream;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;

@Transactional
class StorySpecLifecycleServiceTest extends BaseTest {

    @Autowired
    private StorySpecLifecycleService lifecycle;

    @Autowired
    private StorySpecService specService;

    @Autowired
    private EpicService epicService;

    @Autowired
    private StoryService storyService;

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

    /** Object key to content; a key not in the map answers like a missing object. */
    private final Map<String, String> objects = new HashMap<>();

    @BeforeEach
    void setUp() {
        when(s3Client.getObject(any(GetObjectRequest.class))).thenAnswer(inv -> {
            String text = objects.get(inv.<GetObjectRequest>getArgument(0).key());
            if (text == null) {
                throw NoSuchKeyException.builder().message("missing").build();
            }
            return objectBody(text);
        });
    }

    @Test
    void recordCompletedDesigns_recordsApprovedSpecFromLatestReview() {
        StoryResponse story = makeStory("lifecycle-latest-review");
        WorkflowRun run = designRun(story.id(), WorkflowRunStatus.completed);
        reviewExecution(run, 1, "# Spec as first reviewed");
        reviewExecution(run, 2, "# Spec as finally approved");
        clearInvocations(runEventPublisher);

        int recorded = lifecycle.recordCompletedDesigns();

        assertThat(recorded).isGreaterThanOrEqualTo(1);
        StorySpecVersion current = specService.current(story.id()).orElseThrow();
        assertThat(current.getContent()).isEqualTo("# Spec as finally approved");
        assertThat(current.getVersion()).isEqualTo(1);
        assertThat(current.getRunId()).isEqualTo(run.getId());
        verify(runEventPublisher).publishRoadmapItemChanged("story", story.id(), story.stage());
    }

    @Test
    void recordCompletedDesigns_idempotent() {
        StoryResponse story = makeStory("lifecycle-idempotent");
        WorkflowRun run = designRun(story.id(), WorkflowRunStatus.completed);
        reviewExecution(run, 1, "# Spec");

        lifecycle.recordCompletedDesigns();
        int again = lifecycle.recordCompletedDesigns();

        assertThat(again).isZero();
        assertThat(specService.versions(story.id())).hasSize(1);
    }

    @Test
    void recordCompletedDesigns_ignoresRunningRun() {
        StoryResponse story = makeStory("lifecycle-running");
        WorkflowRun run = designRun(story.id(), WorkflowRunStatus.running);
        reviewExecution(run, 1, "# Spec still under review");

        lifecycle.recordCompletedDesigns();

        assertThat(specService.current(story.id())).isEmpty();
    }

    @Test
    void recordCompletedDesigns_secondRunBecomesV2() {
        StoryResponse story = makeStory("lifecycle-v2");
        WorkflowRun first = designRun(story.id(), WorkflowRunStatus.completed);
        reviewExecution(first, 1, "# Spec v1");
        lifecycle.recordCompletedDesigns();

        WorkflowRun revision = designRun(story.id(), WorkflowRunStatus.completed);
        reviewExecution(revision, 1, "# Spec v2");
        lifecycle.recordCompletedDesigns();

        assertThat(specService.versions(story.id()))
                .extracting(StorySpecVersion::getVersion, StorySpecVersion::getContent, StorySpecVersion::getRunId)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(1, "# Spec v1", first.getId()),
                        org.assertj.core.groups.Tuple.tuple(2, "# Spec v2", revision.getId()));
    }

    @Test
    void recordCompletedDesigns_unrecordableRun_logsErrorAndOthersStillRecord() {
        StoryResponse noReviewStory = makeStory("lifecycle-no-review");
        WorkflowRun noReview = designRun(noReviewStory.id(), WorkflowRunStatus.completed);
        StoryResponse noFileStory = makeStory("lifecycle-no-file");
        WorkflowRun noFile = designRun(noFileStory.id(), WorkflowRunStatus.completed);
        reviewExecution(noFile, 1, null);
        StoryResponse goodStory = makeStory("lifecycle-good");
        WorkflowRun good = designRun(goodStory.id(), WorkflowRunStatus.completed);
        reviewExecution(good, 1, "# Good spec");

        Logger logger = (Logger) LoggerFactory.getLogger(StorySpecLifecycleService.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            lifecycle.recordCompletedDesigns();
        } finally {
            logger.detachAppender(appender);
        }

        assertThat(specService.current(goodStory.id()))
                .map(StorySpecVersion::getContent)
                .contains("# Good spec");
        assertThat(specService.current(noReviewStory.id())).isEmpty();
        assertThat(specService.current(noFileStory.id())).isEmpty();
        assertThat(errorsNaming(appender, noReview.getId())).singleElement().satisfies(message -> assertThat(message)
                .contains(noReviewStory.id().toString()));
        assertThat(errorsNaming(appender, noFile.getId())).singleElement().satisfies(message -> assertThat(message)
                .contains(noFileStory.id().toString()));
        assertThat(errorsNaming(appender, good.getId())).isEmpty();
    }

    // ---- helpers ----

    private static List<String> errorsNaming(ListAppender<ILoggingEvent> appender, UUID runId) {
        return appender.list.stream()
                .filter(e -> e.getLevel() == Level.ERROR)
                .map(ILoggingEvent::getFormattedMessage)
                .filter(m -> m.contains(runId.toString()))
                .toList();
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

    /** A completed Story Spec Review execution; a null {@code spec} leaves its story_spec.md unwritten. */
    private void reviewExecution(WorkflowRun run, int iteration, String spec) {
        TemplateNode reviewNode = templateNodeRepo.findByGraphTemplateId(run.getGraphTemplateId()).stream()
                .filter(n -> StoryDesignGraph.REVIEW.equals(n.getLabel()))
                .findFirst()
                .orElseThrow();
        String prefix = "system/runs/" + run.getId() + "/outputs/review-" + iteration + "/";
        NodeExecution exec = new NodeExecution();
        exec.setWorkflowRunId(run.getId());
        exec.setTemplateNodeId(reviewNode.getId());
        exec.setLabel(StoryDesignGraph.REVIEW);
        exec.setLoopGroup("story-spec");
        exec.setStatus(NodeExecutionStatus.completed);
        exec.setIteration(iteration);
        exec.setCompletedAt(Instant.now());
        exec.setArtifactRefs("{\"output\":\"" + prefix + "\"}");
        execRepo.saveAndFlush(exec);
        if (spec != null) {
            objects.put(prefix + "story_spec.md", spec);
        }
    }

    private StoryResponse makeStory(String slug) {
        String url = "https://github.com/test/" + slug + ".git";
        GitRepo r = new GitRepo();
        r.setUrl(url);
        r.setName(RepoNameUtil.deriveOwnerRepoName(url));
        GitRepo repo = gitRepoRepo.save(r);
        EpicResponse epic = epicService.create(new EpicRequest("Epic", "D", null, repo.getId()), null);
        return storyService.create(epic.id(), new StoryRequest("Story", "D"));
    }

    private static ResponseInputStream<GetObjectResponse> objectBody(String text) {
        return new ResponseInputStream<>(
                GetObjectResponse.builder().build(),
                AbortableInputStream.create(new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8))));
    }
}
