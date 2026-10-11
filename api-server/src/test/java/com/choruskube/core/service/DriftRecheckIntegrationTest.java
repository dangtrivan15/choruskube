package com.choruskube.core.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.choruskube.core.BaseTest;
import com.choruskube.core.CommittedFixtureCleaner;
import com.choruskube.core.config.GraphIds;
import com.choruskube.core.credential.GitHubCredentialResolver;
import com.choruskube.core.model.GateStamp;
import com.choruskube.core.model.GitRepo;
import com.choruskube.core.model.GraphTemplate;
import com.choruskube.core.model.NodeExecution;
import com.choruskube.core.model.NodeExecutionRepoBase;
import com.choruskube.core.model.RunPullRequest;
import com.choruskube.core.model.TemplateNode;
import com.choruskube.core.model.WorkflowRun;
import com.choruskube.core.model.enums.GateStampStatus;
import com.choruskube.core.model.enums.NodeExecutionStatus;
import com.choruskube.core.model.enums.WorkflowRunStatus;
import com.choruskube.core.observability.AuditSink;
import com.choruskube.core.repository.GateStampRepository;
import com.choruskube.core.repository.GitRepoRepository;
import com.choruskube.core.repository.GraphTemplateRepository;
import com.choruskube.core.repository.NodeExecutionRepoBaseRepository;
import com.choruskube.core.repository.NodeExecutionRepository;
import com.choruskube.core.repository.RunPullRequestRepository;
import com.choruskube.core.repository.TemplateNodeRepository;
import com.choruskube.core.repository.WorkflowRunRepository;
import com.choruskube.core.util.RepoNameUtil;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowStub;
import io.temporal.serviceclient.WorkflowServiceStubs;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The shipped Final Approval end to end against a real database: a stale approval is held and
 * routed for a re-check, and once the gate awaits again the held approval re-applies and merges.
 */
@AutoConfigureMockMvc
class DriftRecheckIntegrationTest extends BaseTest {

    private static final String TOKEN = "drift-it-token";

    @MockitoBean
    private WorkflowServiceStubs workflowServiceStubs;

    @MockitoBean
    private WorkflowClient workflowClient;

    @MockitoBean
    private GitHubAppService gitHubAppService;

    @MockitoBean
    private GitHubCredentialResolver credentialResolver;

    @MockitoBean
    private AuditSink auditSink;

    @MockitoBean
    private UploadService uploadService;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private RunService runService;

    @Autowired
    private GraphTemplateRepository templateRepo;

    @Autowired
    private TemplateNodeRepository templateNodeRepo;

    @Autowired
    private WorkflowRunRepository runRepo;

    @Autowired
    private NodeExecutionRepository execRepo;

    @Autowired
    private NodeExecutionRepoBaseRepository baseRepo;

    @Autowired
    private GateStampRepository stampRepo;

    @Autowired
    private GitRepoRepository gitRepoRepo;

    @Autowired
    private RunPullRequestRepository prRepo;

    @Autowired
    private JdbcTemplate jdbc;

    private CommittedFixtureCleaner cleaner;
    private UUID runId;
    private TemplateNode testNode;
    private TemplateNode finalApproval;
    private NodeExecution gateExec;
    private RunPullRequest pr;
    private String ownerRepo;
    private UUID repoId;

    @BeforeEach
    void setUp() {
        cleaner = new CommittedFixtureCleaner(jdbc);
        when(credentialResolver.getTokenForRun(any())).thenReturn(TOKEN);
        when(uploadService.uploadGateText(anyString(), any(), any(), eq("human_guidance.md"), anyString()))
                .thenReturn("{\"human_guidance.md\":\"k\"}");

        String url = "https://github.com/drift-it/repo-" + UUID.randomUUID();
        GitRepo repo = new GitRepo();
        repo.setUrl(url);
        repo.setName(RepoNameUtil.deriveOwnerRepoName(url));
        repo.setSecrets("[]");
        repo = gitRepoRepo.save(repo);
        repoId = repo.getId();
        cleaner.trackSoftwareProject(repoId);
        ownerRepo = RepoNameUtil.deriveOwnerRepoName(url);

        GraphTemplate featureDev = templateRepo
                .findFirstByGraphIdOrderByVersionDesc(GraphIds.FEATURE_DEVELOPMENT)
                .orElseThrow();
        List<TemplateNode> nodes = templateNodeRepo.findByGraphTemplateId(featureDev.getId());
        testNode = nodes.stream()
                .filter(n -> "test".equals(n.getLabel()))
                .findFirst()
                .orElseThrow();
        finalApproval = nodes.stream()
                .filter(n -> "final_approval".equals(n.getLabel()))
                .findFirst()
                .orElseThrow();

        WorkflowRun run = new WorkflowRun();
        run.setGraphTemplateId(featureDev.getId());
        run.setStatus(WorkflowRunStatus.running);
        run.setExternalRunId("drift-it-" + UUID.randomUUID());
        run.setInputs("{\"software_project_id\":\"" + repoId + "\"}");
        runId = cleaner.trackWorkflowRun(runRepo.save(run).getId());

        Instant earlier = Instant.now().minusSeconds(120).truncatedTo(ChronoUnit.MILLIS);
        completedTestRun(earlier, "base-1", 1);
        gateExec = awaitingGate(1);

        pr = new RunPullRequest();
        pr.setWorkflowRunId(runId);
        pr.setGitRepoId(repoId);
        pr.setPrUrl("https://github.com/" + ownerRepo + "/pull/11");
        pr.setPrNumber(11);
        pr.setRepoName(ownerRepo);
        pr = prRepo.save(pr);
    }

    @AfterEach
    void removeEverythingThisTestCommitted() {
        cleaner.deleteAll();
    }

