package com.choruskube.core.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.choruskube.core.credential.GitHubCredentialResolver;
import com.choruskube.core.exception.GateRecheckUnavailableException;
import com.choruskube.core.exception.GitHubApiException;
import com.choruskube.core.exception.GitHubRateLimitHints;
import com.choruskube.core.model.GitRepo;
import com.choruskube.core.model.NodeExecution;
import com.choruskube.core.model.NodeExecutionRepoBase;
import com.choruskube.core.model.RunPullRequest;
import com.choruskube.core.model.enums.NodeExecutionStatus;
import com.choruskube.core.repository.GitRepoRepository;
import com.choruskube.core.repository.NodeExecutionRepoBaseRepository;
import com.choruskube.core.repository.NodeExecutionRepository;
import com.choruskube.core.repository.RunPullRequestRepository;
import com.choruskube.core.service.GateDriftCheckService.DriftCheck;
import com.choruskube.core.service.GateDriftCheckService.Reason;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class GateDriftCheckServiceTest {

    private static final String TOKEN = "drift-token";

    @Mock
    private NodeExecutionRepository execRepo;

    @Mock
    private NodeExecutionRepoBaseRepository baseRepo;

    @Mock
    private GitRepoRepository gitRepoRepo;

    @Mock
    private RunPullRequestRepository prRepo;

    @Mock
    private GitHubCredentialResolver credentialResolver;

    @Mock
    private GitHubAppService gitHubAppService;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private GateDriftCheckService service;

    private final UUID runId = UUID.randomUUID();
    private final UUID repoId = UUID.randomUUID();
    private final UUID testNodeId = UUID.randomUUID();
    private final UUID gateNodeId = UUID.randomUUID();
    private final Instant started = Instant.parse("2026-10-11T10:00:00Z");
    private NodeExecution predecessor;

    @BeforeEach
    void setUp() {
        service = new GateDriftCheckService(
                execRepo, baseRepo, gitRepoRepo, prRepo, credentialResolver, gitHubAppService, objectMapper);

        predecessor = execution(testNodeId, started, started.plusSeconds(60));
        NodeExecution laterGate = execution(gateNodeId, started.plusSeconds(70), started.plusSeconds(80));
        when(execRepo.findByWorkflowRunIdAndStatus(runId, NodeExecutionStatus.completed))
                .thenReturn(List.of(predecessor, laterGate));

        GitRepo repo = new GitRepo();
        repo.setId(repoId);
        repo.setUrl("https://github.com/acme/app");
        when(gitRepoRepo.findById(repoId)).thenReturn(Optional.of(repo));
        when(credentialResolver.getTokenForRun(runId)).thenReturn(TOKEN);
    }

    @Test
    void current_whenCompareSaysZero() throws Exception {
        recordBase("clean", "abc1234def", "[]");
        when(gitHubAppService.compareCommits(TOKEN, "acme/app", "abc1234def", "main"))
                .thenReturn(0);

        DriftCheck check = service.check(runId, gateNodeId, snapshot(true));

        assertThat(check.stale()).isFalse();
        assertThat(check.repos())
                .extracting(GateDriftCheckService.RepoDrift::reason)
                .containsExactly(Reason.CURRENT);
    }

    @Test
    void mainMoved_whenAheadByPositive() throws Exception {
        recordBase("clean", "abc1234def", "[]");
        when(gitHubAppService.compareCommits(TOKEN, "acme/app", "abc1234def", "main"))
                .thenReturn(3);

        DriftCheck check = service.check(runId, gateNodeId, snapshot(true));

        assertThat(check.stale()).isTrue();
        assertThat(check.staleRepos()).singleElement().satisfies(r -> {
            assertThat(r.reason()).isEqualTo(Reason.MAIN_MOVED);
            assertThat(r.aheadBy()).isEqualTo(3);
            assertThat(r.summary()).contains("3 commits behind main");
        });
    }

    @Test
    void conflictRecord_isStaleWithFiles_andNeverCallsGitHub() throws Exception {
        recordBase("conflict", null, "[\"src/a.java\",\"db/V9__x.sql\"]");

        DriftCheck check = service.check(runId, gateNodeId, snapshot(true));

        assertThat(check.staleRepos()).singleElement().satisfies(r -> {
            assertThat(r.reason()).isEqualTo(Reason.CONFLICT);
            assertThat(r.conflictedFiles()).containsExactly("src/a.java", "db/V9__x.sql");
        });
        verify(gitHubAppService, never()).compareCommits(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void unavailableRecord_isStale() throws Exception {
        recordBase("unavailable", null, "[]");
        assertThat(service.check(runId, gateNodeId, snapshot(true)).staleRepos())
                .extracting(GateDriftCheckService.RepoDrift::reason)
                .containsExactly(Reason.UNAVAILABLE);
    }

    @Test
    void missingRecord_isStale() throws Exception {
        when(baseRepo.findByNodeExecutionId(predecessor.getId())).thenReturn(List.of());
        assertThat(service.check(runId, gateNodeId, snapshot(true)).staleRepos())
                .extracting(GateDriftCheckService.RepoDrift::reason)
                .containsExactly(Reason.NO_RECORD);
    }

    @Test
    void compare404_isBaseGone() throws Exception {
        recordBase("clean", "abc1234def", "[]");
        when(gitHubAppService.compareCommits(TOKEN, "acme/app", "abc1234def", "main"))
                .thenThrow(new GitHubApiException(404, "acme/app", GitHubRateLimitHints.NONE));

        assertThat(service.check(runId, gateNodeId, snapshot(true)).staleRepos())
                .extracting(GateDriftCheckService.RepoDrift::reason)
                .containsExactly(Reason.BASE_GONE);
    }

    @Test
    void compare502_throwsUnavailable() throws Exception {
        recordBase("clean", "abc1234def", "[]");
        when(gitHubAppService.compareCommits(TOKEN, "acme/app", "abc1234def", "main"))
                .thenThrow(new GitHubApiException(502, "acme/app", GitHubRateLimitHints.NONE));

        assertThatThrownBy(() -> service.check(runId, gateNodeId, snapshot(true)))
                .isInstanceOf(GateRecheckUnavailableException.class)
                .hasMessageContaining("acme/app")
                .hasMessageContaining("The gate is still open");
    }

    @Test
    void credentialFailure_throwsUnavailable() throws Exception {
        recordBase("clean", "abc1234def", "[]");
        when(credentialResolver.getTokenForRun(runId)).thenThrow(new IllegalStateException("no credential"));

        assertThatThrownBy(() -> service.check(runId, gateNodeId, snapshot(true)))
                .isInstanceOf(GateRecheckUnavailableException.class);
    }

    @Test
    void recordedDefaultBranch_isComparedEvenWhenTheRepoSettingSaysOtherwise() throws Exception {
        recordBase("clean", "abc1234def", "[]", "trunk");
        when(gitHubAppService.compareCommits(TOKEN, "acme/app", "abc1234def", "trunk"))
                .thenReturn(2);

        DriftCheck check = service.check(runId, gateNodeId, snapshot(true));

        assertThat(check.staleRepos()).singleElement().satisfies(r -> {
            assertThat(r.reason()).isEqualTo(Reason.MAIN_MOVED);
            assertThat(r.defaultBranch()).isEqualTo("trunk");
            assertThat(r.summary()).contains("2 commits behind trunk");
        });
        verify(gitHubAppService, never()).compareCommits(anyString(), anyString(), anyString(), eq("main"));
    }

    @Test
    void noRecordedDefaultBranch_fallsBackToTheRepoSetting() throws Exception {
        GitRepo repo = gitRepoRepo.findById(repoId).orElseThrow();
        repo.setDefaultBranch("develop");
        recordBase("clean", "abc1234def", "[]", null);
        when(gitHubAppService.compareCommits(TOKEN, "acme/app", "abc1234def", "develop"))
                .thenReturn(1);

        DriftCheck check = service.check(runId, gateNodeId, snapshot(true));

        assertThat(check.staleRepos()).singleElement().satisfies(r -> {
            assertThat(r.reason()).isEqualTo(Reason.MAIN_MOVED);
            assertThat(r.defaultBranch()).isEqualTo("develop");
        });
    }

    @Test
    void conflictRecord_namesTheBranchThePodRebasedOnto() throws Exception {
        recordBase("conflict", null, "[\"src/a.java\"]", "trunk");

        assertThat(service.check(runId, gateNodeId, snapshot(true)).staleRepos())
                .singleElement()
                .satisfies(r -> assertThat(r.summary()).isEqualTo("rebasing onto trunk conflicted in src/a.java"));
    }

    @Test
    void humanExecutionCompletedLater_isNotThePredecessor() throws Exception {
        recordBase("clean", "abc1234def", "[]");
        when(gitHubAppService.compareCommits(any(), any(), any(), any())).thenReturn(0);

        DriftCheck check = service.check(runId, gateNodeId, snapshot(true));

        assertThat(check.predecessor()).isSameAs(predecessor);
        verify(baseRepo).findByNodeExecutionId(predecessor.getId());
    }

    @Test
    void parallelBranchFinishedLater_theGatesUpstreamNodeIsThePredecessor() throws Exception {
        UUID offPathNodeId = UUID.randomUUID();
        NodeExecution offPath = execution(offPathNodeId, started.plusSeconds(30), started.plusSeconds(90));
        NodeExecution gate = execution(gateNodeId, started.plusSeconds(95), started.plusSeconds(100));
        when(execRepo.findByWorkflowRunIdAndStatus(runId, NodeExecutionStatus.completed))
                .thenReturn(List.of(predecessor, offPath, gate));
        recordBase("clean", "abc1234def", "[]");
        when(gitHubAppService.compareCommits(TOKEN, "acme/app", "abc1234def", "main"))
                .thenReturn(2);

        DriftCheck check = service.check(runId, gateNodeId, parallelSnapshot(offPathNodeId));

        assertThat(check.predecessor()).isSameAs(predecessor);
        assertThat(check.staleRepos())
                .extracting(GateDriftCheckService.RepoDrift::reason)
                .containsExactly(Reason.MAIN_MOVED);
        verify(baseRepo, never()).findByNodeExecutionId(offPath.getId());
    }

    @Test
    void noUpstreamNodeCompleted_fallsBackToTheLatestCompletedNonHumanExecution() throws Exception {
        UUID offPathNodeId = UUID.randomUUID();
        NodeExecution offPath = execution(offPathNodeId, started.plusSeconds(30), started.plusSeconds(90));
        when(execRepo.findByWorkflowRunIdAndStatus(runId, NodeExecutionStatus.completed))
                .thenReturn(List.of(offPath));
        when(baseRepo.findByNodeExecutionId(offPath.getId())).thenReturn(List.of());

        DriftCheck check = service.check(runId, gateNodeId, parallelSnapshot(offPathNodeId));

        assertThat(check.predecessor()).isSameAs(offPath);
    }

    @Test
    void noRepos_isCurrent() throws Exception {
        assertThat(service.check(runId, gateNodeId, snapshot(false)).stale()).isFalse();
        verify(credentialResolver, never()).getTokenForRun(any());
    }

    @Test
    void noPredecessor_isCurrent() throws Exception {
        when(execRepo.findByWorkflowRunIdAndStatus(runId, NodeExecutionStatus.completed))
                .thenReturn(List.of());
        assertThat(service.check(runId, gateNodeId, snapshot(true)).stale()).isFalse();
    }

    @Test
    void guidance_listsStaleReposAndOtherRunsMergedPrs() throws Exception {
        recordBase("clean", "abc1234def", "[]");
        when(gitHubAppService.compareCommits(TOKEN, "acme/app", "abc1234def", "main"))
                .thenReturn(3);
        RunPullRequest merged = new RunPullRequest();
        merged.setPrUrl("https://github.com/acme/app/pull/9");
        merged.setTitle("Rename widget");
        when(prRepo.findMergedByOtherRunsSince(List.of(repoId), started, runId)).thenReturn(List.of(merged));

        String guidance = service.guidance(runId, service.check(runId, gateNodeId, snapshot(true)), 1);

        assertThat(guidance)
                .contains("`acme/app`: 3 commits behind main")
                .contains("base `abc1234`")
                .contains("pull/9 — Rename widget")
                .contains("round 1 of 2")
                .contains("review_conflict");
    }

    private void recordBase(String outcome, String sha, String files) {
        recordBase(outcome, sha, files, null);
    }

    private void recordBase(String outcome, String sha, String files, String defaultBranch) {
        NodeExecutionRepoBase base = new NodeExecutionRepoBase();
        base.setNodeExecutionId(predecessor.getId());
        base.setGitRepoId(repoId);
        base.setOutcome(outcome);
        base.setBaseSha(sha);
        base.setDefaultBranch(defaultBranch);
        base.setConflictedFiles(files);
        when(baseRepo.findByNodeExecutionId(predecessor.getId())).thenReturn(List.of(base));
    }

    private NodeExecution execution(UUID templateNodeId, Instant startedAt, Instant completedAt) {
        NodeExecution exec = new NodeExecution();
        exec.setId(UUID.randomUUID());
        exec.setWorkflowRunId(runId);
        exec.setTemplateNodeId(templateNodeId);
        exec.setStatus(NodeExecutionStatus.completed);
        exec.setStartedAt(startedAt);
        exec.setCompletedAt(completedAt);
        return exec;
    }

    private JsonNode snapshot(boolean withRepo) throws Exception {
        String repos = withRepo
                ? "[{\"id\":\"" + repoId + "\",\"url\":\"https://github.com/acme/app\",\"name\":\"app\"}]"
                : "[]";
        return objectMapper.readTree("""
                {"nodes":[
                  {"template_node_id":"%s","label":"test","executor_type":"script"},
                  {"template_node_id":"%s","label":"final_approval","executor_type":"human"}],
                 "edges":[],
                 "repos":%s}""".formatted(testNodeId, gateNodeId, repos));
    }

    /** {@code test} feeds the gate; {@code docs} runs on a parallel branch that ends elsewhere. */
    private JsonNode parallelSnapshot(UUID offPathNodeId) throws Exception {
        return objectMapper.readTree("""
                {"nodes":[
                  {"template_node_id":"%1$s","label":"test","executor_type":"script"},
                  {"template_node_id":"%2$s","label":"docs","executor_type":"ai"},
                  {"template_node_id":"%3$s","label":"final_approval","executor_type":"human"}],
                 "edges":[
                  {"source_node_id":"%1$s","target_node_id":"%3$s","condition":"passed"},
                  {"source_node_id":"%3$s","target_node_id":"%1$s","condition":"rereview"}],
                 "repos":[{"id":"%4$s","url":"https://github.com/acme/app","name":"app"}]}""".formatted(testNodeId, offPathNodeId, gateNodeId, repoId));
    }
}
