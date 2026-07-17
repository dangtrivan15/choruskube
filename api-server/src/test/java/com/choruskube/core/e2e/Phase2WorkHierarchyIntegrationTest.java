package com.choruskube.core.e2e;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.choruskube.core.BaseTest;
import com.choruskube.core.model.GitRepo;
import com.choruskube.core.model.RepoGroup;
import com.choruskube.core.model.Task;
import com.choruskube.core.model.WorkflowRun;
import com.choruskube.core.model.enums.WorkItemStatus;
import com.choruskube.core.model.enums.WorkflowRunStatus;
import com.choruskube.core.repository.GitRepoRepository;
import com.choruskube.core.repository.TaskRepository;
import com.choruskube.core.repository.WorkflowRunRepository;
import com.choruskube.core.service.RepoGroupService;
import com.choruskube.core.service.RunEventPublisher;
import com.choruskube.core.util.RepoNameUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import io.temporal.serviceclient.WorkflowServiceStubs;
import java.util.List;
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
import org.springframework.test.web.servlet.MvcResult;

/**
 * Phase 2 backend integration coverage for the Epic -> Story -> Task -> run flow (renamed from
 * {@code Phase2FeatureProposalIntegrationTest}). Each scenario boots the full Spring context
 * against a TestContainers Postgres and drives the public REST API end-to-end; Temporal is
 * stubbed so {@code runService.startRun} doesn't try to talk to a real cluster, but every other
 * layer (DTO validation, service, JPA, FK cascade) is exercised.
 *
 * <p>Sister tests {@code EpicControllerTest}/{@code StoryControllerTest}/{@code TaskControllerTest}
 * cover controller-level status codes and shape; this class covers the cross-cutting behaviors
 * that only manifest when the Epic/Story/Task/workflow_run rows round-trip through the database
 * together, including a Task restart producing two run-history rows (Decision 1).
 */
