package com.choruskube.core.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.choruskube.core.BaseTest;
import com.choruskube.core.CommittedFixtureCleaner;
import com.choruskube.core.config.GraphIds;
import com.choruskube.core.credential.GitHubCredentialResolver;
import com.choruskube.core.model.DriftRecheckConfig.StampMode;
import com.choruskube.core.model.Epic;
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
import com.choruskube.core.repository.EpicRepository;
import com.choruskube.core.repository.GateStampRepository;
import com.choruskube.core.repository.GitRepoRepository;
import com.choruskube.core.repository.GraphTemplateRepository;
import com.choruskube.core.repository.NodeExecutionRepoBaseRepository;
import com.choruskube.core.repository.NodeExecutionRepository;
import com.choruskube.core.repository.RunPullRequestRepository;
import com.choruskube.core.repository.TemplateNodeRepository;
import com.choruskube.core.repository.WorkflowRunRepository;
import com.choruskube.core.util.RepoNameUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jayway.jsonpath.JsonPath;
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
 * The shipped Final Approval on the asynchronous path, against a real database: the click claims
 * the gate and replies at once, and the internal steps the workflow drives merge and write the
 * roadmap exactly once, send a refused approval back to waiting, and re-apply a held one.
 */
@AutoConfigureMockMvc
class AsyncGateFinalizeIntegrationTest extends BaseTest {

    private static final String STEPS = "/internal/runs/{runId}/node-executions/{exec}/gate-finalize";

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
    private EpicRepository epicRepo;

    @Autowired
    private JdbcTemplate jdbc;

    private CommittedFixtureCleaner cleaner;
    private UUID runId;
    private UUID repoId;
    private String ownerRepo;
    private TemplateNode testNode;
    private TemplateNode finalApproval;
    private NodeExecution gateExec;
    private WorkflowStub stub;

    @BeforeEach
    void setUp() {
        cleaner = new CommittedFixtureCleaner(jdbc);
        stub = mock(WorkflowStub.class);
        when(workflowClient.newUntypedWorkflowStub(anyString())).thenReturn(stub);

        String url = "https://github.com/async-it/repo-" + UUID.randomUUID();
        GitRepo repo = new GitRepo();
        repo.setUrl(url);
        repo.setName(RepoNameUtil.deriveOwnerRepoName(url));
        repo.setSecrets("[]");
        repoId = cleaner.trackSoftwareProject(gitRepoRepo.save(repo).getId());
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
        run.setExternalRunId("async-it-" + UUID.randomUUID());
        run.setInputs("{\"software_project_id\":\"" + repoId + "\"}");
        runId = cleaner.trackWorkflowRun(runRepo.save(run).getId());

        completedTestRun(Instant.now().minusSeconds(120).truncatedTo(ChronoUnit.MILLIS), "base-1", 1);
        gateExec = asyncGate(NodeExecutionStatus.awaiting_human, 1);
    }

    @AfterEach
    void removeEverythingThisTestCommitted() {
        epicRepo.findBySoftwareProjectIdOrderByCreatedAtDesc(repoId).forEach(e -> cleaner.trackEpic(e.getId()));
        cleaner.deleteAll();
    }

