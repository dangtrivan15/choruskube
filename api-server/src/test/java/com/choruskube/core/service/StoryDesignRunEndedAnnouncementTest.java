package com.choruskube.core.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.choruskube.core.BaseTest;
import com.choruskube.core.config.WorkflowClientRegistry;
import com.choruskube.core.dto.InternalUpdateRunStatusRequest;
import com.choruskube.core.model.Epic;
import com.choruskube.core.model.GitRepo;
import com.choruskube.core.model.GraphTemplate;
import com.choruskube.core.model.Story;
import com.choruskube.core.model.WorkflowRun;
import com.choruskube.core.model.enums.WorkItemStatus;
import com.choruskube.core.model.enums.WorkflowRunStatus;
import com.choruskube.core.repository.EpicRepository;
import com.choruskube.core.repository.GitRepoRepository;
import com.choruskube.core.repository.GraphTemplateRepository;
import com.choruskube.core.repository.StoryRepository;
import com.choruskube.core.repository.WorkflowRunRepository;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowStub;
import io.temporal.serviceclient.WorkflowServiceStubs;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

/**
 * A Story's and its Tasks' pages read "Design in progress" off the Story's open design run, so a
 * design run that ends without a version must announce the Story, or open pages keep Start disabled.
 */
@Transactional
class StoryDesignRunEndedAnnouncementTest extends BaseTest {

    @Autowired
    private InternalRunService internalRunService;

    @Autowired
    private RunService runService;

    @Autowired
    private WorkflowRunRepository runRepo;

    @Autowired
    private GraphTemplateRepository graphTemplateRepo;

    @Autowired
    private GitRepoRepository gitRepoRepo;

    @Autowired
    private EpicRepository epicRepo;

    @Autowired
    private StoryRepository storyRepo;

    @MockitoBean
    private WorkflowServiceStubs workflowServiceStubs;

    @MockitoBean
    private WorkflowClient workflowClient;

    @MockitoBean
    private WorkflowClientRegistry workflowClientRegistry;

    @MockitoBean
    private RunEventPublisher eventPublisher;

    private Story story;
    private GraphTemplate template;

    @BeforeEach
    void setUp() {
        WorkflowClient client = mock(WorkflowClient.class);
        when(client.newUntypedWorkflowStub(anyString())).thenReturn(mock(WorkflowStub.class));
        when(workflowClientRegistry.clientFor(any())).thenReturn(client);

        GitRepo project = new GitRepo();
        project.setName("design-run-ended-" + UUID.randomUUID());
        project.setUrl("https://github.com/acme/design-run-ended.git");
        project = gitRepoRepo.save(project);

        Epic epic = new Epic();
        epic.setSoftwareProjectId(project.getId());
        epic.setTitle("Epic");
        epic.setDescription("d");
        epic = epicRepo.save(epic);

        story = new Story();
        story.setEpicId(epic.getId());
        story.setTitle("Story");
        story.setDescription("d");
        story = storyRepo.save(story);

        template = new GraphTemplate();
        template.setName("Design Run Ended Test Template");
        template.setGraphId("design-run-ended-test-" + UUID.randomUUID());
        template.setVersion(1);
        template = graphTemplateRepo.save(template);
    }

    @ParameterizedTest
    @ValueSource(strings = {"failed", "cancelled"})
    void designRunEndingWithoutAVersion_announcesTheStory(String ended) {
        WorkflowRun run = run(story.getId());

        internalRunService.updateRunStatus(run.getId(), new InternalUpdateRunStatusRequest(ended));

        verify(eventPublisher).publishRoadmapItemChanged("story", story.getId(), WorkItemStatus.backlog.name());
    }

    @Test
    void humanCancelOfADesignRun_announcesTheStory() {
        WorkflowRun run = run(story.getId());

        runService.cancelRun(run.getId());

        verify(eventPublisher).publishRoadmapItemChanged("story", story.getId(), WorkItemStatus.backlog.name());
    }

    @Test
    void completedDesignRun_isLeftToTheVersionRecorder() {
        WorkflowRun run = run(story.getId());

        internalRunService.updateRunStatus(run.getId(), new InternalUpdateRunStatusRequest("completed"));

        verify(eventPublisher, never()).publishRoadmapItemChanged(eq("story"), any(), any());
    }

    @Test
    void runWithoutAStory_announcesNoStory() {
        WorkflowRun run = run(null);

        internalRunService.updateRunStatus(run.getId(), new InternalUpdateRunStatusRequest("failed"));

        verify(eventPublisher, never()).publishRoadmapItemChanged(eq("story"), any(), any());
    }

    private WorkflowRun run(UUID storyId) {
        WorkflowRun run = new WorkflowRun();
        run.setGraphTemplateId(template.getId());
        run.setStatus(WorkflowRunStatus.running);
        run.setExternalRunId("choruskube-run-" + UUID.randomUUID());
        run.setStoryId(storyId);
        return runRepo.save(run);
    }
}
