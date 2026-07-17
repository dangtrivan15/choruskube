package com.choruskube.core.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.choruskube.core.BaseTest;
import com.choruskube.core.dto.EpicRequest;
import com.choruskube.core.dto.EpicResponse;
import com.choruskube.core.dto.StoryRequest;
import com.choruskube.core.dto.StoryResponse;
import com.choruskube.core.dto.TaskRequest;
import com.choruskube.core.dto.TaskResponse;
import com.choruskube.core.exception.ConflictException;
import com.choruskube.core.exception.ForbiddenException;
import com.choruskube.core.exception.NotFoundException;
import com.choruskube.core.model.GitRepo;
import com.choruskube.core.model.WorkflowRun;
import com.choruskube.core.model.enums.WorkflowRunStatus;
import com.choruskube.core.repository.GitRepoRepository;
import com.choruskube.core.repository.WorkflowRunRepository;
import com.choruskube.core.util.RepoNameUtil;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import io.temporal.serviceclient.WorkflowServiceStubs;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

@Transactional
public class DefaultTaskServiceTest extends BaseTest {

    @Autowired
    private TaskService service;

    @Autowired
    private StoryService storyService;

    @Autowired
    private EpicService epicService;

    @Autowired
    private GitRepoRepository gitRepoRepo;

    @Autowired
    private WorkflowRunRepository runRepo;

    @MockitoBean
    private WorkflowServiceStubs workflowServiceStubs;

    @MockitoBean
    private WorkflowClient workflowClient;

    @MockitoBean
    private RunEventPublisher runEventPublisher;

    @BeforeEach
    void setUp() {
        WorkflowStub mockStub = Mockito.mock(WorkflowStub.class);
        Mockito.when(workflowClient.newUntypedWorkflowStub(
                        ArgumentMatchers.anyString(), ArgumentMatchers.any(WorkflowOptions.class)))
                .thenReturn(mockStub);
    }

    @Test
    void create_underStory_denormalizesSoftwareProjectIdFromAncestorEpic() {
        GitRepo r = makeRepo("https://github.com/test/task-one.git");
        StoryResponse story = makeStory(r.getId());

        TaskResponse task = service.create(story.id(), new TaskRequest("Task title", "Task desc"));

        assertThat(task.storyId()).isEqualTo(story.id());
        assertThat(task.softwareProject()).isNotNull();
        assertThat(task.softwareProject().id()).isEqualTo(r.getId());
        assertThat(task.status()).isEqualTo("backlog");
    }

