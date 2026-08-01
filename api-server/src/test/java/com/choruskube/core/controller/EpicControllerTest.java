package com.choruskube.core.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.choruskube.core.BaseTest;
import com.choruskube.core.config.OrgSecurity;
import com.choruskube.core.dto.CreateDependencyRequest;
import com.choruskube.core.dto.StoryRequest;
import com.choruskube.core.dto.TaskRequest;
import com.choruskube.core.model.Epic;
import com.choruskube.core.model.GitRepo;
import com.choruskube.core.model.RepoGroup;
import com.choruskube.core.model.Task;
import com.choruskube.core.model.enums.WorkItemStatus;
import com.choruskube.core.repository.EpicRepository;
import com.choruskube.core.repository.GitRepoRepository;
import com.choruskube.core.repository.TaskRepository;
import com.choruskube.core.service.EpicService;
import com.choruskube.core.service.RepoGroupService;
import com.choruskube.core.service.RunEventPublisher;
import com.choruskube.core.service.StoryService;
import com.choruskube.core.service.TaskService;
import com.choruskube.core.service.WorkItemDependencyService;
import com.choruskube.core.util.RepoNameUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.temporal.client.WorkflowClient;
import io.temporal.serviceclient.WorkflowServiceStubs;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

@AutoConfigureMockMvc
@Transactional
public class EpicControllerTest extends BaseTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private GitRepoRepository gitRepoRepo;

    @Autowired
    private EpicRepository epicRepo;

    @Autowired
    private EpicService epicService;

    @Autowired
    private RepoGroupService repoGroupService;

    @Autowired
    private StoryService storyService;

    @Autowired
    private TaskService taskService;

    @Autowired
    private TaskRepository taskRepo;

    @Autowired
    private WorkItemDependencyService dependencyService;

    @MockitoBean
    private WorkflowServiceStubs workflowServiceStubs;

    @MockitoBean
    private WorkflowClient workflowClient;

    @MockitoBean
    private RunEventPublisher runEventPublisher;

    @MockitoBean
    private OrgSecurity orgSecurity;

    @BeforeEach
    void allowAllByDefault() {
        // Mirrors NoOpOrgSecurity (the un-mocked default): every level of access is allowed unless
        // an individual test overrides it, so the permission mock doesn't break the other tests in
        // this class.
        when(orgSecurity.canRead()).thenReturn(true);
        when(orgSecurity.canOperate()).thenReturn(true);
        when(orgSecurity.canAdmin()).thenReturn(true);
        when(orgSecurity.isPlatformAdmin()).thenReturn(true);
    }

    @Test
    void createEpic_returns201_withSoftwareProjectIdShape() throws Exception {
        GitRepo repo = createGitRepo("https://github.com/test/repo.git");

        var body = Map.of(
                "title", "Add login page",
                "description", "Build a login page with OAuth",
                "motivation", "Users need to authenticate",
                "softwareProjectId", repo.getId());

        mockMvc.perform(post("/api/v1/epics")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.title").value("Add login page"))
                .andExpect(jsonPath("$.status").value("backlog"))
                .andExpect(jsonPath("$.progress.totalTasks").value(0))
                .andExpect(jsonPath("$.softwareProject.id").value(repo.getId().toString()))
                .andExpect(jsonPath("$.softwareProject.type").value("git_repo"))
                .andExpect(jsonPath("$.repos.length()").value(1))
                // No Stories/Tasks yet: nothing to start.
                .andExpect(jsonPath("$.readyToStart").value(false));
    }

    @Test
    void createEpic_withRepoGroupTarget_returnsRepoGroupTypeAndResolvedRepos() throws Exception {
        GitRepo r1 = createGitRepo("https://github.com/test/group-r1.git");
        GitRepo r2 = createGitRepo("https://github.com/test/group-r2.git");
        RepoGroup group = createRepoGroup("grp-" + UUID.randomUUID().toString().substring(0, 8), r1, r2);

        var body =
                Map.of("title", "Two-repo feature", "description", "Backend + UI", "softwareProjectId", group.getId());

        mockMvc.perform(post("/api/v1/epics")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.softwareProject.type").value("repo_group"))
                .andExpect(jsonPath("$.repos.length()").value(2));
    }

    @Test
    void createEpic_withMissingSoftwareProjectId_returns400() throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("title", "No project");
        body.put("description", "softwareProjectId required");

        mockMvc.perform(post("/api/v1/epics")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void createEpic_withInvalidSoftwareProjectId_returns404() throws Exception {
        var body = Map.of(
                "title", "Add login page", "description", "Build a login page", "softwareProjectId", UUID.randomUUID());

        mockMvc.perform(post("/api/v1/epics")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isNotFound());
    }

    @Test
    void listEpics_returnsAll() throws Exception {
        GitRepo repo = createGitRepo("https://github.com/test/list-all.git");
        createEpic(repo, "Epic A");
        createEpic(repo, "Epic B");

        mockMvc.perform(get("/api/v1/epics"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(2))
                .andExpect(jsonPath("$.totalElements").value(2));
    }

    @Test
    void listEpics_unfilteredRequest_includesReadyToStartOnEveryEpic() throws Exception {
        GitRepo repo = createGitRepo("https://github.com/test/list-includes-field.git");
        Epic ready = createEpic(repo, "Ready Epic");
        var story = storyService.create(ready.getId(), new StoryRequest("S", "D"));
        taskService.create(story.id(), new TaskRequest("T", "D"));
        createEpic(repo, "Empty Epic");

        mockMvc.perform(get("/api/v1/epics"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[?(@.id == '" + ready.getId() + "')].readyToStart")
                        .value(true))
                .andExpect(jsonPath("$.content[?(@.title == 'Empty Epic')].readyToStart")
                        .value(false));
    }

    @Test
    void listEpics_readyToStartFilter_excludesDoneInProgressAndBlockedEpics() throws Exception {
        GitRepo repo = createGitRepo("https://github.com/test/list-ready-filter.git");

        Epic readyEpic = createEpic(repo, "Ready Epic");
        var readyStory = storyService.create(readyEpic.getId(), new StoryRequest("S", "D"));
        taskService.create(readyStory.id(), new TaskRequest("T", "D"));

        Epic doneEpic = createEpic(repo, "Done Epic");
        var doneStory = storyService.create(doneEpic.getId(), new StoryRequest("S", "D"));
        var doneTask = taskService.create(doneStory.id(), new TaskRequest("T", "D"));
        Task dt = taskRepo.findById(doneTask.id()).orElseThrow();
        dt.setStatus(WorkItemStatus.done);
        taskRepo.saveAndFlush(dt);

        // The Story's own rollup status must leave "backlog" too (not just the blocked Task),
        // otherwise the Story itself — having no blocking edge of its own — would independently
        // count as ready-to-start work regardless of the Task-level block (Decision 2 treats
        // Story/Task readiness independently). Task A started (in_progress) trips the Story's
        // rollup to "in_progress" while itself not counting toward readyToStart (not backlog);
        // Task A being not-done is what keeps Task C (blocked transitively through done Task B)
        // BLOCKED — mirrors the multi-hop chain regression this feature must not reintroduce.
        Epic blockedEpic = createEpic(repo, "Blocked Epic");
        var blockedStory = storyService.create(blockedEpic.getId(), new StoryRequest("S", "D"));
        var taskA = taskService.create(blockedStory.id(), new TaskRequest("A", "D"));
        var taskB = taskService.create(blockedStory.id(), new TaskRequest("B", "D"));
        var taskC = taskService.create(blockedStory.id(), new TaskRequest("C", "D"));
        dependencyService.create(new CreateDependencyRequest("task", taskA.id(), "task", taskB.id()));
        dependencyService.create(new CreateDependencyRequest("task", taskB.id(), "task", taskC.id()));
        Task ta = taskRepo.findById(taskA.id()).orElseThrow();
        ta.setStatus(WorkItemStatus.in_progress);
        taskRepo.saveAndFlush(ta);
        Task tb = taskRepo.findById(taskB.id()).orElseThrow();
        tb.setStatus(WorkItemStatus.done);
        taskRepo.saveAndFlush(tb);

        mockMvc.perform(get("/api/v1/epics").param("readyToStart", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[?(@.id == '" + readyEpic.getId() + "')]")
                        .isNotEmpty())
                .andExpect(jsonPath("$.content[?(@.id == '" + doneEpic.getId() + "')]")
                        .isEmpty())
                .andExpect(jsonPath("$.content[?(@.id == '" + blockedEpic.getId() + "')]")
                        .isEmpty());
    }

    @Test
    void listEpics_readyToStartFilter_paginationReflectsFilteredCount() throws Exception {
        GitRepo repo = createGitRepo("https://github.com/test/list-ready-pagination.git");
        Epic readyEpic = createEpic(repo, "Only Ready Epic");
        var story = storyService.create(readyEpic.getId(), new StoryRequest("S", "D"));
        taskService.create(story.id(), new TaskRequest("T", "D"));
        createEpic(repo, "Empty Epic 1");
        createEpic(repo, "Empty Epic 2");

        mockMvc.perform(get("/api/v1/epics").param("readyToStart", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.totalPages").value(1));
    }

    @Test
    void listEpics_readyToStartFilter_noMatches_returnsEmptyPageWithZeroPagination() throws Exception {
        GitRepo repo = createGitRepo("https://github.com/test/list-ready-empty.git");
        createEpic(repo, "Never Ready Epic");

        mockMvc.perform(get("/api/v1/epics").param("readyToStart", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(0))
                .andExpect(jsonPath("$.totalElements").value(0))
                .andExpect(jsonPath("$.totalPages").value(0));
    }

    @Test
    void getEpic_returnsEpic() throws Exception {
        GitRepo repo = createGitRepo("https://github.com/test/get.git");
        Epic e = createEpic(repo, "My Feature");
        var story = storyService.create(e.getId(), new StoryRequest("S", "D"));
        taskService.create(story.id(), new TaskRequest("T", "D"));

        mockMvc.perform(get("/api/v1/epics/" + e.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("My Feature"))
                .andExpect(jsonPath("$.status").value("backlog"))
                // Computed the same way as the list path (Decision 3), not a stale/default value.
                .andExpect(jsonPath("$.readyToStart").value(true));
    }

    @Test
    void getEpic_notFound_returns404() throws Exception {
        mockMvc.perform(get("/api/v1/epics/" + UUID.randomUUID())).andExpect(status().isNotFound());
    }

    @Test
    void updateEpic_returns200() throws Exception {
        GitRepo repo = createGitRepo("https://github.com/test/update.git");
        Epic e = createEpic(repo, "Old Title");
        var story = storyService.create(e.getId(), new StoryRequest("S", "D"));
        taskService.create(story.id(), new TaskRequest("T", "D"));

        var body =
                Map.of("title", "New Title", "description", "Updated description", "softwareProjectId", repo.getId());

        mockMvc.perform(put("/api/v1/epics/" + e.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("New Title"))
                // Accurate, not a stale/default value, on the PUT response too (Decision 3).
                .andExpect(jsonPath("$.readyToStart").value(true));
    }

    @Test
    void deleteEpic_returns204() throws Exception {
        GitRepo repo = createGitRepo("https://github.com/test/delete.git");
        Epic e = createEpic(repo, "To Delete");

        mockMvc.perform(delete("/api/v1/epics/" + e.getId())).andExpect(status().isNoContent());

        mockMvc.perform(get("/api/v1/epics/" + e.getId())).andExpect(status().isNotFound());
    }

    @Test
    void createEpic_publishesRoadmapItemChangedEvent() throws Exception {
        GitRepo repo = createGitRepo("https://github.com/test/event-create.git");

        var body = Map.of(
                "title",
                "Add login page",
                "description",
                "Build a login page with OAuth",
                "softwareProjectId",
                repo.getId());

        mockMvc.perform(post("/api/v1/epics")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isCreated());

        verify(runEventPublisher).publishRoadmapItemChanged(eq("epic"), any(UUID.class), eq("backlog"));
    }

    @Test
    void deleteEpic_publishesRoadmapItemChangedEvent() throws Exception {
        GitRepo repo = createGitRepo("https://github.com/test/event-delete.git");
        Epic e = createEpic(repo, "To Delete");

        mockMvc.perform(delete("/api/v1/epics/" + e.getId())).andExpect(status().isNoContent());

        verify(runEventPublisher).publishRoadmapItemChanged(eq("epic"), eq(e.getId()), eq("deleted"));
    }

    // --- PATCH /epics/{id}/stage ---

    @ParameterizedTest
    @ValueSource(strings = {"backlog", "in_progress", "rolled_out"})
    void updateStage_withValidStage_returns200WithUpdatedStage(String stage) throws Exception {
        GitRepo repo = createGitRepo("https://github.com/test/stage-" + stage + ".git");
        Epic e = createEpic(repo, "Stage Epic " + stage);

        mockMvc.perform(patch("/api/v1/epics/" + e.getId() + "/stage")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("stage", stage))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stage").value(stage));
    }

    @Test
    void updateStage_withSyntacticallyUnknownStage_returns400() throws Exception {
        GitRepo repo = createGitRepo("https://github.com/test/stage-unknown.git");
        Epic e = createEpic(repo, "Stage Epic Unknown");

        mockMvc.perform(patch("/api/v1/epics/" + e.getId() + "/stage")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("stage", "not_a_real_stage"))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void updateStage_withDoneValue_returns400() throws Exception {
        // "done" is a syntactically valid WorkItemStatus (so Jackson deserializes it fine) but is
        // not a valid board stage — this is a distinct, service-layer rejection from the Jackson
        // enum-deserialization failure above.
        GitRepo repo = createGitRepo("https://github.com/test/stage-done.git");
        Epic e = createEpic(repo, "Stage Epic Done");

        mockMvc.perform(patch("/api/v1/epics/" + e.getId() + "/stage")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("stage", "done"))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void updateStage_onNonexistentEpic_returns404() throws Exception {
        mockMvc.perform(patch("/api/v1/epics/" + UUID.randomUUID() + "/stage")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("stage", "in_progress"))))
                .andExpect(status().isNotFound());
    }

    @Test
    void updateStage_withStartedDescendantTask_succeeds() throws Exception {
        // Proves the "no edit once started" guard used by the full PUT edit endpoint does NOT
        // apply to stage moves.
        GitRepo repo = createGitRepo("https://github.com/test/stage-started-task.git");
        Epic e = createEpic(repo, "Stage Epic Started Task");
        var story = storyService.create(e.getId(), new StoryRequest("S", "D"));
        var task = taskService.create(story.id(), new TaskRequest("T", "D"));
        Task t = taskRepo.findById(task.id()).orElseThrow();
        t.setStatus(WorkItemStatus.in_progress);
        taskRepo.saveAndFlush(t);

        mockMvc.perform(patch("/api/v1/epics/" + e.getId() + "/stage")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("stage", "rolled_out"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stage").value("rolled_out"));
    }

    @Test
    void updateStage_belowCanOperatePermission_returns403() throws Exception {
        when(orgSecurity.canOperate()).thenReturn(false);

        GitRepo repo = createGitRepo("https://github.com/test/stage-forbidden.git");
        Epic e = createEpic(repo, "Stage Epic Forbidden");

        mockMvc.perform(patch("/api/v1/epics/" + e.getId() + "/stage")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("stage", "in_progress"))))
                .andExpect(status().isForbidden());
    }

    // --- Test helpers ---

    private GitRepo createGitRepo(String url) {
        GitRepo repo = new GitRepo();
        repo.setUrl(url);
        repo.setName(RepoNameUtil.deriveOwnerRepoName(url));
        return gitRepoRepo.save(repo);
    }

    private RepoGroup createRepoGroup(String name, GitRepo... members) {
        List<UUID> ids = java.util.Arrays.stream(members).map(GitRepo::getId).toList();
        return repoGroupService.create(name, null, null, ids);
    }

    /** Creates a backlog Epic targeting {@code repo}'s software_project id. */
    private Epic createEpic(GitRepo repo, String title) {
        var response = epicService.create(
                new com.choruskube.core.dto.EpicRequest(title, "Description for " + title, null, repo.getId()), null);
        return epicRepo.findById(response.id()).orElseThrow();
    }
}
