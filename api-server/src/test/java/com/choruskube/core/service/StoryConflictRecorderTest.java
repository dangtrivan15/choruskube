package com.choruskube.core.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.choruskube.core.BaseTest;
import com.choruskube.core.StorySpecFixtures;
import com.choruskube.core.config.GraphIds;
import com.choruskube.core.config.TaskDeliveryGraph;
import com.choruskube.core.credential.GitHubCredentialResolver;
import com.choruskube.core.dto.EpicRequest;
import com.choruskube.core.dto.EpicResponse;
import com.choruskube.core.dto.InternalUpdateNodeExecutionRequest;
import com.choruskube.core.dto.StoryRequest;
import com.choruskube.core.dto.StoryResponse;
import com.choruskube.core.dto.TaskRequest;
import com.choruskube.core.dto.TaskResponse;
import com.choruskube.core.model.GitRepo;
import com.choruskube.core.model.NodeExecution;
import com.choruskube.core.model.StoryConflict;
import com.choruskube.core.model.TemplateNode;
import com.choruskube.core.model.WorkflowRun;
import com.choruskube.core.model.enums.NodeExecutionStatus;
import com.choruskube.core.model.enums.WorkflowRunStatus;
import com.choruskube.core.repository.GitRepoRepository;
import com.choruskube.core.repository.GraphTemplateRepository;
import com.choruskube.core.repository.NodeExecutionRepository;
import com.choruskube.core.repository.StoryConflictRepository;
import com.choruskube.core.repository.TemplateNodeRepository;
import com.choruskube.core.repository.WorkflowRunRepository;
import com.choruskube.core.util.RepoNameUtil;
import io.temporal.client.WorkflowClient;
import io.temporal.serviceclient.WorkflowServiceStubs;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.http.AbortableInputStream;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;

@Transactional
class StoryConflictRecorderTest extends BaseTest {

    @Autowired
    private StoryConflictRecorder recorder;

    @Autowired
    private InternalRunService internalRunService;

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
    private JdbcTemplate jdbc;

    @PersistenceContext
    private EntityManager entityManager;

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
    void recorder_analyzeStoryConflict_recordsRow() {
        StoryResponse story = makeStory("recorder-records");
        WorkflowRun run = taskRun(story.id(), 1);
        NodeExecution analyze = analyzeExecution(run, TaskDeliveryGraph.STORY_CONFLICT, "The cache cannot work.");

        recorder.onNodeCompleted(run, analyze);

        assertThat(conflictsOf(story.id())).singleElement().satisfies(c -> {
            assertThat(c.getTaskRunId()).isEqualTo(run.getId());
            assertThat(c.getAnalyzeNodeExecutionId()).isEqualTo(analyze.getId());
            assertThat(c.getRaisedAgainstVersion()).isEqualTo(1);
            assertThat(c.getReport()).isEqualTo("The cache cannot work.");
            assertThat(c.getRevisionRunId()).isNull();
            assertThat(c.getReleasedAt()).isNull();
        });
    }

    @Test
    void recorder_raisedAgainstTheRunsVersion_notTheStorysCurrentOne() {
        StoryResponse story = makeStory("recorder-run-version");
        WorkflowRun run = taskRun(story.id(), 1);
        approveSpec(story.id());
        NodeExecution analyze = analyzeExecution(run, TaskDeliveryGraph.STORY_CONFLICT, "Wrong.");

        recorder.onNodeCompleted(run, analyze);

        assertThat(conflictsOf(story.id())).singleElement().satisfies(c -> assertThat(c.getRaisedAgainstVersion())
                .isEqualTo(1));
    }

    /** An activity retry repeats the completed-status call, which reaches the recorder each time. */
    @Test
    void recorder_repeatedCompletion_recordsOnce() {
        StoryResponse story = makeStory("recorder-once");
        WorkflowRun run = taskRun(story.id(), 1);
        NodeExecution analyze = analyzeExecution(run, TaskDeliveryGraph.STORY_CONFLICT, "Raised once.");
        analyze.setStatus(NodeExecutionStatus.running);
        execRepo.saveAndFlush(analyze);
        InternalUpdateNodeExecutionRequest completed = new InternalUpdateNodeExecutionRequest(
                "completed", "Raised a conflict", analyze.getArtifactRefs(), null, null, null, null);

        internalRunService.updateNodeExecutionStatus(run.getId(), analyze.getId(), completed);
        internalRunService.updateNodeExecutionStatus(run.getId(), analyze.getId(), completed);

        assertThat(conflictsOf(story.id())).singleElement().satisfies(c -> assertThat(c.getReport())
                .isEqualTo("Raised once."));
    }

