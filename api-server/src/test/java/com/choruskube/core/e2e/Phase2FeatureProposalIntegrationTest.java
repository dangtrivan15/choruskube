package com.choruskube.core.e2e;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.choruskube.core.BaseTest;
import com.choruskube.core.config.GraphIds;
import com.choruskube.core.model.FeatureProposal;
import com.choruskube.core.model.GitRepo;
import com.choruskube.core.model.RepoGroup;
import com.choruskube.core.model.WorkflowRun;
import com.choruskube.core.model.enums.FeatureProposalStatus;
import com.choruskube.core.model.enums.WorkflowRunStatus;
import com.choruskube.core.repository.FeatureProposalRepository;
import com.choruskube.core.repository.GitRepoRepository;
import com.choruskube.core.repository.GraphTemplateRepository;
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
 * Phase 2 backend integration coverage for the proposal → run flow on the new
 * {@code software_project_id} shape (post-V46). Each scenario boots the full
 * Spring context against a TestContainers Postgres and drives the public REST
 * API end-to-end; Temporal is stubbed so {@code runService.startRun} doesn't
 * try to talk to a real cluster, but every other layer (DTO validation,
 * service, JPA, FK cascade) is exercised.
 *
 * <p>Sister test {@code FeatureProposalControllerTest} covers controller-level
 * status codes and shape; this class covers the cross-cutting behaviors that
 * only manifest when the proposal, software_project, and workflow_run rows
 * round-trip through the database together.
 */
