package com.choruskube.core.controller;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.choruskube.core.SharedContextTest;
import com.choruskube.core.config.OrgSecurity;
import com.choruskube.core.dto.EpicRequest;
import com.choruskube.core.dto.EpicResponse;
import com.choruskube.core.dto.StoryRequest;
import com.choruskube.core.dto.StoryResponse;
import com.choruskube.core.dto.TaskRequest;
import com.choruskube.core.model.GitRepo;
import com.choruskube.core.repository.GitRepoRepository;
import com.choruskube.core.service.EpicService;
import com.choruskube.core.service.RunEventPublisher;
import com.choruskube.core.service.StoryService;
import com.choruskube.core.service.TaskService;
import com.choruskube.core.util.RepoNameUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

@Transactional
public class TaskDeliveryCheckControllerTest extends SharedContextTest {

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

    // The same overrides as TaskControllerTest, so both share one application context.
    @MockitoBean
    private RunEventPublisher runEventPublisher;

    @MockitoBean
    private OrgSecurity orgSecurity;

    private UUID taskId;
    private String ownerRepo;

    @BeforeEach
    void setUp() {
        Mockito.when(workflowClient.newUntypedWorkflowStub(
                        ArgumentMatchers.anyString(), ArgumentMatchers.any(WorkflowOptions.class)))
                .thenReturn(Mockito.mock(WorkflowStub.class));
        when(orgSecurity.canRead()).thenReturn(true);
        when(orgSecurity.canOperate()).thenReturn(true);

        String url = "https://github.com/test/delivered-"
                + UUID.randomUUID().toString().substring(0, 8);
        GitRepo r = new GitRepo();
        r.setUrl(url);
        r.setName(RepoNameUtil.deriveOwnerRepoName(url));
        r = gitRepoRepo.save(r);
        ownerRepo = RepoNameUtil.githubOwnerRepo(url).orElseThrow();
        EpicResponse epic = epicService.create(new EpicRequest("Epic", "Epic desc", null, r.getId()), null);
        StoryResponse story = storyService.create(epic.id(), new StoryRequest("Story", "Story desc"));
        taskId = taskService
                .create(story.id(), new TaskRequest("Task", "Task desc"))
                .id();
    }

    @Test
    void closingAsDelivered_startsTheCheck_andReturnsTheTaskInProgress() throws Exception {
        close(List.of(pr(4)), "Merged outside ChorusKube.")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(taskId.toString()))
                .andExpect(jsonPath("$.status").value("in_progress"))
                .andExpect(jsonPath("$.latestRunId").isNotEmpty());
    }

    @Test
    void noPullRequest_isABadRequest() throws Exception {
        close(List.of(), "note").andExpect(status().isBadRequest());
    }

    @Test
    void moreThanTwentyPullRequests_isABadRequest() throws Exception {
        close(Collections.nCopies(21, pr(1)), "note").andExpect(status().isBadRequest());
    }

    @Test
    void aBlankNote_isABadRequest() throws Exception {
        close(List.of(pr(1)), "  ").andExpect(status().isBadRequest());
    }

    @Test
    void aCallerWhoCannotOperate_isForbidden() throws Exception {
        when(orgSecurity.canOperate()).thenReturn(false);

        close(List.of(pr(1)), "note").andExpect(status().isForbidden());
    }

    private ResultActions close(List<String> urls, String note) throws Exception {
        return mockMvc.perform(post("/api/v1/tasks/" + taskId + "/delivery-check")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("pullRequestUrls", urls, "note", note))));
    }

    private String pr(int number) {
        return "https://github.com/" + ownerRepo + "/pull/" + number;
    }
}
