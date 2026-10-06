package com.choruskube.core.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.choruskube.core.BaseTest;
import com.choruskube.core.config.OrgSecurity;
import com.choruskube.core.dto.CreateDependencyRequest;
import com.choruskube.core.dto.DependencyEdgeResponse;
import com.choruskube.core.dto.EpicDependencyResponse;
import com.choruskube.core.dto.EpicRequest;
import com.choruskube.core.dto.EpicResponse;
import com.choruskube.core.dto.StoryRequest;
import com.choruskube.core.dto.StoryResponse;
import com.choruskube.core.dto.TaskRequest;
import com.choruskube.core.dto.TaskResponse;
import com.choruskube.core.model.GitRepo;
import com.choruskube.core.model.enums.BlockerDirection;
import com.choruskube.core.repository.GitRepoRepository;
import com.choruskube.core.service.EpicService;
import com.choruskube.core.service.RunEventPublisher;
import com.choruskube.core.service.StoryService;
import com.choruskube.core.service.TaskService;
import com.choruskube.core.service.WorkItemDependencyService;
import com.choruskube.core.util.RepoNameUtil;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.temporal.client.WorkflowClient;
import io.temporal.serviceclient.WorkflowServiceStubs;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

@AutoConfigureMockMvc
@Transactional
public class WorkItemDependencyControllerTest extends BaseTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private GitRepoRepository gitRepoRepo;

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

    @MockitoBean
    private OrgSecurity orgSecurity;

    @BeforeEach
    void setUp() {
        when(orgSecurity.canRead()).thenReturn(true);
        when(orgSecurity.canOperate()).thenReturn(true);
        when(orgSecurity.canAdmin()).thenReturn(true);
    }

    @Test
    void createDependency_returns201() throws Exception {
        TaskResponse blocking = makeTask("https://github.com/test/dep-ctrl-create-a.git");
        TaskResponse blocked = makeTask("https://github.com/test/dep-ctrl-create-b.git");

        var body = new CreateDependencyRequest("task", blocking.id(), "task", blocked.id());

        mockMvc.perform(post("/api/v1/dependencies")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.blockingItemId").value(blocking.id().toString()))
                .andExpect(jsonPath("$.blockedItemId").value(blocked.id().toString()));
    }

    @Test
    void createDependency_selfLoop_returns400() throws Exception {
        TaskResponse task = makeTask("https://github.com/test/dep-ctrl-self-loop.git");
        var body = new CreateDependencyRequest("task", task.id(), "task", task.id());

        mockMvc.perform(post("/api/v1/dependencies")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void createDependency_duplicate_returns400() throws Exception {
        TaskResponse blocking = makeTask("https://github.com/test/dep-ctrl-dup-a.git");
        TaskResponse blocked = makeTask("https://github.com/test/dep-ctrl-dup-b.git");
        dependencyService.create(new CreateDependencyRequest("task", blocking.id(), "task", blocked.id()));

        var body = new CreateDependencyRequest("task", blocking.id(), "task", blocked.id());

        mockMvc.perform(post("/api/v1/dependencies")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void createDependency_malformedItemType_returns400() throws Exception {
        TaskResponse blocked = makeTask("https://github.com/test/dep-ctrl-malformed.git");

        var body = Map.of(
                "blockingItemType",
                "bogus",
                "blockingItemId",
                UUID.randomUUID().toString(),
                "blockedItemType",
                "task",
                "blockedItemId",
                blocked.id().toString());

        mockMvc.perform(post("/api/v1/dependencies")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void createDependency_directCycle_returns409() throws Exception {
        TaskResponse a = makeTask("https://github.com/test/dep-ctrl-direct-cycle-a.git");
        TaskResponse b = makeTask("https://github.com/test/dep-ctrl-direct-cycle-b.git");
        dependencyService.create(new CreateDependencyRequest("task", a.id(), "task", b.id()));

        var body = new CreateDependencyRequest("task", b.id(), "task", a.id());

        mockMvc.perform(post("/api/v1/dependencies")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isConflict());
    }

    @Test
    void createDependency_indirectCycle_returns409() throws Exception {
        TaskResponse a = makeTask("https://github.com/test/dep-ctrl-indirect-cycle-a.git");
        TaskResponse b = makeTask("https://github.com/test/dep-ctrl-indirect-cycle-b.git");
        TaskResponse c = makeTask("https://github.com/test/dep-ctrl-indirect-cycle-c.git");
        dependencyService.create(new CreateDependencyRequest("task", a.id(), "task", b.id()));
        dependencyService.create(new CreateDependencyRequest("task", b.id(), "task", c.id()));

        var body = new CreateDependencyRequest("task", c.id(), "task", a.id());

        mockMvc.perform(post("/api/v1/dependencies")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isConflict());
    }

    @Test
    void createDependency_nonCycleFormingEdgeAmongSameNodes_returns201() throws Exception {
        // A blocks B, B blocks C already exist; A blocks C is a valid extra edge (not a cycle) and
        // must still succeed — proves the cycle guard doesn't over-reject a diamond-shaped graph.
        TaskResponse a = makeTask("https://github.com/test/dep-ctrl-non-cycle-a.git");
        TaskResponse b = makeTask("https://github.com/test/dep-ctrl-non-cycle-b.git");
        TaskResponse c = makeTask("https://github.com/test/dep-ctrl-non-cycle-c.git");
        dependencyService.create(new CreateDependencyRequest("task", a.id(), "task", b.id()));
        dependencyService.create(new CreateDependencyRequest("task", b.id(), "task", c.id()));

        var body = new CreateDependencyRequest("task", a.id(), "task", c.id());

        mockMvc.perform(post("/api/v1/dependencies")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isCreated());
    }

    @Test
    void createDependency_withoutOperatePermission_returns403() throws Exception {
        TaskResponse blocking = makeTask("https://github.com/test/dep-ctrl-forbidden-a.git");
        TaskResponse blocked = makeTask("https://github.com/test/dep-ctrl-forbidden-b.git");
        when(orgSecurity.canOperate()).thenReturn(false);

        var body = new CreateDependencyRequest("task", blocking.id(), "task", blocked.id());

        mockMvc.perform(post("/api/v1/dependencies")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isForbidden());
    }

    @Test
    void deleteDependency_returns204ThenNotFoundOnSecondDelete() throws Exception {
        TaskResponse blocking = makeTask("https://github.com/test/dep-ctrl-delete-a.git");
        TaskResponse blocked = makeTask("https://github.com/test/dep-ctrl-delete-b.git");
        DependencyEdgeResponse edge =
                dependencyService.create(new CreateDependencyRequest("task", blocking.id(), "task", blocked.id()));

        mockMvc.perform(delete("/api/v1/dependencies/" + edge.id())).andExpect(status().isNoContent());
        mockMvc.perform(delete("/api/v1/dependencies/" + edge.id())).andExpect(status().isNotFound());
    }

    @Test
    void deleteDependency_withoutAdminPermission_returns403() throws Exception {
        TaskResponse blocking = makeTask("https://github.com/test/dep-ctrl-delete-forbidden-a.git");
        TaskResponse blocked = makeTask("https://github.com/test/dep-ctrl-delete-forbidden-b.git");
        DependencyEdgeResponse edge =
                dependencyService.create(new CreateDependencyRequest("task", blocking.id(), "task", blocked.id()));
        when(orgSecurity.canAdmin()).thenReturn(false);

        mockMvc.perform(delete("/api/v1/dependencies/" + edge.id())).andExpect(status().isForbidden());
    }

    @Test
    void listEpicDependencies_returnsOnlyEdgesOnTheEpicItself_fromEachEpicsOwnSide() throws Exception {
        GitRepo repo = makeRepo("https://github.com/test/dep-ctrl-epic-list.git");
        EpicResponse epic = makeEpic(repo, "Listed Epic");
        EpicResponse blocker = makeEpic(repo, "Blocker Epic");
        EpicResponse downstream = makeEpic(repo, "Downstream Epic");
        EpicResponse taskOwner = makeEpic(repo, "Task Owner Epic");
        StoryResponse ownerStory = storyService.create(taskOwner.id(), new StoryRequest("Owner Story", "d"));
        TaskResponse blockingTask = taskService.create(ownerStory.id(), new TaskRequest("Blocking Task", "d"));

        DependencyEdgeResponse blockedByEpic =
                dependencyService.create(new CreateDependencyRequest("epic", blocker.id(), "epic", epic.id()));
        DependencyEdgeResponse blocksEpic =
                dependencyService.create(new CreateDependencyRequest("epic", epic.id(), "epic", downstream.id()));
        DependencyEdgeResponse blockedByTask =
                dependencyService.create(new CreateDependencyRequest("task", blockingTask.id(), "epic", epic.id()));

        // Edges on the Epic's own Stories/Tasks belong to those items, not to the Epic.
        StoryResponse childStory = storyService.create(epic.id(), new StoryRequest("Child Story", "d"));
        TaskResponse childTask = taskService.create(childStory.id(), new TaskRequest("Child Task", "d"));
        dependencyService.create(new CreateDependencyRequest("story", childStory.id(), "epic", downstream.id()));
        dependencyService.create(new CreateDependencyRequest("task", blockingTask.id(), "task", childTask.id()));

        assertThat(listDependencies(epic.id()))
                .containsExactlyInAnyOrder(
                        new EpicDependencyResponse(
                                blockedByEpic.id(),
                                BlockerDirection.BLOCKED,
                                "epic",
                                blocker.id(),
                                "Blocker Epic",
                                blocker.id(),
                                "Blocker Epic"),
                        new EpicDependencyResponse(
                                blockedByTask.id(),
                                BlockerDirection.BLOCKED,
                                "task",
                                blockingTask.id(),
                                "Blocking Task",
                                taskOwner.id(),
                                "Task Owner Epic"),
                        new EpicDependencyResponse(
                                blocksEpic.id(),
                                BlockerDirection.BLOCKING,
                                "epic",
                                downstream.id(),
                                "Downstream Epic",
                                downstream.id(),
                                "Downstream Epic"));

        // The same edge, read from the Epic at its other end, carries that Epic's own role.
        assertThat(listDependencies(blocker.id()))
                .containsExactly(new EpicDependencyResponse(
                        blockedByEpic.id(),
                        BlockerDirection.BLOCKING,
                        "epic",
                        epic.id(),
                        "Listed Epic",
                        epic.id(),
                        "Listed Epic"));
        // taskOwner's Task blocks the listed Epic, but taskOwner itself is on no edge.
        assertThat(listDependencies(taskOwner.id())).isEmpty();
    }

    @Test
    void listEpicDependencies_unknownEpic_returns404() throws Exception {
        mockMvc.perform(get("/api/v1/epics/" + UUID.randomUUID() + "/dependencies"))
                .andExpect(status().isNotFound());
    }

    @Test
    void listEpicDependencies_withoutReadPermission_returns403() throws Exception {
        EpicResponse epic = makeEpic(makeRepo("https://github.com/test/dep-ctrl-epic-list-forbidden.git"), "Epic");
        when(orgSecurity.canRead()).thenReturn(false);

        mockMvc.perform(get("/api/v1/epics/" + epic.id() + "/dependencies")).andExpect(status().isForbidden());
    }

    private List<EpicDependencyResponse> listDependencies(UUID epicId) throws Exception {
        String body = mockMvc.perform(get("/api/v1/epics/" + epicId + "/dependencies"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return objectMapper.readValue(body, new TypeReference<>() {});
    }

    private GitRepo makeRepo(String url) {
        GitRepo r = new GitRepo();
        r.setUrl(url);
        r.setName(RepoNameUtil.deriveOwnerRepoName(url));
        return gitRepoRepo.save(r);
    }

    private EpicResponse makeEpic(GitRepo repo, String title) {
        return epicService.create(new EpicRequest(title, "Epic desc", null, repo.getId()), null);
    }

    private TaskResponse makeTask(String url) {
        EpicResponse epic = makeEpic(makeRepo(url), "Epic");
        StoryResponse story = storyService.create(epic.id(), new StoryRequest("Story", "Story desc"));
        return taskService.create(story.id(), new TaskRequest("Task", "Task desc"));
    }
}
