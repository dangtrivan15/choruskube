package com.choruskube.core.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.choruskube.core.BaseTest;
import com.choruskube.core.dto.EpicRequest;
import com.choruskube.core.dto.EpicResponse;
import com.choruskube.core.dto.StoryRequest;
import com.choruskube.core.dto.StoryResponse;
import com.choruskube.core.dto.TaskRequest;
import com.choruskube.core.exception.ConflictException;
import com.choruskube.core.exception.ForbiddenException;
import com.choruskube.core.exception.NotFoundException;
import com.choruskube.core.model.GitRepo;
import com.choruskube.core.model.Task;
import com.choruskube.core.model.enums.WorkItemStatus;
import com.choruskube.core.repository.GitRepoRepository;
import com.choruskube.core.repository.TaskRepository;
import com.choruskube.core.util.RepoNameUtil;
import io.temporal.client.WorkflowClient;
import io.temporal.serviceclient.WorkflowServiceStubs;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

@Transactional
public class DefaultStoryServiceTest extends BaseTest {

    @Autowired
    private StoryService service;

    @Autowired
    private EpicService epicService;

    @Autowired
    private TaskService taskService;

    @Autowired
    private GitRepoRepository gitRepoRepo;

    @Autowired
    private TaskRepository taskRepo;

    @MockitoBean
    private WorkflowServiceStubs workflowServiceStubs;

    @MockitoBean
    private WorkflowClient workflowClient;

    @MockitoBean
    private RunEventPublisher runEventPublisher;

    @Test
    void create_underEpic_returnsStoryWithEpicId() {
        EpicResponse epic = makeEpic("https://github.com/test/story-one.git");

        StoryResponse story = service.create(epic.id(), new StoryRequest("Story title", "Story desc"));

        assertThat(story.epicId()).isEqualTo(epic.id());
        assertThat(story.title()).isEqualTo("Story title");
        assertThat(story.status()).isEqualTo("backlog");
        assertThat(story.progress().totalTasks()).isZero();
    }

    @Test
    void create_underUnknownEpic_throwsNotFound() {
        UUID unknown = UUID.randomUUID();
        assertThatThrownBy(() -> service.create(unknown, new StoryRequest("T", "D")))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining(unknown.toString());
    }

    @Test
    void create_withRunId_underEpicInRunsSoftwareProject_succeeds() {
        GitRepo r = makeRepo("https://github.com/test/story-runid-proj-ok.git");
        EpicResponse epic = epicService.create(new EpicRequest("Epic", "Epic desc", null, r.getId()), null);

        StoryResponse story = service.create(epic.id(), new StoryRequest("S", "D"), UUID.randomUUID(), r.getId());

        assertThat(story.epicId()).isEqualTo(epic.id());
        assertThat(story.title()).isEqualTo("S");
    }

    @Test
    void create_withRunId_underEpicOutsideRunsSoftwareProject_throwsForbidden() {
        GitRepo r1 = makeRepo("https://github.com/test/story-runid-proj-a.git");
        GitRepo r2 = makeRepo("https://github.com/test/story-runid-proj-b.git");
        EpicResponse epic = epicService.create(new EpicRequest("Epic", "Epic desc", null, r1.getId()), null);

        // r2 is a real, different SoftwareProject in the same (only, OSS single-tenant) org — the
        // run's resolved software_project_id must still match the Epic's own, or the Story would
        // silently attach to an Epic outside the run's actual target project.
        assertThatThrownBy(() -> service.create(epic.id(), new StoryRequest("S", "D"), UUID.randomUUID(), r2.getId()))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    void list_returnsStoriesForEpicNewestFirst() throws InterruptedException {
        EpicResponse epic = makeEpic("https://github.com/test/story-list.git");
        StoryResponse older = service.create(epic.id(), new StoryRequest("Older", "D"));
        Thread.sleep(5);
        StoryResponse newer = service.create(epic.id(), new StoryRequest("Newer", "D"));

        List<StoryResponse> result = service.list(epic.id());
        assertThat(result).extracting(StoryResponse::id).containsExactly(newer.id(), older.id());
    }

    @Test
    void update_replacesFields() {
        EpicResponse epic = makeEpic("https://github.com/test/story-update.git");
        StoryResponse story = service.create(epic.id(), new StoryRequest("Orig", "Desc"));

        StoryResponse updated = service.update(story.id(), new StoryRequest("New", "New Desc"));

        assertThat(updated.title()).isEqualTo("New");
        assertThat(updated.description()).isEqualTo("New Desc");
    }

    @Test
    void update_withStartedTask_throwsConflict() {
        EpicResponse epic = makeEpic("https://github.com/test/story-update-blocked.git");
        StoryResponse story = service.create(epic.id(), new StoryRequest("S", "D"));
        var task = taskService.create(story.id(), new TaskRequest("T", "D"));
        markTaskInProgress(task.id());

        assertThatThrownBy(() -> service.update(story.id(), new StoryRequest("New", "D")))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void delete_withStartedTask_throwsConflict() {
        EpicResponse epic = makeEpic("https://github.com/test/story-delete-blocked.git");
        StoryResponse story = service.create(epic.id(), new StoryRequest("S", "D"));
        var task = taskService.create(story.id(), new TaskRequest("T", "D"));
        markTaskInProgress(task.id());

        assertThatThrownBy(() -> service.delete(story.id())).isInstanceOf(ConflictException.class);
    }

    @Test
    void delete_withNoStartedTasks_succeeds() {
        EpicResponse epic = makeEpic("https://github.com/test/story-delete-ok.git");
        StoryResponse story = service.create(epic.id(), new StoryRequest("S", "D"));

        service.delete(story.id());

        assertThatThrownBy(() -> service.get(story.id())).isInstanceOf(NotFoundException.class);
    }

    @Test
    void rollup_allTasksDone_statusIsDone() {
        EpicResponse epic = makeEpic("https://github.com/test/story-rollup-done.git");
        StoryResponse story = service.create(epic.id(), new StoryRequest("S", "D"));
        var task = taskService.create(story.id(), new TaskRequest("T", "D"));
        markTaskDone(task.id());

        StoryResponse fetched = service.get(story.id());
        assertThat(fetched.status()).isEqualTo("done");
        assertThat(fetched.progress().doneTasks()).isEqualTo(1);
    }

    @Test
    void rollup_emptyStory_statusIsBacklog() {
        EpicResponse epic = makeEpic("https://github.com/test/story-rollup-empty.git");
        StoryResponse story = service.create(epic.id(), new StoryRequest("S", "D"));

        StoryResponse fetched = service.get(story.id());
        assertThat(fetched.status()).isEqualTo("backlog");
    }

    private void markTaskInProgress(UUID taskId) {
        Task t = taskRepo.findById(taskId).orElseThrow();
        t.setStatus(WorkItemStatus.in_progress);
        taskRepo.saveAndFlush(t);
    }

    private void markTaskDone(UUID taskId) {
        Task t = taskRepo.findById(taskId).orElseThrow();
        t.setStatus(WorkItemStatus.done);
        taskRepo.saveAndFlush(t);
    }

    private EpicResponse makeEpic(String url) {
        GitRepo r = makeRepo(url);
        return epicService.create(new EpicRequest("Epic", "Epic desc", null, r.getId()), null);
    }

    private GitRepo makeRepo(String url) {
        GitRepo r = new GitRepo();
        r.setUrl(url);
        r.setName(RepoNameUtil.deriveOwnerRepoName(url));
        return gitRepoRepo.save(r);
    }
}
