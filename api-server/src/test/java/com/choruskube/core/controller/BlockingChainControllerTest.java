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
public class BlockingChainControllerTest extends BaseTest {

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
    void getTaskChain_threeHopChain_returns200WithNestedTree() throws Exception {
        EpicResponse epic = makeEpic("https://github.com/test/chain-ctrl-3hop.git");
        StoryResponse story = storyService.create(epic.id(), new StoryRequest("Story", "D"));
        TaskResponse a = taskService.create(story.id(), new TaskRequest("A", "D"));
        TaskResponse b = taskService.create(story.id(), new TaskRequest("B", "D"));
        TaskResponse c = taskService.create(story.id(), new TaskRequest("C", "D"));
        // A blocks B blocks C.
        dependencyService.create(new CreateDependencyRequest("task", a.id(), "task", b.id()));
        dependencyService.create(new CreateDependencyRequest("task", b.id(), "task", c.id()));

        mockMvc.perform(get("/api/v1/tasks/" + c.id() + "/blocking-chain"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.itemId").value(c.id().toString()))
                .andExpect(jsonPath("$.readiness").value("BLOCKED"))
                .andExpect(jsonPath("$.blockedBy[0].itemId").value(b.id().toString()))
                .andExpect(jsonPath("$.blockedBy[0].blockedBy[0].itemId").value(a.id().toString()))
                .andExpect(jsonPath("$.blockedBy[0].blockedBy[0].blockedBy.length()")
                        .value(0))
                .andExpect(jsonPath("$.truncated").value(false));
    }

    @Test
    void getTaskChain_readyItemWithNoBlockers_returns200WithEmptyChain() throws Exception {
        EpicResponse epic = makeEpic("https://github.com/test/chain-ctrl-ready.git");
        StoryResponse story = storyService.create(epic.id(), new StoryRequest("Story", "D"));
        TaskResponse task = taskService.create(story.id(), new TaskRequest("Solo", "D"));

        mockMvc.perform(get("/api/v1/tasks/" + task.id() + "/blocking-chain"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.readiness").value("READY"))
                .andExpect(jsonPath("$.blockedBy.length()").value(0));
    }

    @Test
    void getTaskChain_unknownId_returns404() throws Exception {
        mockMvc.perform(get("/api/v1/tasks/" + UUID.randomUUID() + "/blocking-chain"))
                .andExpect(status().isNotFound());
    }

    @Test
    void getTaskChain_crossingTwoEpics_resolvesCrossEpicNodeTitle() throws Exception {
        EpicResponse epic1 = makeEpic("https://github.com/test/chain-ctrl-epic1.git");
        StoryResponse story1 = storyService.create(epic1.id(), new StoryRequest("Story1", "D"));
        TaskResponse rootTask = taskService.create(story1.id(), new TaskRequest("Root Task", "D"));

        EpicResponse epic2 = makeEpic("https://github.com/test/chain-ctrl-epic2.git");
        StoryResponse story2 = storyService.create(epic2.id(), new StoryRequest("Story2", "D"));
        TaskResponse crossEpicBlocker = taskService.create(story2.id(), new TaskRequest("Cross-Epic Blocker", "D"));

        dependencyService.create(new CreateDependencyRequest("task", crossEpicBlocker.id(), "task", rootTask.id()));

        mockMvc.perform(get("/api/v1/tasks/" + rootTask.id() + "/blocking-chain"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.blockedBy[0].itemId")
                        .value(crossEpicBlocker.id().toString()))
                .andExpect(jsonPath("$.blockedBy[0].title").value("Cross-Epic Blocker"));
    }

    private EpicResponse makeEpic(String url) {
        GitRepo r = new GitRepo();
        r.setUrl(url);
        r.setName(RepoNameUtil.deriveOwnerRepoName(url));
        r = gitRepoRepo.save(r);
        return epicService.create(new EpicRequest("Epic", "Epic desc", null, r.getId()), null);
    }
}
