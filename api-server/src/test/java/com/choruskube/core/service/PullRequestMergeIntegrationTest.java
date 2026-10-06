package com.choruskube.core.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.choruskube.core.BaseTest;
import com.choruskube.core.CommittedFixtureCleaner;
import com.choruskube.core.config.GraphIds;
import com.choruskube.core.credential.GitHubCredentialResolver;
import com.choruskube.core.exception.GitHubMergeRefusedException;
import com.choruskube.core.exception.GitHubRateLimitHints;
import com.choruskube.core.model.GitRepo;
import com.choruskube.core.model.GraphTemplate;
import com.choruskube.core.model.NodeExecution;
import com.choruskube.core.model.RunPullRequest;
import com.choruskube.core.model.TemplateNode;
import com.choruskube.core.model.WorkflowRun;
import com.choruskube.core.model.enums.NodeExecutionStatus;
import com.choruskube.core.model.enums.PullRequestState;
import com.choruskube.core.model.enums.WorkflowRunStatus;
import com.choruskube.core.observability.AuditSink;
import com.choruskube.core.repository.GitRepoRepository;
import com.choruskube.core.repository.GraphTemplateRepository;
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
import org.springframework.test.web.servlet.ResultActions;

/**
 * Merge-on-approval through the real approval endpoint, the real seeded Final Approval gate and a
 * real database — only GitHub, the credential seam, the audit sink and Temporal are stubbed. Not
 * {@code @Transactional}: the merge outcome is recorded in its own transaction, and what these
 * tests prove is what a separate request observes afterwards.
 *
 * <p>Because it commits, every row it creates is removed by hand in {@code @AfterEach} via {@link
 * CommittedFixtureCleaner} — otherwise {@code rereviewDecision_neverTouchesGitHubOrTheCredential}
 * leaves its pull requests permanently unmerged and due, and the next class to scan for unmerged
 * rows against the shared container (such as {@code PullRequestStateServiceIntegrationTest})
 * reads them too.
 */
@AutoConfigureMockMvc
class PullRequestMergeIntegrationTest extends BaseTest {

    private static final String TOKEN = "merge-it-token";

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

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private GraphTemplateRepository templateRepo;

    @Autowired
    private TemplateNodeRepository templateNodeRepo;

    @Autowired
    private WorkflowRunRepository runRepo;

    @Autowired
    private NodeExecutionRepository execRepo;

    @Autowired
    private GitRepoRepository gitRepoRepo;

    @Autowired
    private RunPullRequestRepository prRepo;

    @Autowired
    private JdbcTemplate jdbc;

    private CommittedFixtureCleaner cleaner;
    private WorkflowStub stub;
    private UUID runId;
    private UUID gateExecId;
    private RunPullRequest prA;
    private RunPullRequest prB;
    private String ownerA;
    private String ownerB;

    @BeforeEach
    void setUp() {
        cleaner = new CommittedFixtureCleaner(jdbc);
        stub = mock(WorkflowStub.class);
        when(workflowClient.newUntypedWorkflowStub(anyString())).thenReturn(stub);
        when(credentialResolver.getTokenForRun(any())).thenReturn(TOKEN);

        GitRepo repoA = saveRepo("merge-it-a");
        GitRepo repoB = saveRepo("merge-it-b");
        cleaner.trackSoftwareProject(repoA.getId());
        cleaner.trackSoftwareProject(repoB.getId());
        ownerA = RepoNameUtil.deriveOwnerRepoName(repoA.getUrl());
        ownerB = RepoNameUtil.deriveOwnerRepoName(repoB.getUrl());

        // The real seeded gate, so these tests also prove the shipped template merges on approval.
        GraphTemplate featureDev = templateRepo
                .findFirstByGraphIdOrderByVersionDesc(GraphIds.FEATURE_DEVELOPMENT)
                .orElseThrow();
        TemplateNode finalApproval = templateNodeRepo.findByGraphTemplateId(featureDev.getId()).stream()
                .filter(n -> "final_approval".equals(n.getLabel()))
                .findFirst()
                .orElseThrow();

        WorkflowRun run = new WorkflowRun();
        run.setGraphTemplateId(featureDev.getId());
        run.setStatus(WorkflowRunStatus.running);
        run.setExternalRunId("merge-it-" + UUID.randomUUID());
        run.setInputs("{\"software_project_id\":\"" + repoA.getId() + "\"}");
        runId = cleaner.trackWorkflowRun(runRepo.save(run).getId());

        NodeExecution exec = new NodeExecution();
        exec.setWorkflowRunId(runId);
        exec.setTemplateNodeId(finalApproval.getId());
        exec.setGraphVersion(1);
        exec.setIteration(1);
        exec.setStatus(NodeExecutionStatus.awaiting_human);
        exec.setArtifactRefs("{}");
        gateExecId = execRepo.save(exec).getId();

        Instant registered = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        prA = savePr(repoA, ownerA, 11, registered.minusSeconds(60));
        prB = savePr(repoB, ownerB, 22, registered);
    }

