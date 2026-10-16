package com.choruskube.core.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.choruskube.core.BaseTest;
import com.choruskube.core.dto.EpicRequest;
import com.choruskube.core.dto.EpicResponse;
import com.choruskube.core.dto.StoryRequest;
import com.choruskube.core.dto.StoryResponse;
import com.choruskube.core.dto.TaskRequest;
import com.choruskube.core.model.GitRepo;
import com.choruskube.core.model.TaskGithubIssue;
import com.choruskube.core.model.enums.GithubIssueState;
import com.choruskube.core.repository.GitRepoRepository;
import com.choruskube.core.repository.TaskGithubIssueRepository;
import com.choruskube.core.service.EpicService;
import com.choruskube.core.service.StoryService;
import com.choruskube.core.service.TaskService;
import com.choruskube.core.util.RepoNameUtil;
import io.temporal.client.WorkflowClient;
import io.temporal.serviceclient.WorkflowServiceStubs;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

@AutoConfigureMockMvc
@Transactional
class InternalTaskIssueControllerTest extends BaseTest {

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
    private TaskGithubIssueRepository taskGithubIssueRepo;

    @MockitoBean
    private WorkflowServiceStubs workflowServiceStubs;

    @MockitoBean
    private WorkflowClient workflowClient;

    private UUID taskId;

    @BeforeEach
    void setUp() {
        String url = "https://github.com/acme/task-issue-record-"
                + UUID.randomUUID().toString().substring(0, 8) + ".git";
        GitRepo repo = new GitRepo();
        repo.setUrl(url);
        repo.setName(RepoNameUtil.deriveOwnerRepoName(url));
        repo = gitRepoRepo.save(repo);
        EpicResponse epic = epicService.create(new EpicRequest("Epic", "D", null, repo.getId()), null);
        StoryResponse story = storyService.create(epic.id(), new StoryRequest("Story", "D"));
        taskId = taskService.create(story.id(), new TaskRequest("Task", "D")).id();

        TaskGithubIssue issue = new TaskGithubIssue();
        issue.setTaskId(taskId);
        issue.setGitRepoId(repo.getId());
        issue.setIssueNumber(7);
        issue.setIssueUrl(repo.getUrl() + "/issues/7");
        taskGithubIssueRepo.saveAndFlush(issue);
    }

    @Test
    void closed_openLinkage_is204AndRecordsIt() throws Exception {
        record(taskId, "{\"issueNumber\":7}").andExpect(status().isNoContent());

        TaskGithubIssue reloaded = taskGithubIssueRepo.findByTaskId(taskId).orElseThrow();
        assertThat(reloaded.getState()).isEqualTo(GithubIssueState.closed);
        assertThat(reloaded.getClosedAt()).isNotNull();
    }

    @Test
    void closed_alreadyClosed_is204() throws Exception {
        record(taskId, "{\"issueNumber\":7}").andExpect(status().isNoContent());

        record(taskId, "{\"issueNumber\":7}").andExpect(status().isNoContent());
    }

    @Test
    void closed_noLinkage_is404() throws Exception {
        record(UUID.randomUUID(), "{\"issueNumber\":7}").andExpect(status().isNotFound());
    }

    @Test
    void closed_otherIssueNumber_is409AndLeavesTheLinkageOpen() throws Exception {
        record(taskId, "{\"issueNumber\":8}").andExpect(status().isConflict());

        assertThat(taskGithubIssueRepo.findByTaskId(taskId).orElseThrow().getState())
                .isEqualTo(GithubIssueState.open);
    }

    @Test
    void closed_issueNumberZero_is400() throws Exception {
        record(taskId, "{\"issueNumber\":0}").andExpect(status().isBadRequest());
    }

    @Test
    void closed_issueNumberOmitted_is400() throws Exception {
        record(taskId, "{}").andExpect(status().isBadRequest());
    }

    @Test
    void closed_missingBody_is400() throws Exception {
        mockMvc.perform(post("/internal/tasks/{taskId}/github-issue/closed", taskId)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest());
    }

    private ResultActions record(UUID task, String body) throws Exception {
        return mockMvc.perform(post("/internal/tasks/{taskId}/github-issue/closed", task)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }
}