@AutoConfigureMockMvc
public class Phase2FeatureProposalIntegrationTest extends BaseTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private GitRepoRepository gitRepoRepo;

    @Autowired
    private RepoGroupService repoGroupService;

    @Autowired
    private FeatureProposalRepository proposalRepo;

    @Autowired
    private WorkflowRunRepository runRepo;

    @Autowired
    private GraphTemplateRepository templateRepo;

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
    void create_proposal_with_git_repo_target_persists_software_project_id_equal_to_repo_id() throws Exception {
        GitRepo repo = createGitRepo("https://github.com/phase2-int/single-repo-target.git");

        Map<String, Object> body = Map.of(
                "title", "Phase 2 single-repo proposal",
                "description", "Targets a 1-repo SoftwareProject (git_repo subtype)",
                "softwareProjectId", repo.getId());

        MvcResult result = mockMvc.perform(post("/api/v1/feature-proposals")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.softwareProject.id").value(repo.getId().toString()))
                .andExpect(jsonPath("$.softwareProject.type").value("git_repo"))
                .andReturn();

        JsonNode json = objectMapper.readTree(result.getResponse().getContentAsString());
        UUID proposalId = UUID.fromString(json.get("id").asText());

        // Post-V45/V46: git_repo.id == software_project.id, so the proposal's
        // software_project_id is exactly the repo's id.
        FeatureProposal persisted = proposalRepo.findById(proposalId).orElseThrow();
        assertThat(persisted.getSoftwareProjectId()).isEqualTo(repo.getId());
    }

    @Test
    void create_proposal_with_repo_group_target_persists_group_id() throws Exception {
        GitRepo r1 = createGitRepo("https://github.com/phase2-int/grp-r1.git");
        GitRepo r2 = createGitRepo("https://github.com/phase2-int/grp-r2.git");
        RepoGroup group = repoGroupService.create(
                "phase2-int-grp-" + UUID.randomUUID().toString().substring(0, 8),
                null,
                null,
                List.of(r1.getId(), r2.getId()));

        Map<String, Object> body = Map.of(
                "title", "Phase 2 multi-repo proposal",
                "description", "Targets a user-created RepoGroup",
                "softwareProjectId", group.getId());

        MvcResult result = mockMvc.perform(post("/api/v1/feature-proposals")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.softwareProject.id").value(group.getId().toString()))
                .andExpect(jsonPath("$.softwareProject.type").value("repo_group"))
                .andExpect(jsonPath("$.repos.length()").value(2))
                .andReturn();

        JsonNode json = objectMapper.readTree(result.getResponse().getContentAsString());
        UUID proposalId = UUID.fromString(json.get("id").asText());

        FeatureProposal persisted = proposalRepo.findById(proposalId).orElseThrow();
        assertThat(persisted.getSoftwareProjectId()).isEqualTo(group.getId());
    }

    @Test
    void start_proposal_emits_software_project_id_against_latest_feature_dev() throws Exception {
        GitRepo repo = createGitRepo("https://github.com/phase2-int/start-target.git");

        Map<String, Object> createBody = Map.of(
                "title", "Add a health check endpoint",
                "description", "We need /healthz for the LB",
                "motivation", "Allow probes",
                "softwareProjectId", repo.getId());

        MvcResult createResult = mockMvc.perform(post("/api/v1/feature-proposals")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createBody)))
                .andExpect(status().isCreated())
                .andReturn();
        UUID proposalId = UUID.fromString(objectMapper
                .readTree(createResult.getResponse().getContentAsString())
                .get("id")
                .asText());

        mockMvc.perform(post("/api/v1/feature-proposals/" + proposalId + "/start"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("in_progress"))
                .andExpect(jsonPath("$.workflowRunId").isNotEmpty());

        FeatureProposal started = proposalRepo.findById(proposalId).orElseThrow();
        assertThat(started.getStatus()).isEqualTo(FeatureProposalStatus.in_progress);
        assertThat(started.getWorkflowRunId()).isNotNull();

        WorkflowRun run = runRepo.findById(started.getWorkflowRunId()).orElseThrow();

        // The seeded latest template is the highest version of feature-development;
        // the proposal service picks it via findFirstByGraphIdOrderByVersionDesc.
        var latest = templateRepo
                .findFirstByGraphIdOrderByVersionDesc(GraphIds.FEATURE_DEVELOPMENT)
                .orElseThrow(() -> new AssertionError("Feature Dev template should be seeded"));
        assertThat(run.getGraphTemplateId()).isEqualTo(latest.getId());

        // The new shape: inputs JSON carries software_project_id, NOT the legacy "repos" list.
        JsonNode inputs = objectMapper.readTree(run.getInputs());
        assertThat(inputs.has("software_project_id")).isTrue();
        assertThat(inputs.get("software_project_id").asText())
                .isEqualTo(started.getSoftwareProjectId().toString());
        assertThat(inputs.has("repos"))
                .as("latest Feature Dev must not carry the legacy repos field")
                .isFalse();

        // The synthesized feature_request preserves the proposal's title and description so
        // the AI agent has the same context the human reviewer entered.
        assertThat(inputs.has("feature_request")).isTrue();
        String featureRequest = inputs.get("feature_request").asText();
        assertThat(featureRequest).contains("Add a health check endpoint");
        assertThat(featureRequest).contains("We need /healthz for the LB");
    }

    @Test
    void roll_out_proposal_after_terminal_run_transitions_to_rolled_out() throws Exception {
        GitRepo repo = createGitRepo("https://github.com/phase2-int/rollout-target.git");

        Map<String, Object> createBody = Map.of(
                "title", "Rollout flow",
                "description", "Verify rolled_out transition",
                "softwareProjectId", repo.getId());
        MvcResult createResult = mockMvc.perform(post("/api/v1/feature-proposals")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createBody)))
                .andExpect(status().isCreated())
                .andReturn();
        UUID proposalId = UUID.fromString(objectMapper
                .readTree(createResult.getResponse().getContentAsString())
                .get("id")
                .asText());

        // Drive into in_progress via the same start path real users hit, then mark the run
        // terminal directly so the rollOut precondition (linked run is terminal) is met.
        mockMvc.perform(post("/api/v1/feature-proposals/" + proposalId + "/start"))
                .andExpect(status().isOk());
        FeatureProposal started = proposalRepo.findById(proposalId).orElseThrow();
        WorkflowRun run = runRepo.findById(started.getWorkflowRunId()).orElseThrow();
        run.setStatus(WorkflowRunStatus.completed);
        runRepo.save(run);

        mockMvc.perform(patch("/api/v1/feature-proposals/" + proposalId + "/roll-out"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("rolled_out"));

        FeatureProposal rolledOut = proposalRepo.findById(proposalId).orElseThrow();
        assertThat(rolledOut.getStatus()).isEqualTo(FeatureProposalStatus.rolled_out);
    }

    // --- Test helpers ---

    private GitRepo createGitRepo(String url) {
        GitRepo repo = new GitRepo();
        repo.setUrl(url);
        repo.setName(RepoNameUtil.deriveOwnerRepoName(url));
        return gitRepoRepo.save(repo);
    }
}