    @Test
    void approve_mergesEveryOpenPr_recordsRows_auditsOnce_andSignalsWithTheNote() throws Exception {
        stubOpen(ownerA, 11, "head-a");
        stubOpen(ownerB, 22, "head-b");
        when(gitHubAppService.mergePullRequest(TOKEN, ownerA, 11, "squash", "head-a"))
                .thenReturn("merge-a");
        when(gitHubAppService.mergePullRequest(TOKEN, ownerB, 22, "squash", "head-b"))
                .thenReturn("merge-b");

        approve().andExpect(status().isOk());

        assertMerged(prA);
        assertMerged(prB);
        verify(auditSink, times(1))
                .record(eq(AuditSink.PULL_REQUESTS_MERGED), eq("workflow_run"), eq(runId), anyString());
        assertThat(signalPayload())
                .contains("Pull requests merged on approval (squash): " + ownerA + "#11, " + ownerB + "#22.");
    }

    @Test
    void refusal_returns409_persistsNoDecisionState_auditsWhatMerged_andARetryMergesOnlyTheRest() throws Exception {
        stubOpen(ownerA, 11, "head-a");
        stubOpen(ownerB, 22, "head-b");
        when(gitHubAppService.mergePullRequest(TOKEN, ownerA, 11, "squash", "head-a"))
                .thenReturn("merge-a");
        when(gitHubAppService.mergePullRequest(TOKEN, ownerB, 22, "squash", "head-b"))
                .thenThrow(new GitHubMergeRefusedException(
                        405, ownerB, 22, "At least 1 approving review is required", GitHubRateLimitHints.NONE));

        String body = approve()
                .andExpect(status().isConflict())
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(body)
                .contains("Merged: " + ownerA + "#11.")
                .contains("Could not merge " + ownerB + "#22: At least 1 approving review is required")
                .contains("The gate is still open");
        assertThat(execRepo.findById(gateExecId).orElseThrow().getStatus())
                .isEqualTo(NodeExecutionStatus.awaiting_human);
        assertThat(prRepo.findById(prA.getId()).orElseThrow().getMergedAt()).isNull();
        assertThat(prRepo.findById(prB.getId()).orElseThrow().getMergedAt()).isNull();
        verifyNoInteractions(stub);
        assertThat(auditedDetail()).contains(prA.getPrUrl()).contains("\"refusedPullRequest\":\"" + prB.getPrUrl());

        // The reviewer approves B on GitHub and approves again: A now reads as merged, B merges.
        clearInvocations(auditSink);
        when(gitHubAppService.fetchPullRequestDetail(TOKEN, ownerA, 11))
                .thenReturn(new GitHubAppService.PullRequestDetail(
                        "closed", true, Instant.now(), false, null, "head-a", "main"));
        // doReturn, not when(): the method is still stubbed to throw, and when() would invoke it.
        doReturn("merge-b").when(gitHubAppService).mergePullRequest(TOKEN, ownerB, 22, "squash", "head-b");

        approve().andExpect(status().isOk());

        verify(gitHubAppService, times(1)).mergePullRequest(TOKEN, ownerA, 11, "squash", "head-a");
        assertMerged(prA);
        assertMerged(prB);
        String retryAudit = auditedDetail();
        assertThat(retryAudit).contains(prB.getPrUrl()).doesNotContain(prA.getPrUrl());
    }