    @Test
    void create_underUnknownStory_throwsNotFound() {
        UUID unknown = UUID.randomUUID();
        assertThatThrownBy(() -> service.create(unknown, new TaskRequest("T", "D")))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void create_withRunId_underStoryInRunsSoftwareProject_succeeds() {
        GitRepo r = makeRepo("https://github.com/test/task-runid-proj-ok.git");
        StoryResponse story = makeStory(r.getId());

        TaskResponse task = service.create(story.id(), new TaskRequest("T", "D"), UUID.randomUUID(), r.getId());

        assertThat(task.storyId()).isEqualTo(story.id());
        assertThat(task.title()).isEqualTo("T");
    }

    @Test
    void create_withRunId_underStoryOutsideRunsSoftwareProject_throwsForbidden() {
        GitRepo r1 = makeRepo("https://github.com/test/task-runid-proj-a.git");
        GitRepo r2 = makeRepo("https://github.com/test/task-runid-proj-b.git");
        StoryResponse story = makeStory(r1.getId());

        // r2 is a real, different SoftwareProject in the same (only, OSS single-tenant) org — the
        // run's resolved software_project_id must still match the ancestor Epic's own, or the
        // Task would silently attach to a Story outside the run's actual target project.
        assertThatThrownBy(() -> service.create(story.id(), new TaskRequest("T", "D"), UUID.randomUUID(), r2.getId()))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    void update_nonBacklogTask_throwsConflict() {
        GitRepo r = makeRepo("https://github.com/test/task-update-blocked.git");
        StoryResponse story = makeStory(r.getId());
        TaskResponse task = service.create(story.id(), new TaskRequest("T", "D"));

        TaskResponse started = service.start(task.id());
        assertThat(started.status()).isEqualTo("in_progress");

        assertThatThrownBy(() -> service.update(task.id(), new TaskRequest("New", "D")))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void delete_nonBacklogTask_throwsConflict() {
        GitRepo r = makeRepo("https://github.com/test/task-delete-blocked.git");
        StoryResponse story = makeStory(r.getId());
        TaskResponse task = service.create(story.id(), new TaskRequest("T", "D"));
        service.start(task.id());

        assertThatThrownBy(() -> service.delete(task.id())).isInstanceOf(ConflictException.class);
    }

    @Test
    void start_backlogTask_createsRunAndTransitionsToInProgress() {
        GitRepo r = makeRepo("https://github.com/test/task-start.git");
        StoryResponse story = makeStory(r.getId());
        TaskResponse task = service.create(story.id(), new TaskRequest("Add health check", "Desc"));

        TaskResponse started = service.start(task.id());

        assertThat(started.status()).isEqualTo("in_progress");
        assertThat(started.latestRunId()).isNotNull();

        WorkflowRun run = runRepo.findById(started.latestRunId()).orElseThrow();
        assertThat(run.getTaskId()).isEqualTo(task.id());
    }

    @Test
    void start_whileActiveRunExists_throwsConflict() {
        GitRepo r = makeRepo("https://github.com/test/task-start-active.git");
        StoryResponse story = makeStory(r.getId());
        TaskResponse task = service.create(story.id(), new TaskRequest("T", "D"));
        service.start(task.id());

        // The most recent run is still pending (non-terminal) — re-triggering must be rejected.
        assertThatThrownBy(() -> service.start(task.id())).isInstanceOf(ConflictException.class);
    }

    @Test
    void start_afterMostRecentRunTerminal_restartsAndKeepsBothRunsInHistory() {
        GitRepo r = makeRepo("https://github.com/test/task-restart.git");
        StoryResponse story = makeStory(r.getId());
        TaskResponse task = service.create(story.id(), new TaskRequest("T", "D"));
        TaskResponse afterFirstStart = service.start(task.id());
        markRunTerminal(afterFirstStart.latestRunId(), WorkflowRunStatus.failed);

        TaskResponse afterRestart = service.start(task.id());

        assertThat(afterRestart.latestRunId()).isNotEqualTo(afterFirstStart.latestRunId());

        Page<com.choruskube.core.dto.RunSummary> history = service.listRuns(task.id(), PageRequest.of(0, 20));
        assertThat(history.getContent()).hasSize(2);
        assertThat(history.getContent().get(0).id()).isEqualTo(afterRestart.latestRunId());
        assertThat(history.getContent().get(1).id()).isEqualTo(afterFirstStart.latestRunId());
    }

    @Test
    void complete_beforeMostRecentRunTerminal_throwsConflict() {
        GitRepo r = makeRepo("https://github.com/test/task-complete-blocked.git");
        StoryResponse story = makeStory(r.getId());
        TaskResponse task = service.create(story.id(), new TaskRequest("T", "D"));
        service.start(task.id());

        assertThatThrownBy(() -> service.complete(task.id())).isInstanceOf(ConflictException.class);
    }

    @Test
    void complete_afterMostRecentRunTerminal_transitionsToDone() {
        GitRepo r = makeRepo("https://github.com/test/task-complete-ok.git");
        StoryResponse story = makeStory(r.getId());
        TaskResponse task = service.create(story.id(), new TaskRequest("T", "D"));
        TaskResponse started = service.start(task.id());
        markRunTerminal(started.latestRunId(), WorkflowRunStatus.completed);

        TaskResponse completed = service.complete(task.id());

        assertThat(completed.status()).isEqualTo("done");
    }

    @Test
    void complete_backlogTask_throwsConflict() {
        GitRepo r = makeRepo("https://github.com/test/task-complete-backlog.git");
        StoryResponse story = makeStory(r.getId());
        TaskResponse task = service.create(story.id(), new TaskRequest("T", "D"));

        assertThatThrownBy(() -> service.complete(task.id())).isInstanceOf(ConflictException.class);
    }

    @Test
    void listRuns_emptyHistory_returnsEmptyPage() {
        GitRepo r = makeRepo("https://github.com/test/task-empty-history.git");
        StoryResponse story = makeStory(r.getId());
        TaskResponse task = service.create(story.id(), new TaskRequest("T", "D"));

        Page<com.choruskube.core.dto.RunSummary> history = service.listRuns(task.id(), PageRequest.of(0, 20));
        assertThat(history.getContent()).isEmpty();
    }

    private void markRunTerminal(UUID runId, WorkflowRunStatus status) {
        WorkflowRun run = runRepo.findById(runId).orElseThrow();
        run.setStatus(status);
        runRepo.saveAndFlush(run);
    }

    private StoryResponse makeStory(UUID softwareProjectId) {
        EpicResponse epic = epicService.create(new EpicRequest("Epic", "Epic desc", null, softwareProjectId), null);
        return storyService.create(epic.id(), new StoryRequest("Story", "Story desc"));
    }

    private GitRepo makeRepo(String url) {
        GitRepo r = new GitRepo();
        r.setUrl(url);
        r.setName(RepoNameUtil.deriveOwnerRepoName(url));
        return gitRepoRepo.save(r);
    }
}
