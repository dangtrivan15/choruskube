package com.choruskube.core.controller;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.choruskube.core.BaseTest;
import com.choruskube.core.config.OrgSecurity;
import com.choruskube.core.dto.CreateDependencyRequest;
import com.choruskube.core.dto.DependencyEdgeResponse;
import com.choruskube.core.dto.EpicRequest;
import com.choruskube.core.dto.EpicResponse;
import com.choruskube.core.dto.StoryRequest;
import com.choruskube.core.dto.StoryResponse;
import com.choruskube.core.dto.TaskRequest;
import com.choruskube.core.dto.TaskResponse;
import com.choruskube.core.model.GitRepo;
import com.choruskube.core.repository.GitRepoRepository;
import com.choruskube.core.service.EpicService;
import com.choruskube.core.service.RunEventPublisher;
import com.choruskube.core.service.StoryService;
import com.choruskube.core.service.TaskService;
import com.choruskube.core.service.WorkItemDependencyService;
import com.choruskube.core.util.RepoNameUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.temporal.client.WorkflowClient;
import io.temporal.serviceclient.WorkflowServiceStubs;
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

    private TaskResponse makeTask(String url) {
        GitRepo r = new GitRepo();
        r.setUrl(url);
        r.setName(RepoNameUtil.deriveOwnerRepoName(url));
        r = gitRepoRepo.save(r);
        EpicResponse epic = epicService.create(new EpicRequest("Epic", "Epic desc", null, r.getId()), null);
        StoryResponse story = storyService.create(epic.id(), new StoryRequest("Story", "Story desc"));
        return taskService.create(story.id(), new TaskRequest("Task", "Task desc"));
    }
}
