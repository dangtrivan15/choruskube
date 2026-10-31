package com.choruskube.core.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.choruskube.core.model.GitRepo;
import com.choruskube.core.model.NodeExecution;
import com.choruskube.core.model.NodeExecutionRepoBase;
import com.choruskube.core.model.RunPullRequest;
import com.choruskube.core.model.enums.NodeExecutionStatus;
import com.choruskube.core.repository.GitRepoRepository;
import com.choruskube.core.repository.NodeExecutionRepoBaseRepository;
import com.choruskube.core.repository.NodeExecutionRepository;
import com.choruskube.core.repository.RunPullRequestRepository;
import com.choruskube.core.service.GateDriftCheckService.CompareInput;
import com.choruskube.core.service.GateDriftCheckService.CompareResult;
import com.choruskube.core.service.GateDriftCheckService.DriftCheck;
import com.choruskube.core.service.GateDriftCheckService.DriftInputs;
import com.choruskube.core.service.GateDriftCheckService.Reason;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
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

    @Mock
    private NodeExecutionRepository execRepo;

    @Mock
    private NodeExecutionRepoBaseRepository baseRepo;

    @Mock
    private GitRepoRepository gitRepoRepo;

    @Mock
    private RunPullRequestRepository prRepo;

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
        service = new GateDriftCheckService(execRepo, baseRepo, gitRepoRepo, prRepo, objectMapper);

        predecessor = execution(testNodeId, started, started.plusSeconds(60));
        NodeExecution laterGate = execution(gateNodeId, started.plusSeconds(70), started.plusSeconds(80));
        when(execRepo.findByWorkflowRunIdAndStatus(runId, NodeExecutionStatus.completed))
                .thenReturn(List.of(predecessor, laterGate));

        GitRepo repo = new GitRepo();
        repo.setId(repoId);
        repo.setUrl("https://github.com/acme/app");
        when(gitRepoRepo.findById(repoId)).thenReturn(Optional.of(repo));
    }

    @Test
    void current_whenCompareSaysZero() throws Exception {
        recordBase("clean", "abc1234def", "[]");

        DriftInputs inputs = service.gather(runId, gateNodeId, snapshot(true));
        DriftCheck check = service.classify(inputs, List.of(new CompareResult(repoId, 0, false)));

        assertThat(check.stale()).isFalse();
        assertThat(check.repos())
                .extracting(GateDriftCheckService.RepoDrift::reason)
                .containsExactly(Reason.CURRENT);
    }

    @Test
    void mainMoved_whenAheadByPositive() throws Exception {
        recordBase("clean", "abc1234def", "[]");

        DriftInputs inputs = service.gather(runId, gateNodeId, snapshot(true));
        DriftCheck check = service.classify(inputs, List.of(new CompareResult(repoId, 3, false)));

        assertThat(check.stale()).isTrue();
        assertThat(check.staleRepos()).singleElement().satisfies(r -> {
            assertThat(r.reason()).isEqualTo(Reason.MAIN_MOVED);
            assertThat(r.aheadBy()).isEqualTo(3);
            assertThat(r.summary()).contains("3 commits behind main");
        });
    }

    @Test
    void conflictRecord_isStaleWithFiles() throws Exception {
        recordBase("conflict", null, "[\"src/a.java\",\"db/V9__x.sql\"]");

        DriftInputs inputs = service.gather(runId, gateNodeId, snapshot(true));
        DriftCheck check = service.classify(inputs, List.of());

        assertThat(check.staleRepos()).singleElement().satisfies(r -> {
            assertThat(r.reason()).isEqualTo(Reason.CONFLICT);
            assertThat(r.conflictedFiles()).containsExactly("src/a.java", "db/V9__x.sql");
        });
        assertThat(inputs.toCompare()).isEmpty();
    }

    @Test
    void unavailableRecord_isStale() throws Exception {
        recordBase("unavailable", null, "[]");

        DriftInputs inputs = service.gather(runId, gateNodeId, snapshot(true));
        DriftCheck check = service.classify(inputs, List.of());

        assertThat(check.staleRepos())
                .extracting(GateDriftCheckService.RepoDrift::reason)
                .containsExactly(Reason.UNAVAILABLE);
    }

    @Test
    void missingRecord_isStale() throws Exception {
        when(baseRepo.findByNodeExecutionId(predecessor.getId())).thenReturn(List.of());

        DriftInputs inputs = service.gather(runId, gateNodeId, snapshot(true));
        DriftCheck check = service.classify(inputs, List.of());

        assertThat(check.staleRepos())
                .extracting(GateDriftCheckService.RepoDrift::reason)
                .containsExactly(Reason.NO_RECORD);
    }

    @Test
    void compareResult_baseGone_isClassifiedAsBaseGone() throws Exception {
        recordBase("clean", "abc1234def", "[]");

        DriftInputs inputs = service.gather(runId, gateNodeId, snapshot(true));
        DriftCheck check = service.classify(inputs, List.of(new CompareResult(repoId, 0, true)));

        assertThat(check.staleRepos())
                .extracting(GateDriftCheckService.RepoDrift::reason)
                .containsExactly(Reason.BASE_GONE);
    }

    @Test
    void recordedDefaultBranch_isComparedEvenWhenTheRepoSettingSaysOtherwise() throws Exception {
        recordBase("clean", "abc1234def", "[]", "trunk");

        DriftInputs inputs = service.gather(runId, gateNodeId, snapshot(true));

        assertThat(inputs.toCompare()).containsExactly(new CompareInput(repoId, "acme/app", "trunk", "abc1234def"));

        DriftCheck check = service.classify(inputs, List.of(new CompareResult(repoId, 2, false)));
        assertThat(check.staleRepos()).singleElement().satisfies(r -> {
            assertThat(r.reason()).isEqualTo(Reason.MAIN_MOVED);
            assertThat(r.defaultBranch()).isEqualTo("trunk");
            assertThat(r.summary()).contains("2 commits behind trunk");
        });
    }

    @Test
    void noRecordedDefaultBranch_fallsBackToTheRepoSetting() throws Exception {
        GitRepo repo = gitRepoRepo.findById(repoId).orElseThrow();
        repo.setDefaultBranch("develop");
        recordBase("clean", "abc1234def", "[]", null);

        DriftInputs inputs = service.gather(runId, gateNodeId, snapshot(true));
        DriftCheck check = service.classify(inputs, List.of(new CompareResult(repoId, 1, false)));

        assertThat(check.staleRepos()).singleElement().satisfies(r -> {
            assertThat(r.reason()).isEqualTo(Reason.MAIN_MOVED);
            assertThat(r.defaultBranch()).isEqualTo("develop");
        });
    }

    @Test
    void conflictRecord_namesTheBranchThePodRebasedOnto() throws Exception {
        recordBase("conflict", null, "[\"src/a.java\"]", "trunk");

        DriftInputs inputs = service.gather(runId, gateNodeId, snapshot(true));
        DriftCheck check = service.classify(inputs, List.of());

        assertThat(check.staleRepos()).singleElement().satisfies(r -> assertThat(r.summary())
                .isEqualTo("rebasing onto trunk conflicted in src/a.java"));
    }

    @Test
    void humanExecutionCompletedLater_isNotThePredecessor() throws Exception {
        recordBase("clean", "abc1234def", "[]");

        DriftInputs inputs = service.gather(runId, gateNodeId, snapshot(true));

        assertThat(inputs.predecessor()).isSameAs(predecessor);
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

        DriftInputs inputs = service.gather(runId, gateNodeId, parallelSnapshot(offPathNodeId));
        DriftCheck check = service.classify(inputs, List.of(new CompareResult(repoId, 2, false)));

        assertThat(inputs.predecessor()).isSameAs(predecessor);
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

        DriftInputs inputs = service.gather(runId, gateNodeId, parallelSnapshot(offPathNodeId));

        assertThat(inputs.predecessor()).isSameAs(offPath);
    }

    @Test
    void noRepos_isCurrent() throws Exception {
        DriftInputs inputs = service.gather(runId, gateNodeId, snapshot(false));
        DriftCheck check = service.classify(inputs, List.of());

        assertThat(check.stale()).isFalse();
    }

    @Test
    void noPredecessor_isCurrent() throws Exception {
        when(execRepo.findByWorkflowRunIdAndStatus(runId, NodeExecutionStatus.completed))
                .thenReturn(List.of());

        DriftInputs inputs = service.gather(runId, gateNodeId, snapshot(true));
        DriftCheck check = service.classify(inputs, List.of());

        assertThat(check.stale()).isFalse();
    }

    @Test
    void guidance_listsStaleReposAndOtherRunsMergedPrs() throws Exception {
        recordBase("clean", "abc1234def", "[]");
        RunPullRequest merged = new RunPullRequest();
        merged.setPrUrl("https://github.com/acme/app/pull/9");
        merged.setTitle("Rename widget");
        when(prRepo.findMergedByOtherRunsSince(List.of(repoId), started, runId)).thenReturn(List.of(merged));

        DriftInputs inputs = service.gather(runId, gateNodeId, snapshot(true));
        DriftCheck check = service.classify(inputs, List.of(new CompareResult(repoId, 3, false)));

        String guidance = service.guidance(runId, check, 1);

        assertThat(guidance)
                .contains("`acme/app`: 3 commits behind main")
                .contains("base `abc1234`")
                .contains("pull/9 — Rename widget")
                .contains("round 1 of 2")
                .contains("review_conflict");
    }

    @Test
    void gather_splitsKnownStaleFromReposToCompare() throws Exception {
        UUID cleanRepoId = UUID.randomUUID();
        GitRepo cleanRepo = new GitRepo();
        cleanRepo.setId(cleanRepoId);
        cleanRepo.setUrl("https://github.com/acme/lib");
        when(gitRepoRepo.findById(cleanRepoId)).thenReturn(Optional.of(cleanRepo));
        when(baseRepo.findByNodeExecutionId(predecessor.getId()))
                .thenReturn(List.of(
                        base(repoId, "conflict", null, "[\"src/a.java\"]", null),
                        base(cleanRepoId, "clean", "def5678abc", "[]", "trunk")));

        DriftInputs inputs = service.gather(runId, gateNodeId, snapshot(repoId, cleanRepoId));

        assertThat(inputs.predecessor()).isSameAs(predecessor);
        assertThat(inputs.known()).singleElement().satisfies(r -> {
            assertThat(r.reason()).isEqualTo(Reason.CONFLICT);
            assertThat(r.gitRepoId()).isEqualTo(repoId);
            assertThat(r.conflictedFiles()).containsExactly("src/a.java");
        });
        assertThat(inputs.toCompare())
                .containsExactly(new CompareInput(cleanRepoId, "acme/lib", "trunk", "def5678abc"));
    }

    @Test
    void classify_mapsCompareResults() {
        UUID moved = UUID.randomUUID();
        UUID current = UUID.randomUUID();
        UUID gone = UUID.randomUUID();
        UUID missing = UUID.randomUUID();
        DriftInputs inputs = new DriftInputs(
                predecessor,
                List.of(),
                List.of(
                        new CompareInput(moved, "acme/a", "main", "sha-a"),
                        new CompareInput(current, "acme/b", "main", "sha-b"),
                        new CompareInput(gone, "acme/c", "main", "sha-c"),
                        new CompareInput(missing, "acme/d", "main", "sha-d")));

        DriftCheck check = service.classify(
                inputs,
                List.of(
                        new CompareResult(moved, 2, false),
                        new CompareResult(current, 0, false),
                        new CompareResult(gone, 0, true)));

        assertThat(check.predecessor()).isSameAs(predecessor);
        assertThat(check.repos())
                .extracting(GateDriftCheckService.RepoDrift::gitRepoId, GateDriftCheckService.RepoDrift::reason)
                .containsExactlyInAnyOrder(
                        tuple(moved, Reason.MAIN_MOVED),
                        tuple(current, Reason.CURRENT),
                        tuple(gone, Reason.BASE_GONE),
                        tuple(missing, Reason.UNAVAILABLE));
        assertThat(check.repos().stream()
                        .filter(r -> r.gitRepoId().equals(moved))
                        .findFirst())
                .hasValueSatisfying(r -> {
                    assertThat(r.aheadBy()).isEqualTo(2);
                    assertThat(r.baseSha()).isEqualTo("sha-a");
                    assertThat(r.ownerRepo()).isEqualTo("acme/a");
                });
    }

    @Test
    void classify_keepsKnownStaleReposAlongsideComparedOnes() {
        UUID compared = UUID.randomUUID();
        GateDriftCheckService.RepoDrift known =
                new GateDriftCheckService.RepoDrift(repoId, "acme/app", "main", Reason.NO_RECORD, null, 0, List.of());
        DriftInputs inputs = new DriftInputs(
                predecessor, List.of(known), List.of(new CompareInput(compared, "acme/b", "main", "sha-b")));

        DriftCheck check = service.classify(inputs, List.of(new CompareResult(compared, 0, false)));

        assertThat(check.repos())
                .extracting(GateDriftCheckService.RepoDrift::reason)
                .containsExactlyInAnyOrder(Reason.NO_RECORD, Reason.CURRENT);
        assertThat(check.staleRepos()).containsExactly(known);
    }

    private void recordBase(String outcome, String sha, String files) {
        recordBase(outcome, sha, files, null);
    }

    private void recordBase(String outcome, String sha, String files, String defaultBranch) {
        when(baseRepo.findByNodeExecutionId(predecessor.getId()))
                .thenReturn(List.of(base(repoId, outcome, sha, files, defaultBranch)));
    }

    private NodeExecutionRepoBase base(UUID gitRepoId, String outcome, String sha, String files, String defaultBranch) {
        NodeExecutionRepoBase base = new NodeExecutionRepoBase();
        base.setNodeExecutionId(predecessor.getId());
        base.setGitRepoId(gitRepoId);
        base.setOutcome(outcome);
        base.setBaseSha(sha);
        base.setDefaultBranch(defaultBranch);
        base.setConflictedFiles(files);
        return base;
    }

    private JsonNode snapshot(UUID... repoIds) throws Exception {
        String repos = Arrays.stream(repoIds)
                .map(id -> "{\"id\":\"" + id + "\",\"url\":\"https://github.com/acme/x\",\"name\":\"x\"}")
                .collect(Collectors.joining(",", "[", "]"));
        return objectMapper.readTree("""
                {"nodes":[
                  {"template_node_id":"%s","label":"test","executor_type":"script"},
                  {"template_node_id":"%s","label":"final_approval","executor_type":"human"}],
                 "edges":[],
                 "repos":%s}""".formatted(testNodeId, gateNodeId, repos));
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