    @Test
    void staleApproval_isHeld_thenReappliedAfterTheRecheck_andMerges() throws Exception {
        WorkflowStub firstStub = mock(WorkflowStub.class);
        when(workflowClient.newUntypedWorkflowStub(anyString())).thenReturn(firstStub);
        when(gitHubAppService.compareCommits(TOKEN, ownerRepo, "base-1", "main"))
                .thenReturn(2);

        mockMvc.perform(post("/api/v1/runs/{id}/nodes/{exec}/signal", runId, gateExec.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"approved\",\"feedback\":\"ship it\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("held_for_recheck"))
                .andExpect(jsonPath("$.staleRepos[0].repo").value(ownerRepo));

        verify(gitHubAppService, never()).mergePullRequest(any(), any(), any(Integer.class), any(), any());
        GateStamp stamp =
                stampRepo.findByWorkflowRunIdOrderByCreatedAtAsc(runId).get(0);
        assertThat(stamp.getStatus()).isEqualTo(GateStampStatus.pending);
        assertThat(stamp.getRecheckRounds()).isEqualTo(1);
        assertThat(payload(firstStub, gateExec)).contains("rereview").contains("human_guidance.md");

        // The re-check: the orchestrator completes the held gate, Code Review and Test run again on
        // the moved main, and the gate awaits a decision again.
        NodeExecution held = execRepo.findById(gateExec.getId()).orElseThrow();
        held.setStatus(NodeExecutionStatus.completed);
        held.setDecision("rereview");
        held.setCompletedAt(Instant.now());
        execRepo.save(held);
        completedTestRun(Instant.now(), "base-2", 2);
        NodeExecution again = awaitingGate(2);
        WorkflowStub secondStub = mock(WorkflowStub.class);
        when(workflowClient.newUntypedWorkflowStub(anyString())).thenReturn(secondStub);
        when(gitHubAppService.compareCommits(TOKEN, ownerRepo, "base-2", "main"))
                .thenReturn(0);
        when(gitHubAppService.fetchPullRequestDetail(TOKEN, ownerRepo, 11))
                .thenReturn(new GitHubAppService.PullRequestDetail(
                        "open", false, null, false, Boolean.TRUE, "head", "main"));
        when(gitHubAppService.mergePullRequest(TOKEN, ownerRepo, 11, "squash", "head"))
                .thenReturn("merge-sha");

        runService.applyHeldApproval(stamp.getId());

        assertThat(prRepo.findById(pr.getId()).orElseThrow().getMergedAt()).isNotNull();
        assertThat(stampRepo.findById(stamp.getId()).orElseThrow().getStatus()).isEqualTo(GateStampStatus.consumed);
        assertThat(payload(secondStub, again)).contains("approved").contains("ship it");

        mockMvc.perform(get("/api/v1/runs/{id}/gate-stamps", runId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].status").value("consumed"))
                .andExpect(jsonPath("$[0].recheckRounds").value(1))
                .andExpect(jsonPath("$[0].maxRecheckRounds").value(2))
                .andExpect(jsonPath("$[0].gateLabel").value("final_approval"));
    }

    @Test
    void currentApproval_mergesAtOnce() throws Exception {
        when(workflowClient.newUntypedWorkflowStub(anyString())).thenReturn(mock(WorkflowStub.class));
        when(gitHubAppService.compareCommits(TOKEN, ownerRepo, "base-1", "main"))
                .thenReturn(0);
        when(gitHubAppService.fetchPullRequestDetail(TOKEN, ownerRepo, 11))
                .thenReturn(new GitHubAppService.PullRequestDetail(
                        "open", false, null, false, Boolean.TRUE, "head", "main"));
        when(gitHubAppService.mergePullRequest(TOKEN, ownerRepo, 11, "squash", "head"))
                .thenReturn("merge-sha");

        mockMvc.perform(post("/api/v1/runs/{id}/nodes/{exec}/signal", runId, gateExec.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"approved\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("signaled"));

        assertThat(prRepo.findById(pr.getId()).orElseThrow().getMergedAt()).isNotNull();
        assertThat(stampRepo.findByWorkflowRunIdOrderByCreatedAtAsc(runId)).isEmpty();
    }

    private void completedTestRun(Instant completedAt, String baseSha, int iteration) {
        NodeExecution exec = new NodeExecution();
        exec.setWorkflowRunId(runId);
        exec.setTemplateNodeId(testNode.getId());
        exec.setGraphVersion(1);
        exec.setIteration(iteration);
        exec.setStatus(NodeExecutionStatus.completed);
        exec.setStartedAt(completedAt.minusSeconds(30));
        exec.setCompletedAt(completedAt);
        exec.setArtifactRefs("{}");
        exec = execRepo.save(exec);
        NodeExecutionRepoBase base = new NodeExecutionRepoBase();
        base.setNodeExecutionId(exec.getId());
        base.setGitRepoId(repoId);
        base.setOutcome("clean");
        base.setBaseSha(baseSha);
        baseRepo.save(base);
    }

    private NodeExecution awaitingGate(int iteration) {
        NodeExecution exec = new NodeExecution();
        exec.setWorkflowRunId(runId);
        exec.setTemplateNodeId(finalApproval.getId());
        exec.setGraphVersion(1);
        exec.setIteration(iteration);
        exec.setStatus(NodeExecutionStatus.awaiting_human);
        exec.setArtifactRefs("{}");
        return execRepo.save(exec);
    }

    private String payload(WorkflowStub stub, NodeExecution gate) {
        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(stub, atLeastOnce()).signal(eq("human-decision-" + gate.getId()), payload.capture());
        return payload.getValue().toString();
    }
}
