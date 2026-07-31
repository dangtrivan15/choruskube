package com.choruskube.core.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.choruskube.core.BaseTest;
import com.choruskube.core.dto.CreateDependencyRequest;
import com.choruskube.core.dto.EpicRequest;
import com.choruskube.core.dto.EpicResponse;
import com.choruskube.core.dto.StoryRequest;
import com.choruskube.core.dto.StoryResponse;
import com.choruskube.core.dto.TaskRequest;
import com.choruskube.core.dto.TaskResponse;
import com.choruskube.core.model.GitRepo;
import com.choruskube.core.model.WorkflowRun;
import com.choruskube.core.model.enums.WorkflowRunStatus;
import com.choruskube.core.repository.GitRepoRepository;
import com.choruskube.core.repository.WorkflowRunRepository;
import com.choruskube.core.service.EpicService;
import com.choruskube.core.service.RunEventPublisher;
import com.choruskube.core.service.StoryService;
import com.choruskube.core.service.TaskService;
import com.choruskube.core.service.WorkItemDependencyService;
import com.choruskube.core.util.RepoNameUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import io.temporal.serviceclient.WorkflowServiceStubs;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

@AutoConfigureMockMvc
@Transactional
public class TaskControllerTest extends BaseTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private GitRepoRepository gitRepoRepo;

    @Autowired
    private WorkflowRunRepository runRepo;

    @Autowired
    private EpicService epicService;

    @Autowired
    private StoryService storyService;

    @Autowired
    private TaskService taskService;

    @Autowired
    private WorkItemDependencyService dependencyService;

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
    void createTask_returns201() throws Exception {
        StoryResponse story = makeStory("https://github.com/test/task-create.git");

        var body = Map.of("title", "Task title", "description", "Task desc");

        mockMvc.perform(post("/api/v1/stories/" + story.id() + "/tasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.storyId").value(story.id().toString()))
                .andExpect(jsonPath("$.title").value("Task title"))
                .andExpect(jsonPath("$.status").value("backlog"));
    }

    @Test
    void createTask_underUnknownStory_returns404() throws Exception {
        var body = Map.of("title", "T", "description", "D");

        mockMvc.perform(post("/api/v1/stories/" + UUID.randomUUID() + "/tasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isNotFound());
    }

    @Test
    void listTasks_returnsTasksForStory() throws Exception {
        StoryResponse story = makeStory("https://github.com/test/task-list.git");
        taskService.create(story.id(), new TaskRequest("T1", "D"));
        taskService.create(story.id(), new TaskRequest("T2", "D"));

        mockMvc.perform(get("/api/v1/stories/" + story.id() + "/tasks"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));
    }

    @Test
    void listTasks_blockedByUnfinishedDependency_readinessIsBlocked() throws Exception {
        // Decision 1: the flat list endpoint now populates the same `readiness` field the
        // Roadmap Graph View has always computed, instead of leaving it null.
        StoryResponse story = makeStory("https://github.com/test/task-list-readiness.git");
        TaskResponse blocking = taskService.create(story.id(), new TaskRequest("Blocking", "D"));
        TaskResponse blocked = taskService.create(story.id(), new TaskRequest("Blocked", "D"));
        dependencyService.create(new CreateDependencyRequest("task", blocking.id(), "task", blocked.id()));

        mockMvc.perform(get("/api/v1/stories/" + story.id() + "/tasks"))
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath("$[?(@.id=='" + blocked.id() + "')].readiness").value("BLOCKED"))
                .andExpect(jsonPath("$[?(@.id=='" + blocking.id() + "')].readiness")
                        .value("READY"));
    }

    @Test
    void getTask_returnsTask() throws Exception {
        StoryResponse story = makeStory("https://github.com/test/task-get.git");
        TaskResponse task = taskService.create(story.id(), new TaskRequest("My Task", "D"));

        mockMvc.perform(get("/api/v1/tasks/" + task.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("My Task"));
    }

    @Test
    void getTask_notFound_returns404() throws Exception {
        mockMvc.perform(get("/api/v1/tasks/" + UUID.randomUUID())).andExpect(status().isNotFound());
    }

    @Test
    void updateTask_inBacklog_returns200() throws Exception {
        StoryResponse story = makeStory("https://github.com/test/task-update.git");
        TaskResponse task = taskService.create(story.id(), new TaskRequest("Old", "D"));

        var body = Map.of("title", "New", "description", "New Desc");

        mockMvc.perform(put("/api/v1/tasks/" + task.id())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("New"));
    }

    @Test
    void updateTask_notInBacklog_returns409() throws Exception {
        StoryResponse story = makeStory("https://github.com/test/task-update-conflict.git");
        TaskResponse task = taskService.create(story.id(), new TaskRequest("T", "D"));
        taskService.start(task.id());

        var body = Map.of("title", "New", "description", "New Desc");

        mockMvc.perform(put("/api/v1/tasks/" + task.id())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isConflict());
    }

    @Test
    void deleteTask_inBacklog_returns204() throws Exception {
        StoryResponse story = makeStory("https://github.com/test/task-delete.git");
        TaskResponse task = taskService.create(story.id(), new TaskRequest("To Delete", "D"));

        mockMvc.perform(delete("/api/v1/tasks/" + task.id())).andExpect(status().isNoContent());

        mockMvc.perform(get("/api/v1/tasks/" + task.id())).andExpect(status().isNotFound());
    }

    @Test
    void deleteTask_notInBacklog_returns409() throws Exception {
        StoryResponse story = makeStory("https://github.com/test/task-delete-conflict.git");
        TaskResponse task = taskService.create(story.id(), new TaskRequest("T", "D"));
        taskService.start(task.id());

        mockMvc.perform(delete("/api/v1/tasks/" + task.id())).andExpect(status().isConflict());
    }

    @Test
    void startTask_returns200_andCreatesRun() throws Exception {
        StoryResponse story = makeStory("https://github.com/test/task-start.git");
        TaskResponse task = taskService.create(story.id(), new TaskRequest("T", "D"));

        mockMvc.perform(post("/api/v1/tasks/" + task.id() + "/start"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("in_progress"))
                .andExpect(jsonPath("$.latestRunId").isNotEmpty());
    }

    @Test
    void startTask_withActiveRun_returns409() throws Exception {
        StoryResponse story = makeStory("https://github.com/test/task-start-conflict.git");
        TaskResponse task = taskService.create(story.id(), new TaskRequest("T", "D"));
        taskService.start(task.id());

        mockMvc.perform(post("/api/v1/tasks/" + task.id() + "/start")).andExpect(status().isConflict());
    }

    @Test
    void completeTask_withTerminalRun_returns200() throws Exception {
        StoryResponse story = makeStory("https://github.com/test/task-complete.git");
        TaskResponse task = taskService.create(story.id(), new TaskRequest("T", "D"));
        TaskResponse started = taskService.start(task.id());
        WorkflowRun run = runRepo.findById(started.latestRunId()).orElseThrow();
        run.setStatus(WorkflowRunStatus.completed);
        runRepo.save(run);

        mockMvc.perform(patch("/api/v1/tasks/" + task.id() + "/complete"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("done"));
    }

    @Test
    void completeTask_withActiveRun_returns409() throws Exception {
        StoryResponse story = makeStory("https://github.com/test/task-complete-conflict.git");
        TaskResponse task = taskService.create(story.id(), new TaskRequest("T", "D"));
        taskService.start(task.id());

        mockMvc.perform(patch("/api/v1/tasks/" + task.id() + "/complete")).andExpect(status().isConflict());
    }

    @Test
    void listRuns_returnsHistory() throws Exception {
        StoryResponse story = makeStory("https://github.com/test/task-runs.git");
        TaskResponse task = taskService.create(story.id(), new TaskRequest("T", "D"));
        taskService.start(task.id());

        mockMvc.perform(get("/api/v1/tasks/" + task.id() + "/runs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1));
    }

    // --- helpers ---

    private StoryResponse makeStory(String url) {
        GitRepo r = new GitRepo();
        r.setUrl(url);
        r.setName(RepoNameUtil.deriveOwnerRepoName(url));
        r = gitRepoRepo.save(r);
        EpicResponse epic = epicService.create(new EpicRequest("Epic", "Epic desc", null, r.getId()), null);
        return storyService.create(epic.id(), new StoryRequest("Story", "Story desc"));
    }
}
