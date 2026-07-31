package com.choruskube.core.controller;

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
import com.choruskube.core.dto.TaskResponse;
import com.choruskube.core.model.GitRepo;
import com.choruskube.core.repository.GitRepoRepository;
import com.choruskube.core.service.EpicService;
import com.choruskube.core.service.RunEventPublisher;
import com.choruskube.core.service.StoryService;
import com.choruskube.core.service.TaskService;
import com.choruskube.core.service.WorkItemDependencyService;
import com.choruskube.core.util.RepoNameUtil;
import io.temporal.client.WorkflowClient;
import io.temporal.serviceclient.WorkflowServiceStubs;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

@AutoConfigureMockMvc
@Transactional
public class RoadmapGraphControllerTest extends BaseTest {

    @Autowired
    private MockMvc mockMvc;

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
    void getGraph_returns200WithExpectedShape() throws Exception {
        EpicResponse epic = makeEpic("https://github.com/test/graph-ctrl-shape.git");
        StoryResponse story = storyService.create(epic.id(), new StoryRequest("S", "D"));
        TaskResponse blocking = taskService.create(story.id(), new TaskRequest("Blocking", "D"));
        TaskResponse blocked = taskService.create(story.id(), new TaskRequest("Blocked", "D"));
        dependencyService.create(new CreateDependencyRequest("task", blocking.id(), "task", blocked.id()));

        // A cross-Epic blocker of `blocked` (Decision 3), so the response shape assertion below
        // also covers the additive `direction`/`internalItemId` fields on `externalBlockers`, not
        // just the within-Epic `dependencies` shape.
        EpicResponse foreignEpic = makeEpic("https://github.com/test/graph-ctrl-shape-foreign.git");
        StoryResponse foreignStory = storyService.create(foreignEpic.id(), new StoryRequest("Foreign S", "D"));
        TaskResponse foreignBlocking = taskService.create(foreignStory.id(), new TaskRequest("Foreign Blocking", "D"));
        dependencyService.create(new CreateDependencyRequest("task", foreignBlocking.id(), "task", blocked.id()));

        mockMvc.perform(get("/api/v1/epics/" + epic.id() + "/graph"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.epic.id").value(epic.id().toString()))
                .andExpect(jsonPath("$.stories.length()").value(1))
                .andExpect(jsonPath("$.stories[0].id").value(story.id().toString()))
                .andExpect(jsonPath("$.tasks.length()").value(2))
                .andExpect(jsonPath("$.dependencies.length()").value(1))
                .andExpect(jsonPath("$.dependencies[0].blockingItemId")
                        .value(blocking.id().toString()))
                .andExpect(jsonPath("$.dependencies[0].blockedItemId")
                        .value(blocked.id().toString()))
                .andExpect(jsonPath("$.externalBlockers.length()").value(1))
                .andExpect(jsonPath("$.externalBlockers[0].direction").isNotEmpty())
                .andExpect(jsonPath("$.externalBlockers[0].internalItemId").isNotEmpty())
                .andExpect(jsonPath("$.externalBlockers[0].direction").value("BLOCKING"))
                .andExpect(jsonPath("$.externalBlockers[0].internalItemId")
                        .value(blocked.id().toString()));
    }

    @Test
    void getGraph_unknownEpic_returns404() throws Exception {
        mockMvc.perform(get("/api/v1/epics/" + UUID.randomUUID() + "/graph")).andExpect(status().isNotFound());
    }

    @Test
    void getGraph_withoutReadPermission_returns403() throws Exception {
        EpicResponse epic = makeEpic("https://github.com/test/graph-ctrl-forbidden.git");
        when(orgSecurity.canRead()).thenReturn(false);

        mockMvc.perform(get("/api/v1/epics/" + epic.id() + "/graph")).andExpect(status().isForbidden());
    }

    private EpicResponse makeEpic(String url) {
        GitRepo r = new GitRepo();
        r.setUrl(url);
        r.setName(RepoNameUtil.deriveOwnerRepoName(url));
        r = gitRepoRepo.save(r);
        return epicService.create(new EpicRequest("Epic", "Epic desc", null, r.getId()), null);
    }
}