    @Test
    void recorder_otherDecision_noRow() {
        StoryResponse story = makeStory("recorder-other");
        WorkflowRun run = taskRun(story.id(), 1);
        NodeExecution planned = analyzeExecution(run, TaskDeliveryGraph.PLANNED, "Not a conflict.");
        NodeExecution needsSpec = analyzeExecution(run, TaskDeliveryGraph.NEEDS_SPEC, "Not a conflict.");

        recorder.onNodeCompleted(run, planned);
        recorder.onNodeCompleted(run, needsSpec);

        assertThat(conflictsOf(story.id())).isEmpty();
    }

    @Test
    void recorder_storyConflictFromAnotherNode_noRow() {
        StoryResponse story = makeStory("recorder-other-node");
        WorkflowRun run = taskRun(story.id(), 1);
        NodeExecution exec = analyzeExecution(run, TaskDeliveryGraph.STORY_CONFLICT, "Wrong node.");
        exec.setLabel(TaskDeliveryGraph.IMPLEMENT);

        recorder.onNodeCompleted(run, exec);

        assertThat(conflictsOf(story.id())).isEmpty();
    }

    @Test
    void recorder_missingReport_recordsPlaceholder() {
        StoryResponse story = makeStory("recorder-missing");
        WorkflowRun run = taskRun(story.id(), 1);
        NodeExecution analyze = analyzeExecution(run, TaskDeliveryGraph.STORY_CONFLICT, null);

        recorder.onNodeCompleted(run, analyze);

        assertThat(conflictsOf(story.id())).singleElement().satisfies(c -> assertThat(c.getReport())
                .isEqualTo("(Analyze wrote no story_conflict.md)"));
    }

    @Test
    void recorder_runWithoutStoryVersion_noRow() {
        StoryResponse story = makeStory("recorder-no-version");
        WorkflowRun run = taskRun(story.id(), 1);
        run.setStorySpecVersion(null);
        runRepo.saveAndFlush(run);
        NodeExecution analyze = analyzeExecution(run, TaskDeliveryGraph.STORY_CONFLICT, "Against nothing.");

        recorder.onNodeCompleted(run, analyze);

        assertThat(conflictsOf(story.id())).isEmpty();
    }

    // ---- helpers ----

    private List<StoryConflict> conflictsOf(UUID storyId) {
        entityManager.flush();
        entityManager.clear();
        return conflictRepo.findByStoryIdAndReleasedAtIsNullOrderByCreatedAtAsc(storyId);
    }

    /** A Task Delivery run of a fresh Task in a Story approved up to {@code version}, stamped with it. */
    private WorkflowRun taskRun(UUID storyId, int version) {
        TaskResponse task = taskService.create(storyId, new TaskRequest("Task", "D"));
        for (int v = 1; v <= version; v++) {
            approveSpec(storyId);
        }
        WorkflowRun run = new WorkflowRun();
        run.setGraphTemplateId(templateRepo
                .findFirstByGraphIdOrderByVersionDesc(GraphIds.TASK_DELIVERY)
                .orElseThrow()
                .getId());
        run.setStatus(WorkflowRunStatus.running);
        run.setTaskId(task.id());
        run.setStorySpecVersion(version);
        return runRepo.saveAndFlush(run);
    }

    /** A completed Analyze execution; a null {@code report} leaves its story_conflict.md unwritten. */
    private NodeExecution analyzeExecution(WorkflowRun run, String decision, String report) {
        TemplateNode node = templateNodeRepo.findByGraphTemplateId(run.getGraphTemplateId()).stream()
                .filter(n -> TaskDeliveryGraph.ANALYZE.equals(n.getLabel()))
                .findFirst()
                .orElseThrow();
        NodeExecution exec = new NodeExecution();
        exec.setWorkflowRunId(run.getId());
        exec.setTemplateNodeId(node.getId());
        exec.setLabel(TaskDeliveryGraph.ANALYZE);
        exec.setIteration(execRepo.findByWorkflowRunId(run.getId()).size() + 1);
        exec.setStatus(NodeExecutionStatus.completed);
        exec.setDecision(decision);
        exec = execRepo.saveAndFlush(exec);
        String prefix = "system/runs/" + run.getId() + "/outputs/" + exec.getId() + "/";
        exec.setArtifactRefs("{\"output\":\"" + prefix + "\"}");
        exec = execRepo.saveAndFlush(exec);
        if (report != null) {
            objects.put(prefix + "story_conflict.md", report);
        }
        return exec;
    }

    private void approveSpec(UUID storyId) {
        entityManager.flush();
        StorySpecFixtures.approve(jdbc, storyId);
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
