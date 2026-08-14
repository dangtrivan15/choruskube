package com.choruskube.core.controller;

import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.choruskube.core.BaseTest;
import com.choruskube.core.config.OrgSecurity;
import com.choruskube.core.dto.CreateDependencyRequest;
import com.choruskube.core.dto.EpicRequest;
import com.choruskube.core.dto.EpicResponse;
import com.choruskube.core.dto.StoryRequest;
import com.choruskube.core.dto.StoryResponse;
import com.choruskube.core.dto.TaskRequest;
import com.choruskube.core.model.GitRepo;
import com.choruskube.core.model.enums.WorkItemStatus;
import com.choruskube.core.service.EpicService;
import com.choruskube.core.service.RunEventPublisher;
import com.choruskube.core.service.TaskService;
import com.choruskube.core.service.WorkItemDependencyService;
import com.choruskube.core.util.RepoNameUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.temporal.client.WorkflowClient;
import io.temporal.serviceclient.WorkflowServiceStubs;
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
public class StoryControllerTest extends BaseTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private com.choruskube.core.repository.GitRepoRepository gitRepoRepo;

    @Autowired
    private EpicService epicService;

    @Autowired
    private com.choruskube.core.service.StoryService storyService;

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

    @MockitoBean
    private OrgSecurity orgSecurity;

    @BeforeEach
    void allowAllByDefault() {
        // Mirrors NoOpOrgSecurity (the un-mocked default): every level of access is allowed unless
        // an individual test overrides it, so the permission mock doesn't break the other tests in
        // this class (mirrors EpicControllerTest#allowAllByDefault).
        when(orgSecurity.canRead()).thenReturn(true);
        when(orgSecurity.canOperate()).thenReturn(true);
        when(orgSecurity.canAdmin()).thenReturn(true);
        when(orgSecurity.isPlatformAdmin()).thenReturn(true);
    }

    @Test
    void createStory_returns201() throws Exception {
        EpicResponse epic = makeEpic("https://github.com/test/story-create.git");

        var body = Map.of("title", "Story title", "description", "Story desc");

        mockMvc.perform(post("/api/v1/epics/" + epic.id() + "/stories")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.epicId").value(epic.id().toString()))
                .andExpect(jsonPath("$.title").value("Story title"))
                .andExpect(jsonPath("$.status").value("backlog"));
    }

    @Test
    void createStory_underUnknownEpic_returns404() throws Exception {
        var body = Map.of("title", "T", "description", "D");

        mockMvc.perform(post("/api/v1/epics/" + UUID.randomUUID() + "/stories")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isNotFound());
    }

    @Test
    void listStories_returnsStoriesForEpic() throws Exception {
        EpicResponse epic = makeEpic("https://github.com/test/story-list.git");
        makeStory(epic.id(), "S1");
        makeStory(epic.id(), "S2");

        mockMvc.perform(get("/api/v1/epics/" + epic.id() + "/stories"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));
    }

    @Test
    void listStories_blockedByUnfinishedDependency_readinessIsBlocked() throws Exception {
        // Decision 1: the flat list endpoint now populates the same `readiness` field the
        // Roadmap Graph View has always computed, instead of leaving it null.
        EpicResponse epic = makeEpic("https://github.com/test/story-list-readiness.git");
        StoryResponse blocking = makeStory(epic.id(), "Blocking");
        StoryResponse blocked = makeStory(epic.id(), "Blocked");
        dependencyService.create(new CreateDependencyRequest("story", blocking.id(), "story", blocked.id()));

        mockMvc.perform(get("/api/v1/epics/" + epic.id() + "/stories"))
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath("$[?(@.id=='" + blocked.id() + "')].readiness").value("BLOCKED"))
                .andExpect(jsonPath("$[?(@.id=='" + blocking.id() + "')].readiness")
                        .value("READY"));
    }

    @Test
    void getStory_returnsStory() throws Exception {
        EpicResponse epic = makeEpic("https://github.com/test/story-get.git");
        StoryResponse story = makeStory(epic.id(), "My Story");

        mockMvc.perform(get("/api/v1/stories/" + story.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("My Story"));
    }

    @Test
    void getStory_notFound_returns404() throws Exception {
        mockMvc.perform(get("/api/v1/stories/" + UUID.randomUUID())).andExpect(status().isNotFound());
    }

    @Test
    void updateStory_returns200() throws Exception {
        EpicResponse epic = makeEpic("https://github.com/test/story-update.git");
        StoryResponse story = makeStory(epic.id(), "Old Title");

        var body = Map.of("title", "New Title", "description", "New Desc");

        mockMvc.perform(put("/api/v1/stories/" + story.id())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("New Title"));
    }

    @Test
    void updateStory_withStartedTask_returns409() throws Exception {
        EpicResponse epic = makeEpic("https://github.com/test/story-update-conflict.git");
        StoryResponse story = makeStory(epic.id(), "S");
        var task = taskService.create(story.id(), new TaskRequest("T", "D"));
        taskService.start(task.id());

        var body = Map.of("title", "New Title", "description", "New Desc");

        mockMvc.perform(put("/api/v1/stories/" + story.id())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isConflict());
    }

    @Test
    void deleteStory_returns204() throws Exception {
        EpicResponse epic = makeEpic("https://github.com/test/story-delete.git");
        StoryResponse story = makeStory(epic.id(), "To Delete");

        mockMvc.perform(delete("/api/v1/stories/" + story.id())).andExpect(status().isNoContent());

        mockMvc.perform(get("/api/v1/stories/" + story.id())).andExpect(status().isNotFound());
    }

    @Test
    void deleteStory_withStartedTask_returns409() throws Exception {
        EpicResponse epic = makeEpic("https://github.com/test/story-delete-conflict.git");
        StoryResponse story = makeStory(epic.id(), "S");
        var task = taskService.create(story.id(), new TaskRequest("T", "D"));
        taskService.start(task.id());

        mockMvc.perform(delete("/api/v1/stories/" + story.id())).andExpect(status().isConflict());
    }

    // --- GET /api/v1/stories (global board listing) ---

    @Test
    void listAllStories_returnsPagedShape() throws Exception {
        // Board S1/S2 assert into the *global*, unscoped listing, which (like
        // TaskControllerTest#listAllTasks_returnsPagedShape) can also observe rows committed by
        // non-@Transactional e2e integration tests that persist for the life of the test JVM.
        // Assert presence of these two plus a lower-bound count, not an exact global total.
        EpicResponse epic = makeEpic("https://github.com/test/story-board-list.git");
        StoryResponse s1 = makeStory(epic.id(), "Board S1");
        StoryResponse s2 = makeStory(epic.id(), "Board S2");

        mockMvc.perform(get("/api/v1/stories"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(greaterThanOrEqualTo(2)))
                .andExpect(jsonPath("$.totalElements").value(greaterThanOrEqualTo(2)))
                .andExpect(jsonPath("$.content[?(@.id=='" + s1.id() + "')]").exists())
                .andExpect(jsonPath("$.content[?(@.id=='" + s2.id() + "')]").exists());
    }

    @Test
    void listAllStories_everyRowHasNullReadiness() throws Exception {
        EpicResponse epic = makeEpic("https://github.com/test/story-board-readiness.git");
        makeStory(epic.id(), "Board Readiness S1");

        mockMvc.perform(get("/api/v1/stories"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].readiness")
                        .value(org.hamcrest.Matchers.everyItem(org.hamcrest.Matchers.nullValue())));
    }

    @Test
    void listAllStories_filtersByStage() throws Exception {
        EpicResponse epic = makeEpic("https://github.com/test/story-board-filter.git");
        StoryResponse backlogStory = makeStory(epic.id(), "Still Backlog");
        StoryResponse movedStory = makeStory(epic.id(), "Moved");
        storyService.updateStage(movedStory.id(), WorkItemStatus.in_progress);

        mockMvc.perform(get("/api/v1/stories").param("stage", "backlog"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[?(@.id=='" + backlogStory.id() + "')]")
                        .exists())
                .andExpect(jsonPath("$.content[?(@.id=='" + movedStory.id() + "')]")
                        .doesNotExist());

        mockMvc.perform(get("/api/v1/stories").param("stage", "in_progress"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[?(@.id=='" + movedStory.id() + "')]")
                        .exists())
                .andExpect(jsonPath("$.content[?(@.id=='" + backlogStory.id() + "')]")
                        .doesNotExist());
    }

    @Test
    void listAllStories_belowCanReadPermission_returns403() throws Exception {
        when(orgSecurity.canRead()).thenReturn(false);

        mockMvc.perform(get("/api/v1/stories")).andExpect(status().isForbidden());
    }

    // --- PATCH /api/v1/stories/{id}/stage ---

    @ParameterizedTest
    @ValueSource(strings = {"backlog", "in_progress", "rolled_out"})
    void updateStage_withValidStage_returns200WithUpdatedStage(String stage) throws Exception {
        EpicResponse epic = makeEpic("https://github.com/test/story-stage-" + stage + ".git");
        StoryResponse story = makeStory(epic.id(), "Stage Story " + stage);

        mockMvc.perform(patch("/api/v1/stories/" + story.id() + "/stage")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("stage", stage))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stage").value(stage));
    }

    @Test
    void updateStage_withSyntacticallyUnknownStage_returns400() throws Exception {
        EpicResponse epic = makeEpic("https://github.com/test/story-stage-unknown.git");
        StoryResponse story = makeStory(epic.id(), "Stage Story Unknown");

        mockMvc.perform(patch("/api/v1/stories/" + story.id() + "/stage")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("stage", "not_a_real_stage"))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void updateStage_withDoneValue_returns400() throws Exception {
        // "done" is a syntactically valid WorkItemStatus (so Jackson deserializes it fine) but is
        // not a valid board stage — this is a distinct, service-layer rejection from the Jackson
        // enum-deserialization failure above.
        EpicResponse epic = makeEpic("https://github.com/test/story-stage-done.git");
        StoryResponse story = makeStory(epic.id(), "Stage Story Done");

        mockMvc.perform(patch("/api/v1/stories/" + story.id() + "/stage")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("stage", "done"))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void updateStage_onNonexistentStory_returns404() throws Exception {
        mockMvc.perform(patch("/api/v1/stories/" + UUID.randomUUID() + "/stage")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("stage", "in_progress"))))
                .andExpect(status().isNotFound());
    }

    @Test
    void updateStage_withStartedDescendantTask_succeeds() throws Exception {
        // Proves the "no edit once started" guard used by the full PUT edit endpoint does NOT
        // apply to stage moves.
        EpicResponse epic = makeEpic("https://github.com/test/story-stage-started-task.git");
        StoryResponse story = makeStory(epic.id(), "Stage Story Started Task");
        var task = taskService.create(story.id(), new TaskRequest("T", "D"));
        taskService.start(task.id());

        mockMvc.perform(patch("/api/v1/stories/" + story.id() + "/stage")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("stage", "rolled_out"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stage").value("rolled_out"));
    }

    @Test
    void updateStage_belowCanOperatePermission_returns403() throws Exception {
        when(orgSecurity.canOperate()).thenReturn(false);

        EpicResponse epic = makeEpic("https://github.com/test/story-stage-forbidden.git");
        StoryResponse story = makeStory(epic.id(), "Stage Story Forbidden");

        mockMvc.perform(patch("/api/v1/stories/" + story.id() + "/stage")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("stage", "in_progress"))))
                .andExpect(status().isForbidden());
    }

    // --- priority field ---

    @Test
    void createStory_withPriority_returns201WithPriority() throws Exception {
        EpicResponse epic = makeEpic("https://github.com/test/story-priority-create.git");

        var body = Map.of("title", "Story title", "description", "Story desc", "priority", "high");

        mockMvc.perform(post("/api/v1/epics/" + epic.id() + "/stories")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.priority").value("high"));
    }

    @Test
    void createStory_withoutPriority_defaultsToMedium() throws Exception {
        EpicResponse epic = makeEpic("https://github.com/test/story-priority-default.git");

        var body = Map.of("title", "Story title", "description", "Story desc");

        mockMvc.perform(post("/api/v1/epics/" + epic.id() + "/stories")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.priority").value("medium"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"low", "medium", "high"})
    void updatePriority_withValidPriority_returns200WithUpdatedPriority(String priority) throws Exception {
        EpicResponse epic = makeEpic("https://github.com/test/story-priority-" + priority + ".git");
        StoryResponse story = makeStory(epic.id(), "Priority Story " + priority);

        mockMvc.perform(patch("/api/v1/stories/" + story.id() + "/priority")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("priority", priority))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.priority").value(priority));
    }

    @Test
    void updatePriority_withSyntacticallyUnknownPriority_returns400() throws Exception {
        EpicResponse epic = makeEpic("https://github.com/test/story-priority-unknown.git");
        StoryResponse story = makeStory(epic.id(), "Priority Story Unknown");

        mockMvc.perform(patch("/api/v1/stories/" + story.id() + "/priority")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("priority", "not_a_real_priority"))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void updatePriority_onNonexistentStory_returns404() throws Exception {
        mockMvc.perform(patch("/api/v1/stories/" + UUID.randomUUID() + "/priority")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("priority", "high"))))
                .andExpect(status().isNotFound());
    }

    @Test
    void listAllStories_filteredByPriority_returnsOnlyMatching() throws Exception {
        EpicResponse epic = makeEpic("https://github.com/test/story-priority-filter.git");
        StoryResponse highStory = makeStory(epic.id(), "High Priority Story");
        storyService.updatePriority(highStory.id(), com.choruskube.core.model.enums.Priority.high);
        StoryResponse lowStory = makeStory(epic.id(), "Low Priority Story");
        storyService.updatePriority(lowStory.id(), com.choruskube.core.model.enums.Priority.low);

        mockMvc.perform(get("/api/v1/stories").param("priority", "high"))
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath("$.content[?(@.id=='" + highStory.id() + "')]").exists())
                .andExpect(
                        jsonPath("$.content[?(@.id=='" + lowStory.id() + "')]").doesNotExist());
    }

    @Test
    void listAllStories_sortedByPriorityDescending_ordersHighToLow() throws Exception {
        EpicResponse epic = makeEpic("https://github.com/test/story-priority-sort.git");
        StoryResponse lowStory = makeStory(epic.id(), "Sort Low");
        storyService.updatePriority(lowStory.id(), com.choruskube.core.model.enums.Priority.low);
        StoryResponse highStory = makeStory(epic.id(), "Sort High");
        storyService.updatePriority(highStory.id(), com.choruskube.core.model.enums.Priority.high);

        // GET /api/v1/stories is global/unscoped, so — like listAllTasks_returnsPagedShape
        // above — it can also observe rows committed by other Stories in the shared test
        // database (e.g. non-@Transactional integration tests, or any other fixture that
        // outlives its own test). We can't assert exact ordinal position against a set we
        // don't fully control; asserting the *relative* order of the two Stories we created
        // still proves the sort is correct without depending on global isolation.
        MvcResult result = mockMvc.perform(
                        get("/api/v1/stories").param("sort", "priority,desc").param("size", "100"))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode content =
                objectMapper.readTree(result.getResponse().getContentAsString()).get("content");
        int highIndex = -1;
        int lowIndex = -1;
        for (int i = 0; i < content.size(); i++) {
            String id = content.get(i).get("id").asText();
            if (id.equals(highStory.id().toString())) {
                highIndex = i;
            } else if (id.equals(lowStory.id().toString())) {
                lowIndex = i;
            }
        }
        assertTrue(highIndex >= 0, "high-priority Story missing from response");
        assertTrue(lowIndex >= 0, "low-priority Story missing from response");
        assertTrue(highIndex < lowIndex, "expected high-priority Story to sort before low-priority Story");
    }

    @Test
    void updatePriority_belowCanOperatePermission_returns403() throws Exception {
        when(orgSecurity.canOperate()).thenReturn(false);

        EpicResponse epic = makeEpic("https://github.com/test/story-priority-forbidden.git");
        StoryResponse story = makeStory(epic.id(), "Priority Story Forbidden");

        mockMvc.perform(patch("/api/v1/stories/" + story.id() + "/priority")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("priority", "high"))))
                .andExpect(status().isForbidden());
    }

    @Test
    void updateStory_withStoryUpdateRequestBody_leavesPriorityUnchanged() throws Exception {
        // The full PUT edit body (StoryUpdateRequest) carries no `priority` key at all — confirm the
        // stored priority survives a PUT untouched, mirroring how `stage` is edit-immutable there.
        EpicResponse epic = makeEpic("https://github.com/test/story-priority-put-noop.git");
        StoryResponse story = makeStory(epic.id(), "Priority Put Noop");
        storyService.updatePriority(story.id(), com.choruskube.core.model.enums.Priority.high);

        var body = Map.of("title", "New Title", "description", "New Desc");

        mockMvc.perform(put("/api/v1/stories/" + story.id())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.priority").value("high"));
    }

    // --- target date field ---

    @Test
    void updateTargetDate_withValidDate_returns200WithEchoedDate() throws Exception {
        EpicResponse epic = makeEpic("https://github.com/test/story-target-date-set.git");
        StoryResponse story = makeStory(epic.id(), "Target Date Story");

        mockMvc.perform(patch("/api/v1/stories/" + story.id() + "/target-date")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("targetDate", "2026-08-13"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.targetDate").value("2026-08-13"));
    }

    @Test
    void updateTargetDate_withNull_clearsDate() throws Exception {
        EpicResponse epic = makeEpic("https://github.com/test/story-target-date-clear.git");
        StoryResponse story = makeStory(epic.id(), "Target Date Clear Story");
        storyService.updateTargetDate(story.id(), java.time.LocalDate.parse("2026-08-13"));

        mockMvc.perform(patch("/api/v1/stories/" + story.id() + "/target-date")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                java.util.Collections.singletonMap("targetDate", null))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.targetDate").doesNotExist());
    }

    @Test
    void updateTargetDate_withMalformedDate_returns400() throws Exception {
        EpicResponse epic = makeEpic("https://github.com/test/story-target-date-malformed.git");
        StoryResponse story = makeStory(epic.id(), "Target Date Malformed Story");

        mockMvc.perform(patch("/api/v1/stories/" + story.id() + "/target-date")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("targetDate", "2026-13-40"))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void updateTargetDate_onNonexistentStory_returns404() throws Exception {
        mockMvc.perform(patch("/api/v1/stories/" + UUID.randomUUID() + "/target-date")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("targetDate", "2026-08-13"))))
                .andExpect(status().isNotFound());
    }

    @Test
    void updateTargetDate_belowCanOperatePermission_returns403() throws Exception {
        when(orgSecurity.canOperate()).thenReturn(false);

        EpicResponse epic = makeEpic("https://github.com/test/story-target-date-forbidden.git");
        StoryResponse story = makeStory(epic.id(), "Target Date Forbidden Story");

        mockMvc.perform(patch("/api/v1/stories/" + story.id() + "/target-date")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("targetDate", "2026-08-13"))))
                .andExpect(status().isForbidden());
    }

    // --- helpers ---

    private EpicResponse makeEpic(String url) {
        GitRepo r = new GitRepo();
        r.setUrl(url);
        r.setName(RepoNameUtil.deriveOwnerRepoName(url));
        r = gitRepoRepo.save(r);
        return epicService.create(new EpicRequest("Epic", "Epic desc", null, r.getId()), null);
    }

    private StoryResponse makeStory(UUID epicId, String title) {
        return storyService.create(epicId, new StoryRequest(title, "Desc for " + title));
    }
}