    @Test
    void approve_repliesFinalizing_thenPlanCompleteMerges_materializesOnce() throws Exception {
        RunPullRequest pr = pullRequest(11, "https://github.com/" + ownerRepo + "/pull/11");
        when(credentialResolver.getTokenForRepo(repoId)).thenReturn("issue-token");
        when(gitHubAppService.createIssue(eq("issue-token"), eq(ownerRepo), eq("Do the thing"), any()))
                .thenReturn(new GitHubAppService.CreatedIssue(42, "https://github.com/" + ownerRepo + "/issues/42"));

        mockMvc.perform(post("/api/v1/runs/{id}/nodes/{exec}/signal", runId, gateExec.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"approved\",\"feedback\":\"ship it\","
                                + "\"editedCandidates\":{\"epics\":[{\"title\":\"Async follow-up\",\"stories\":[{\"title\":\"First slice\","
                                + "\"tasks\":[{\"title\":\"Do the thing\"}]}]}]}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("finalizing"));

        assertThat(execRepo.findById(gateExec.getId()).orElseThrow().getStatus())
                .isEqualTo(NodeExecutionStatus.finalizing);
        List<GateStamp> stamps = stampRepo.findByWorkflowRunIdOrderByCreatedAtAsc(runId);
        assertThat(stamps).singleElement().extracting(GateStamp::getStatus).isEqualTo(GateStampStatus.finalizing);
        UUID approvalId = stamps.get(0).getId();
        assertThat(payload()).contains("approvalId=" + approvalId);
        verifyNoInteractions(gitHubAppService, credentialResolver);

        mockMvc.perform(post(STEPS + "/{approval}/plan", runId, gateExec.getId(), approvalId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("proceed"))
                .andExpect(jsonPath("$.compares.length()").value(1))
                .andExpect(jsonPath("$.compares[0].gitRepoId").value(repoId.toString()))
                .andExpect(jsonPath("$.compares[0].ownerRepo").value(ownerRepo))
                .andExpect(jsonPath("$.compares[0].branch").value("main"))
                .andExpect(jsonPath("$.compares[0].baseSha").value("base-1"))
                .andExpect(jsonPath("$.prs.length()").value(1))
                .andExpect(jsonPath("$.prs[0].rowId").value(pr.getId().toString()))
                .andExpect(jsonPath("$.prs[0].ownerRepo").value(ownerRepo))
                .andExpect(jsonPath("$.prs[0].number").value(11))
                .andExpect(jsonPath("$.prs[0].label").value(ownerRepo + "#11"))
                .andExpect(jsonPath("$.prs[0].prUrl").value(pr.getPrUrl()))
                .andExpect(jsonPath("$.mergeMethod").value("squash"));

        String merged = "{\"merges\":[{\"rowId\":\"" + pr.getId()
                + "\",\"kind\":\"MERGED\",\"mergedAt\":\"2026-10-11T10:00:00Z\",\"mergeSha\":\"m1\"}]}";
        String first = mockMvc.perform(post(STEPS + "/{approval}/complete", runId, gateExec.getId(), approvalId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(merged))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("completed"))
                .andExpect(jsonPath("$.attachmentRefs").value("{}"))
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(first)
                .contains("ship it")
                .contains("Pull requests merged on approval (squash): " + ownerRepo + "#11.")
                .contains("Roadmap extension approved: created 1 Epics, 1 Stories, 1 Tasks");
        assertThat(prRepo.findById(pr.getId()).orElseThrow().getMergedAt())
                .isEqualTo(Instant.parse("2026-10-11T10:00:00Z"));
        GateStamp consumed = stampRepo.findById(approvalId).orElseThrow();
        assertThat(consumed.getStatus()).isEqualTo(GateStampStatus.consumed);
        assertThat(consumed.getMaterializedAt()).isNotNull();
        assertThat(execRepo.findById(gateExec.getId()).orElseThrow().getDecision())
                .isEqualTo("approved");
        assertThat(epicRepo.findBySoftwareProjectIdOrderByCreatedAtDesc(repoId))
                .extracting(Epic::getTitle)
                .containsExactly("Async follow-up");

        String second = mockMvc.perform(post(STEPS + "/{approval}/complete", runId, gateExec.getId(), approvalId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(merged))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("completed"))
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(second).isEqualTo(first);
        assertThat(epicRepo.findBySoftwareProjectIdOrderByCreatedAtDesc(repoId)).hasSize(1);
        assertThat(stampRepo.findById(approvalId).orElseThrow().getMaterializedAt())
                .isEqualTo(consumed.getMaterializedAt());
        verify(auditSink, times(1)).record(eq(AuditSink.PULL_REQUESTS_MERGED), eq("workflow_run"), eq(runId), any());
        verify(gitHubAppService, times(1)).createIssue(any(), any(), any(), any());
    }

    @Test
    void complete_anEdgeTheWritePathRejects_isSkippedAndNoted_andTheRestIsWrittenOnce() throws Exception {
        when(credentialResolver.getTokenForRepo(repoId)).thenReturn("issue-token");
        when(gitHubAppService.createIssue(eq("issue-token"), eq(ownerRepo), any(), any()))
                .thenReturn(new GitHubAppService.CreatedIssue(7, "https://github.com/" + ownerRepo + "/issues/7"));
        // GATE strictness leaves dependency checks to the write path, which rejects the repeat.
        String document = "{\"epics\":[{\"title\":\"Edge case\",\"stories\":[{\"title\":\"Slice\",\"tasks\":["
                + "{\"title\":\"Build it\",\"key\":\"t1\"},{\"title\":\"Ship it\",\"key\":\"t2\"}]}]}],"
                + "\"dependencies\":[{\"blocking\":\"t1\",\"blocked\":\"t2\"},{\"blocking\":\"t1\",\"blocked\":\"t2\"}]}";

        mockMvc.perform(post("/api/v1/runs/{id}/nodes/{exec}/signal", runId, gateExec.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"approved\",\"editedCandidates\":" + document + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("finalizing"));
        UUID approvalId =
                stampRepo.findByWorkflowRunIdOrderByCreatedAtAsc(runId).get(0).getId();
        mockMvc.perform(post(STEPS + "/{approval}/plan", runId, gateExec.getId(), approvalId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("proceed"));

        String first = mockMvc.perform(post(STEPS + "/{approval}/complete", runId, gateExec.getId(), approvalId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"merges\":[]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("completed"))
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(first)
                .contains("created 1 Epics, 1 Stories, 2 Tasks and 1 dependency edges (1 skipped)")
                .contains("Skipped: Failed to materialize dependency edge 't1' -> 't2'")
                .contains("This dependency edge already exists");
        assertThat(roadmapCounts()).containsExactly(1, 2, 1);
        GateStamp consumed = stampRepo.findById(approvalId).orElseThrow();
        assertThat(consumed.getStatus()).isEqualTo(GateStampStatus.consumed);
        assertThat(consumed.getMaterializationNote()).contains("This dependency edge already exists");

        String second = mockMvc.perform(post(STEPS + "/{approval}/complete", runId, gateExec.getId(), approvalId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"merges\":[]}"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(second).isEqualTo(first);
        assertThat(roadmapCounts()).containsExactly(1, 2, 1);
        verify(gitHubAppService, times(2)).createIssue(any(), any(), any(), any());
    }

    @Test
    void refusedPlan_returnsGateToWaiting_withReasonOnTheApproval() throws Exception {
        pullRequest(null, "https://github.com/" + ownerRepo + "/tree/some-branch");

        mockMvc.perform(post("/api/v1/runs/{id}/nodes/{exec}/signal", runId, gateExec.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"approved\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("finalizing"));
        UUID approvalId =
                stampRepo.findByWorkflowRunIdOrderByCreatedAtAsc(runId).get(0).getId();

        String reason = JsonPath.read(
                mockMvc.perform(post(STEPS + "/{approval}/plan", runId, gateExec.getId(), approvalId))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.outcome").value("refused"))
                        .andReturn()
                        .getResponse()
                        .getContentAsString(),
                "$.reason");
        assertThat(reason).contains("cannot determine its repository or number");

        mockMvc.perform(post(STEPS + "/{approval}/refuse", runId, gateExec.getId(), approvalId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":" + new ObjectMapper().writeValueAsString(reason) + ",\"merges\":null}"))
                .andExpect(status().isNoContent());

        assertThat(execRepo.findById(gateExec.getId()).orElseThrow().getStatus())
                .isEqualTo(NodeExecutionStatus.awaiting_human);
        GateStamp voided = stampRepo.findById(approvalId).orElseThrow();
        assertThat(voided.getStatus()).isEqualTo(GateStampStatus.voided);
        assertThat(voided.getVoidReason()).isEqualTo(reason);
        assertThat(voided.getParkedNodeExecutionId()).isEqualTo(gateExec.getId());

        // A repeated refuse changes nothing, and the gate takes a fresh approval.
        mockMvc.perform(post(STEPS + "/{approval}/refuse", runId, gateExec.getId(), approvalId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"again\"}"))
                .andExpect(status().isNoContent());
        assertThat(stampRepo.findById(approvalId).orElseThrow().getVoidReason()).isEqualTo(reason);
        mockMvc.perform(post("/api/v1/runs/{id}/nodes/{exec}/signal", runId, gateExec.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"approved\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("finalizing"));
    }

    @Test
    void heldApproval_claimedOnGateOpen() throws Exception {
        GateStamp held = pendingStampAt(heldAtTheFirstGate());
        NodeExecution reopened = asyncGate(NodeExecutionStatus.awaiting_human, 2);

        mockMvc.perform(post(STEPS + "/held", runId, reopened.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.approvalId").value(held.getId().toString()));

        assertThat(execRepo.findById(reopened.getId()).orElseThrow().getStatus())
                .isEqualTo(NodeExecutionStatus.finalizing);
        assertThat(stampRepo.findById(held.getId()).orElseThrow().getStatus()).isEqualTo(GateStampStatus.finalizing);

        // A retry of the same step finds its own claim rather than losing the approval.
        mockMvc.perform(post(STEPS + "/held", runId, reopened.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.approvalId").value(held.getId().toString()));
    }

    @Test
    void approve_whileAnApprovalIsHeld_replacesIt() throws Exception {
        GateStamp held = pendingStampAt(heldAtTheFirstGate());
        NodeExecution reopened = asyncGate(NodeExecutionStatus.awaiting_human, 2);

        mockMvc.perform(post("/api/v1/runs/{id}/nodes/{exec}/signal", runId, reopened.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"approved\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("finalizing"));

        GateStamp replaced = stampRepo.findById(held.getId()).orElseThrow();
        assertThat(replaced.getStatus()).isEqualTo(GateStampStatus.voided);
        assertThat(replaced.getVoidReason()).isEqualTo(GateRecheckService.REVIEWER_DECIDED);
        assertThat(stampRepo.findByWorkflowRunIdOrderByCreatedAtAsc(runId))
                .filteredOn(s -> s.getStatus() == GateStampStatus.finalizing)
                .singleElement()
                .extracting(GateStamp::getStampedNodeExecutionId)
                .isEqualTo(reopened.getId());

        // The workflow's own lookup now defers to the reviewer's approval, which arrives by signal.
        mockMvc.perform(post(STEPS + "/held", runId, reopened.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.approvalId").doesNotExist());
    }

    @Test
    void anApprovalOfAnotherRun_isNotFound() throws Exception {
        mockMvc.perform(post("/api/v1/runs/{id}/nodes/{exec}/signal", runId, gateExec.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"approved\"}"))
                .andExpect(status().isOk());
        UUID approvalId =
                stampRepo.findByWorkflowRunIdOrderByCreatedAtAsc(runId).get(0).getId();

        WorkflowRun other = new WorkflowRun();
        other.setGraphTemplateId(runRepo.findById(runId).orElseThrow().getGraphTemplateId());
        other.setStatus(WorkflowRunStatus.running);
        UUID otherRunId = cleaner.trackWorkflowRun(runRepo.save(other).getId());
        NodeExecution otherGate = new NodeExecution();
        otherGate.setWorkflowRunId(otherRunId);
        otherGate.setTemplateNodeId(finalApproval.getId());
        otherGate.setGraphVersion(1);
        otherGate.setStatus(NodeExecutionStatus.finalizing);
        otherGate.setFinalizeMode("async");
        otherGate = execRepo.save(otherGate);

        mockMvc.perform(post(STEPS + "/{approval}/plan", otherRunId, otherGate.getId(), approvalId))
                .andExpect(status().isNotFound());
        mockMvc.perform(post(STEPS + "/{approval}/plan", otherRunId, gateExec.getId(), approvalId))
                .andExpect(status().isNotFound());
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

    private NodeExecution asyncGate(NodeExecutionStatus status, int iteration) {
        NodeExecution exec = new NodeExecution();
        exec.setWorkflowRunId(runId);
        exec.setTemplateNodeId(finalApproval.getId());
        exec.setGraphVersion(1);
        exec.setIteration(iteration);
        exec.setStatus(status);
        exec.setFinalizeMode("async");
        exec.setArtifactRefs("{}");
        return execRepo.save(exec);
    }

    /** The first gate, completed down its re-check route as a held approval leaves it. */
    private NodeExecution heldAtTheFirstGate() {
        gateExec.setStatus(NodeExecutionStatus.completed);
        gateExec.setDecision("rereview");
        gateExec.setCompletedAt(Instant.now().minusSeconds(60));
        return execRepo.save(gateExec);
    }

    private GateStamp pendingStampAt(NodeExecution stampedAt) {
        GateStamp stamp = new GateStamp();
        stamp.setWorkflowRunId(runId);
        stamp.setGateTemplateNodeId(finalApproval.getId());
        stamp.setGateLabel("final_approval");
        stamp.setStampedNodeExecutionId(stampedAt.getId());
        stamp.setStampMode(StampMode.always);
        stamp.setRecheckRounds(1);
        stamp.setStatus(GateStampStatus.pending);
        return stampRepo.save(stamp);
    }

    /** Epics, Tasks and dependency edges written under this test's project. */
    private List<Integer> roadmapCounts() {
        String tasks = "SELECT t.id FROM task t JOIN story s ON t.story_id = s.id JOIN epic e ON s.epic_id = e.id"
                + " WHERE e.software_project_id = ?";
        return List.of(
                jdbc.queryForObject("SELECT count(*) FROM epic WHERE software_project_id = ?", Integer.class, repoId),
                jdbc.queryForObject("SELECT count(*) FROM (" + tasks + ") x", Integer.class, repoId),
                jdbc.queryForObject(
                        "SELECT count(*) FROM work_item_dependency WHERE blocking_item_id IN (" + tasks + ")",
                        Integer.class,
                        repoId));
    }

    private RunPullRequest pullRequest(Integer number, String url) {
        RunPullRequest pr = new RunPullRequest();
        pr.setWorkflowRunId(runId);
        pr.setGitRepoId(repoId);
        pr.setPrUrl(url);
        pr.setPrNumber(number);
        pr.setRepoName(ownerRepo);
        return prRepo.save(pr);
    }

    private String payload() {
        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(stub, atLeastOnce()).signal(eq("human-decision-" + gateExec.getId()), payload.capture());
        return payload.getValue().toString();
    }
}