    @Test
    void retryAfterASignalFailure_needsNeitherGitHubNorACredential_andAuditsNothingNew() throws Exception {
        stubOpen(ownerA, 11, "head-a");
        stubOpen(ownerB, 22, "head-b");
        when(gitHubAppService.mergePullRequest(any(), any(), anyInt(), any(), any()))
                .thenReturn("merge-sha");
        doThrow(new RuntimeException("temporal unavailable"))
                .doNothing()
                .when(stub)
                .signal(anyString(), any());

        approve().andExpect(status().is5xxServerError());

        // Merges are recorded before the signal, so the failed signal leaves them on the rows.
        assertMerged(prA);
        assertMerged(prB);
        assertThat(execRepo.findById(gateExecId).orElseThrow().getStatus())
                .isEqualTo(NodeExecutionStatus.awaiting_human);

        clearInvocations(gitHubAppService, credentialResolver, auditSink);

        approve().andExpect(status().isOk());

        verifyNoInteractions(gitHubAppService, credentialResolver);
        verify(auditSink, never()).record(eq(AuditSink.PULL_REQUESTS_MERGED), any(), any(), any());
        assertThat(signalPayload()).contains("Already merged: " + ownerA + "#11, " + ownerB + "#22.");
    }

    @Test
    void rereviewDecision_neverTouchesGitHubOrTheCredential() throws Exception {
        mockMvc.perform(post("/api/v1/runs/{id}/nodes/{exec}/signal", runId, gateExecId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"rereview\"}"))
                .andExpect(status().isOk());

        verifyNoInteractions(gitHubAppService, credentialResolver);
        assertThat(prRepo.findById(prA.getId()).orElseThrow().getMergedAt()).isNull();
    }

    @AfterEach
    void removeEverythingThisTestCommitted() {
        cleaner.deleteAll();
    }

    // --- helpers ---

    private ResultActions approve() throws Exception {
        return mockMvc.perform(post("/api/v1/runs/{id}/nodes/{exec}/signal", runId, gateExecId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"decision\":\"approved\"}"));
    }

    private void stubOpen(String ownerRepo, int number, String headSha) {
        when(gitHubAppService.fetchPullRequestDetail(TOKEN, ownerRepo, number))
                .thenReturn(new GitHubAppService.PullRequestDetail(
                        "open", false, null, false, Boolean.TRUE, headSha, "main"));
    }

    private void assertMerged(RunPullRequest pr) {
        RunPullRequest row = prRepo.findById(pr.getId()).orElseThrow();
        assertThat(row.getMergedAt()).as("mergedAt of %s", pr.getPrUrl()).isNotNull();
        assertThat(row.getState()).isEqualTo(PullRequestState.closed);
    }

    private String signalPayload() {
        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(stub, atLeastOnce()).signal(eq("human-decision-" + gateExecId), payload.capture());
        return payload.getValue().toString();
    }

    private String auditedDetail() {
        ArgumentCaptor<String> detail = ArgumentCaptor.forClass(String.class);
        verify(auditSink).record(eq(AuditSink.PULL_REQUESTS_MERGED), eq("workflow_run"), eq(runId), detail.capture());
        return detail.getValue();
    }

    private GitRepo saveRepo(String prefix) {
        String url = "https://github.com/" + prefix + "/repo-" + UUID.randomUUID();
        GitRepo repo = new GitRepo();
        repo.setUrl(url);
        repo.setName(RepoNameUtil.deriveOwnerRepoName(url));
        repo.setSecrets("[]");
        return gitRepoRepo.save(repo);
    }

    private RunPullRequest savePr(GitRepo repo, String ownerRepo, int number, Instant createdAt) {
        RunPullRequest pr = new RunPullRequest();
        pr.setWorkflowRunId(runId);
        pr.setGitRepoId(repo.getId());
        pr.setPrUrl("https://github.com/" + ownerRepo + "/pull/" + number);
        pr.setPrNumber(number);
        pr.setRepoName(ownerRepo);
        pr.setCreatedAt(createdAt);
        return prRepo.save(pr);
    }
}