@AutoConfigureMockMvc
public class Phase2WorkHierarchyIntegrationTest extends BaseTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private GitRepoRepository gitRepoRepo;

    @Autowired
    private RepoGroupService repoGroupService;

    @Autowired
    private TaskRepository taskRepo;

    @Autowired
    private WorkflowRunRepository runRepo;

    @MockitoBean
    private WorkflowServiceStubs workflowServiceStubs;

    @MockitoBean
    private WorkflowClient workflowClient;

    @MockitoBean
    private RunEventPublisher runEventPublisher;

    @BeforeEach
    void setUp() {
        // Stub Temporal so RunService.startRun() can hand off without a real worker.
        // Core is single-tenant and stamps no org, so no tenant setup is needed for
        // MockMvc-driven calls.
        WorkflowStub mockStub = Mockito.mock(WorkflowStub.class);
        Mockito.when(workflowClient.newUntypedWorkflowStub(
                        ArgumentMatchers.anyString(), ArgumentMatchers.any(WorkflowOptions.class)))
                .thenReturn(mockStub);
    }

    @Test
    void create_epic_with_git_repo_target_persists_software_project_id_equal_to_repo_id() throws Exception {
        GitRepo repo = createGitRepo("https://github.com/phase2-int/single-repo-target.git");

        Map<String, Object> body = Map.of(
                "title", "Phase 2 single-repo epic",
                "description", "Targets a 1-repo SoftwareProject (git_repo subtype)",
                "softwareProjectId", repo.getId());

        MvcResult result = mockMvc.perform(post("/api/v1/epics")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.softwareProject.id").value(repo.getId().toString()))
                .andExpect(jsonPath("$.softwareProject.type").value("git_repo"))
                .andExpect(jsonPath("$.status").value("backlog"))
                .andReturn();

        JsonNode json = objectMapper.readTree(result.getResponse().getContentAsString());
        UUID epicId = UUID.fromString(json.get("id").asText());
        assertThat(epicId).isNotNull();
    }

    @Test
    void create_epic_with_repo_group_target_persists_group_id() throws Exception {
        GitRepo r1 = createGitRepo("https://github.com/phase2-int/grp-r1.git");
        GitRepo r2 = createGitRepo("https://github.com/phase2-int/grp-r2.git");
        RepoGroup group = repoGroupService.create(
                "phase2-int-grp-" + UUID.randomUUID().toString().substring(0, 8),
                null,
                null,
                List.of(r1.getId(), r2.getId()));

        Map<String, Object> body = Map.of(
                "title", "Phase 2 multi-repo epic",
                "description", "Targets a user-created RepoGroup",
                "softwareProjectId", group.getId());

        mockMvc.perform(post("/api/v1/epics")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.softwareProject.id").value(group.getId().toString()))
                .andExpect(jsonPath("$.softwareProject.type").value("repo_group"))
                .andExpect(jsonPath("$.repos.length()").value(2));
    }

    @Test
    void full_epic_story_task_chain_is_startable_and_completable() throws Exception {
        GitRepo repo = createGitRepo("https://github.com/phase2-int/full-chain.git");

        UUID epicId = createEpic("Add a health check endpoint", "We need /healthz for the LB", repo.getId());
        UUID storyId = createStory(epicId, "Add /healthz route", "Backend story");
        UUID taskId = createTask(storyId, "Implement /healthz handler", "Task desc");

        Task persistedTask = taskRepo.findById(taskId).orElseThrow();
        assertThat(persistedTask.getSoftwareProjectId())
                .as("Task denormalizes software_project_id from the ancestor Epic (Decision 4)")
                .isEqualTo(repo.getId());
        assertThat(persistedTask.getStatus()).isEqualTo(WorkItemStatus.backlog);

        mockMvc.perform(post("/api/v1/tasks/" + taskId + "/start"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("in_progress"))
                .andExpect(jsonPath("$.latestRunId").isNotEmpty());

        Task started = taskRepo.findById(taskId).orElseThrow();
        assertThat(started.getStatus()).isEqualTo(WorkItemStatus.in_progress);

        // Epic and Story status now read "in_progress" purely from re-aggregating Task status.
        mockMvc.perform(get("/api/v1/epics/" + epicId))
                .andExpect(jsonPath("$.status").value("in_progress"));
        mockMvc.perform(get("/api/v1/stories/" + storyId))
                .andExpect(jsonPath("$.status").value("in_progress"));

        // Fetch the run id via the run-history endpoint and drive it to a terminal status.
        MvcResult historyResult = mockMvc.perform(get("/api/v1/tasks/" + taskId + "/runs"))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode history = objectMapper.readTree(historyResult.getResponse().getContentAsString());
        assertThat(history.get("content")).hasSize(1);
        UUID runId = UUID.fromString(history.get("content").get(0).get("id").asText());

        WorkflowRun run = runRepo.findById(runId).orElseThrow();
        assertThat(run.getTaskId()).isEqualTo(taskId);
        run.setStatus(WorkflowRunStatus.completed);
        runRepo.save(run);

        mockMvc.perform(patch("/api/v1/tasks/" + taskId + "/complete"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("done"));

        // Epic/Story now read "done" purely from re-aggregating Task status — no separate update.
        mockMvc.perform(get("/api/v1/epics/" + epicId))
                .andExpect(jsonPath("$.status").value("done"));
        mockMvc.perform(get("/api/v1/stories/" + storyId))
                .andExpect(jsonPath("$.status").value("done"));
    }

    @Test
    void restarting_a_task_after_a_terminal_run_keeps_both_runs_in_history() throws Exception {
        GitRepo repo = createGitRepo("https://github.com/phase2-int/restart-history.git");
        UUID epicId = createEpic("Restart flow", "Verify run history is preserved", repo.getId());
        UUID storyId = createStory(epicId, "Restart story", "desc");
        UUID taskId = createTask(storyId, "Restart task", "desc");

        mockMvc.perform(post("/api/v1/tasks/" + taskId + "/start")).andExpect(status().isOk());
        Task afterFirstStart = taskRepo.findById(taskId).orElseThrow();

        MvcResult firstHistory =
                mockMvc.perform(get("/api/v1/tasks/" + taskId + "/runs")).andReturn();
        UUID firstRunId = UUID.fromString(objectMapper
                .readTree(firstHistory.getResponse().getContentAsString())
                .get("content")
                .get(0)
                .get("id")
                .asText());

        WorkflowRun firstRun = runRepo.findById(firstRunId).orElseThrow();
        firstRun.setStatus(WorkflowRunStatus.failed);
        runRepo.save(firstRun);

        // Restart: same task, terminal most-recent run — a new run row is created, the prior one
        // is left untouched, and both remain queryable (Decision 1).
        mockMvc.perform(post("/api/v1/tasks/" + taskId + "/start")).andExpect(status().isOk());

        MvcResult secondHistory = mockMvc.perform(get("/api/v1/tasks/" + taskId + "/runs"))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode content = objectMapper
                .readTree(secondHistory.getResponse().getContentAsString())
                .get("content");
        assertThat(content).hasSize(2);
        // Newest first.
        assertThat(content.get(0).get("id").asText()).isNotEqualTo(firstRunId.toString());
        assertThat(content.get(1).get("id").asText()).isEqualTo(firstRunId.toString());
    }

    @Test
    void starting_a_task_while_a_run_is_active_is_rejected() throws Exception {
        GitRepo repo = createGitRepo("https://github.com/phase2-int/reject-active.git");
        UUID epicId = createEpic("Active run", "desc", repo.getId());
        UUID storyId = createStory(epicId, "S", "desc");
        UUID taskId = createTask(storyId, "T", "desc");

        mockMvc.perform(post("/api/v1/tasks/" + taskId + "/start")).andExpect(status().isOk());

        // The most recent run is still pending (non-terminal) — re-triggering must be rejected.
        mockMvc.perform(post("/api/v1/tasks/" + taskId + "/start")).andExpect(status().isConflict());
    }

    // --- Test helpers ---

    private UUID createEpic(String title, String description, UUID softwareProjectId) throws Exception {
        Map<String, Object> body =
                Map.of("title", title, "description", description, "softwareProjectId", softwareProjectId);
        MvcResult result = mockMvc.perform(post("/api/v1/epics")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andReturn();
        return UUID.fromString(objectMapper
                .readTree(result.getResponse().getContentAsString())
                .get("id")
                .asText());
    }

    private UUID createStory(UUID epicId, String title, String description) throws Exception {
        Map<String, Object> body = Map.of("title", title, "description", description);
        MvcResult result = mockMvc.perform(post("/api/v1/epics/" + epicId + "/stories")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andReturn();
        return UUID.fromString(objectMapper
                .readTree(result.getResponse().getContentAsString())
                .get("id")
                .asText());
    }

    private UUID createTask(UUID storyId, String title, String description) throws Exception {
        Map<String, Object> body = Map.of("title", title, "description", description);
        MvcResult result = mockMvc.perform(post("/api/v1/stories/" + storyId + "/tasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andReturn();
        return UUID.fromString(objectMapper
                .readTree(result.getResponse().getContentAsString())
                .get("id")
                .asText());
    }

    private GitRepo createGitRepo(String url) {
        GitRepo repo = new GitRepo();
        repo.setUrl(url);
        repo.setName(RepoNameUtil.deriveOwnerRepoName(url));
        return gitRepoRepo.save(repo);
    }
}
