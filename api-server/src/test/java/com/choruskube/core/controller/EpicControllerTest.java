package com.choruskube.core.controller;

import static org.junit.jupiter.api.Assertions.assertTrue;
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
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.temporal.client.WorkflowClient;
import io.temporal.serviceclient.WorkflowServiceStubs;
import java.util.Collections;
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
import org.springframework.test.web.servlet.MvcResult;
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
                .andExpect(jsonPath("$.stage").value("backlog"))
                .andExpect(jsonPath("$.progress.totalTasks").value(0))
                .andExpect(jsonPath("$.softwareProject.id").value(repo.getId().toString()))
                .andExpect(jsonPath("$.softwareProject.type").value("git_repo"))
                .andExpect(jsonPath("$.repos.length()").value(1));
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

    // --- GET /epics?readiness= ("ready to start" roadmap filter) ---

    @Test
    void listEpics_readyFilter_returnsOnlyEpicsWithReadyDescendants() throws Exception {
        GitRepo repo = createGitRepo("https://github.com/test/ready-filter.git");
        Epic readyEpic = createEpic(repo, "Ready Epic");
        var unblockedStory = storyService.create(readyEpic.getId(), new StoryRequest("Unblocked", "D"));
        taskService.create(unblockedStory.id(), new TaskRequest("T", "D")); // no incoming edge -> READY

        Epic blockedEpic = createEpic(repo, "Blocked Epic");
        var blockedStory = storyService.create(blockedEpic.getId(), new StoryRequest("Blocked", "D"));
        taskService.create(blockedStory.id(), new TaskRequest("T", "D"));
        Epic blockerEpic = createEpic(repo, "Blocker Owner Epic");
        var blockerStory = storyService.create(blockerEpic.getId(), new StoryRequest("Blocker", "D"));
        dependencyService.create(new CreateDependencyRequest("story", blockerStory.id(), "story", blockedStory.id()));

        mockMvc.perform(get("/api/v1/epics").param("readiness", "READY"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[?(@.id == '" + readyEpic.getId() + "')]")
                        .exists())
                .andExpect(jsonPath("$.content[?(@.id == '" + blockedEpic.getId() + "')]")
                        .doesNotExist());
    }

    @Test
    void listEpics_readyFilter_pagesCorrectly() throws Exception {
        GitRepo repo = createGitRepo("https://github.com/test/ready-filter-paging.git");
        for (int i = 0; i < 3; i++) {
            Epic epic = createEpic(repo, "Ready Epic " + i);
            var story = storyService.create(epic.getId(), new StoryRequest("S" + i, "D"));
            taskService.create(story.id(), new TaskRequest("T" + i, "D"));
        }

        mockMvc.perform(get("/api/v1/epics").param("readiness", "READY").param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(2))
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.totalPages").value(2));
    }

    @Test
    void listEpics_unsupportedReadinessValue_returns400() throws Exception {
        mockMvc.perform(get("/api/v1/epics").param("readiness", "not_a_real_readiness"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void listEpics_blockedReadinessValue_returnsEmptyPage_notUnfilteredFallback() throws Exception {
        // BLOCKED is a syntactically valid Readiness enum value, so it doesn't hit the 400 path
        // above — it must instead be treated as "no candidates match", not silently
        // fall back to the unfiltered page. Seed an Epic that would appear in both the unfiltered
        // list and the READY-filtered list, to prove ?readiness=BLOCKED excludes it too.
        GitRepo repo = createGitRepo("https://github.com/test/ready-filter-blocked-value.git");
        Epic readyEpic = createEpic(repo, "Ready Epic For Blocked Value Filter");
        var unblockedStory = storyService.create(readyEpic.getId(), new StoryRequest("Unblocked", "D"));
        taskService.create(unblockedStory.id(), new TaskRequest("T", "D"));

        mockMvc.perform(get("/api/v1/epics").param("readiness", "BLOCKED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(0))
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    void listEpics_noFilter_includesReadyItemCountOnEveryEpic() throws Exception {
        GitRepo repo = createGitRepo("https://github.com/test/ready-count.git");
        Epic readyEpic = createEpic(repo, "Ready Count Epic");
        var unblockedStory = storyService.create(readyEpic.getId(), new StoryRequest("Unblocked", "D"));
        taskService.create(unblockedStory.id(), new TaskRequest("T", "D"));

        Epic blockedEpic = createEpic(repo, "Blocked Count Epic");
        var blockedStory = storyService.create(blockedEpic.getId(), new StoryRequest("Blocked", "D"));
        taskService.create(blockedStory.id(), new TaskRequest("T", "D"));
        Epic blockerEpic = createEpic(repo, "Blocker Count Owner Epic");
        var blockerStory = storyService.create(blockerEpic.getId(), new StoryRequest("Blocker", "D"));
        dependencyService.create(new CreateDependencyRequest("story", blockerStory.id(), "story", blockedStory.id()));

        mockMvc.perform(get("/api/v1/epics"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[?(@.id == '" + readyEpic.getId() + "')].readyItemCount")
                        .value(1))
                .andExpect(jsonPath("$.content[?(@.id == '" + blockedEpic.getId() + "')].readyItemCount")
                        .value(0));
    }

    @Test
    void listEpics_readyFilter_excludesEpicWhoseTasksAreAllDone() throws Exception {
        // Nothing blocks a finished Epic, so it stays READY in the dependency sense — it drops
        // out only because "ready" also means "not started yet".
        GitRepo repo = createGitRepo("https://github.com/test/ready-filter-all-done.git");
        Epic doneEpic = createEpic(repo, "Done Epic");
        var doneStory = storyService.create(doneEpic.getId(), new StoryRequest("Shipped", "D"));
        markTaskDone(
                taskService.create(doneStory.id(), new TaskRequest("T", "D")).id());

        Epic openEpic = createEpic(repo, "Open Epic");
        var openStory = storyService.create(openEpic.getId(), new StoryRequest("Pending", "D"));
        taskService.create(openStory.id(), new TaskRequest("T", "D"));

        mockMvc.perform(get("/api/v1/epics").param("readiness", "READY"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[?(@.id == '" + openEpic.getId() + "')]")
                        .exists())
                .andExpect(jsonPath("$.content[?(@.id == '" + doneEpic.getId() + "')]")
                        .doesNotExist());
    }

    @Test
    void listEpics_noFilter_excludesDoneTasksFromReadyItemCount() throws Exception {
        GitRepo repo = createGitRepo("https://github.com/test/ready-count-done-task.git");
        Epic epic = createEpic(repo, "Half Done Epic");
        var story = storyService.create(epic.getId(), new StoryRequest("S", "D"));
        markTaskDone(
                taskService.create(story.id(), new TaskRequest("Finished", "D")).id());
        taskService.create(story.id(), new TaskRequest("Pending", "D"));

        mockMvc.perform(get("/api/v1/epics"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[?(@.id == '" + epic.getId() + "')].readyItemCount")
                        .value(1));
    }

    @Test
    void getEpic_returnsEpic() throws Exception {
        GitRepo repo = createGitRepo("https://github.com/test/get.git");
        Epic e = createEpic(repo, "My Feature");

        mockMvc.perform(get("/api/v1/epics/" + e.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("My Feature"))
                .andExpect(jsonPath("$.stage").value("backlog"));
    }

    @Test
    void getEpic_notFound_returns404() throws Exception {
        mockMvc.perform(get("/api/v1/epics/" + UUID.randomUUID())).andExpect(status().isNotFound());
    }

    @Test
    void updateEpic_returns200() throws Exception {
        GitRepo repo = createGitRepo("https://github.com/test/update.git");
        Epic e = createEpic(repo, "Old Title");

        var body =
                Map.of("title", "New Title", "description", "Updated description", "softwareProjectId", repo.getId());

        mockMvc.perform(put("/api/v1/epics/" + e.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("New Title"));
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

    // --- priority field ---

    @Test
    void createEpic_withPriority_returns201WithPriority() throws Exception {
        GitRepo repo = createGitRepo("https://github.com/test/priority-create.git");

        var body = Map.of(
                "title", "High priority epic",
                "description", "Desc",
                "softwareProjectId", repo.getId(),
                "priority", "high");

        mockMvc.perform(post("/api/v1/epics")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.priority").value("high"));
    }

    @Test
    void createEpic_withoutPriority_defaultsToMedium() throws Exception {
        GitRepo repo = createGitRepo("https://github.com/test/priority-default.git");

        var body = Map.of("title", "No priority set", "description", "Desc", "softwareProjectId", repo.getId());

        mockMvc.perform(post("/api/v1/epics")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.priority").value("medium"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"low", "medium", "high"})
    void updatePriority_withValidPriority_returns200WithUpdatedPriority(String priority) throws Exception {
        GitRepo repo = createGitRepo("https://github.com/test/priority-" + priority + ".git");
        Epic e = createEpic(repo, "Priority Epic " + priority);

        mockMvc.perform(patch("/api/v1/epics/" + e.getId() + "/priority")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("priority", priority))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.priority").value(priority));
    }

    @Test
    void updatePriority_withSyntacticallyUnknownPriority_returns400() throws Exception {
        GitRepo repo = createGitRepo("https://github.com/test/priority-unknown.git");
        Epic e = createEpic(repo, "Priority Epic Unknown");

        mockMvc.perform(patch("/api/v1/epics/" + e.getId() + "/priority")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("priority", "not_a_real_priority"))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void updatePriority_onNonexistentEpic_returns404() throws Exception {
        mockMvc.perform(patch("/api/v1/epics/" + UUID.randomUUID() + "/priority")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("priority", "high"))))
                .andExpect(status().isNotFound());
    }

    @Test
    void listEpics_filteredByPriority_returnsOnlyMatching() throws Exception {
        GitRepo repo = createGitRepo("https://github.com/test/priority-filter.git");
        Epic highEpic = createEpic(repo, "High Priority Epic");
        epicService.updatePriority(highEpic.getId(), com.choruskube.core.model.enums.Priority.high);
        Epic lowEpic = createEpic(repo, "Low Priority Epic");
        epicService.updatePriority(lowEpic.getId(), com.choruskube.core.model.enums.Priority.low);

        mockMvc.perform(get("/api/v1/epics").param("priority", "high"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[?(@.id == '" + highEpic.getId() + "')]")
                        .exists())
                .andExpect(jsonPath("$.content[?(@.id == '" + lowEpic.getId() + "')]")
                        .doesNotExist());
    }

    @Test
    void listEpics_sortedByPriorityDescending_ordersHighToLow() throws Exception {
        GitRepo repo = createGitRepo("https://github.com/test/priority-sort.git");
        Epic lowEpic = createEpic(repo, "Sort Low");
        epicService.updatePriority(lowEpic.getId(), com.choruskube.core.model.enums.Priority.low);
        Epic highEpic = createEpic(repo, "Sort High");
        epicService.updatePriority(highEpic.getId(), com.choruskube.core.model.enums.Priority.high);

        // GET /api/v1/epics is global/unscoped, so it can also observe rows committed by
        // other Epics in the shared test database (e.g. non-@Transactional integration
        // tests, or any other fixture that outlives its own test) — see
        // StoryControllerTest#listAllStories_sortedByPriorityDescending_ordersHighToLow for
        // the full rationale. We can't assert exact ordinal position against a set we don't
        // fully control; asserting the *relative* order of the two Epics we created still
        // proves the sort is correct without depending on global isolation.
        MvcResult result = mockMvc.perform(
                        get("/api/v1/epics").param("sort", "priority,desc").param("size", "100"))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode content =
                objectMapper.readTree(result.getResponse().getContentAsString()).get("content");
        int highIndex = -1;
        int lowIndex = -1;
        for (int i = 0; i < content.size(); i++) {
            String id = content.get(i).get("id").asText();
            if (id.equals(highEpic.getId().toString())) {
                highIndex = i;
            } else if (id.equals(lowEpic.getId().toString())) {
                lowIndex = i;
            }
        }
        assertTrue(highIndex >= 0, "high-priority Epic missing from response");
        assertTrue(lowIndex >= 0, "low-priority Epic missing from response");
        assertTrue(highIndex < lowIndex, "expected high-priority Epic to sort before low-priority Epic");
    }

    @Test
    void updatePriority_belowCanOperatePermission_returns403() throws Exception {
        when(orgSecurity.canOperate()).thenReturn(false);

        GitRepo repo = createGitRepo("https://github.com/test/priority-forbidden.git");
        Epic e = createEpic(repo, "Priority Epic Forbidden");

        mockMvc.perform(patch("/api/v1/epics/" + e.getId() + "/priority")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("priority", "high"))))
                .andExpect(status().isForbidden());
    }

    @Test
    void updateEpic_withEpicUpdateRequestBody_leavesPriorityUnchanged() throws Exception {
        // The full PUT edit body (EpicUpdateRequest) carries no `priority` key at all — confirm the
        // stored priority survives a PUT untouched, mirroring how `stage` is edit-immutable there.
        GitRepo repo = createGitRepo("https://github.com/test/priority-put-noop.git");
        Epic e = createEpic(repo, "Priority Put Noop");
        epicService.updatePriority(e.getId(), com.choruskube.core.model.enums.Priority.high);

        var body =
                Map.of("title", "New Title", "description", "Updated description", "softwareProjectId", repo.getId());

        mockMvc.perform(put("/api/v1/epics/" + e.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.priority").value("high"));
    }

    // --- target date field ---

    @Test
    void updateTargetDate_withValidDate_returns200WithEchoedDate() throws Exception {
        GitRepo repo = createGitRepo("https://github.com/test/target-date-set.git");
        Epic e = createEpic(repo, "Target Date Epic");

        mockMvc.perform(patch("/api/v1/epics/" + e.getId() + "/target-date")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("targetDate", "2026-08-13"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.targetDate").value("2026-08-13"));
    }

    @Test
    void updateTargetDate_withNull_clearsDate() throws Exception {
        GitRepo repo = createGitRepo("https://github.com/test/target-date-clear.git");
        Epic e = createEpic(repo, "Target Date Clear Epic");
        epicService.updateTargetDate(e.getId(), java.time.LocalDate.parse("2026-08-13"));

        mockMvc.perform(patch("/api/v1/epics/" + e.getId() + "/target-date")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Collections.singletonMap("targetDate", null))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.targetDate").doesNotExist());
    }

    @Test
    void updateTargetDate_withMalformedDate_returns400() throws Exception {
        GitRepo repo = createGitRepo("https://github.com/test/target-date-malformed.git");
        Epic e = createEpic(repo, "Target Date Malformed Epic");

        mockMvc.perform(patch("/api/v1/epics/" + e.getId() + "/target-date")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("targetDate", "2026-13-40"))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void updateTargetDate_onNonexistentEpic_returns404() throws Exception {
        mockMvc.perform(patch("/api/v1/epics/" + UUID.randomUUID() + "/target-date")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("targetDate", "2026-08-13"))))
                .andExpect(status().isNotFound());
    }

    @Test
    void updateTargetDate_belowCanOperatePermission_returns403() throws Exception {
        when(orgSecurity.canOperate()).thenReturn(false);

        GitRepo repo = createGitRepo("https://github.com/test/target-date-forbidden.git");
        Epic e = createEpic(repo, "Target Date Forbidden Epic");

        mockMvc.perform(patch("/api/v1/epics/" + e.getId() + "/target-date")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("targetDate", "2026-08-13"))))
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
    private void markTaskDone(UUID taskId) {
        Task t = taskRepo.findById(taskId).orElseThrow();
        t.setStatus(WorkItemStatus.done);
        taskRepo.saveAndFlush(t);
    }

    private Epic createEpic(GitRepo repo, String title) {
        var response = epicService.create(
                new com.choruskube.core.dto.EpicRequest(title, "Description for " + title, null, repo.getId()), null);
        return epicRepo.findById(response.id()).orElseThrow();
    }
}
