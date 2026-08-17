package com.choruskube.core.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.choruskube.core.BaseTest;
import com.choruskube.core.model.GitRepo;
import com.choruskube.core.model.GraphTemplate;
import com.choruskube.core.model.RunPullRequest;
import com.choruskube.core.model.WorkflowRun;
import com.choruskube.core.model.enums.PullRequestState;
import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@code run_pull_request} has FKs to {@code workflow_run} and {@code git_repo}
 * (V1__core_schema.sql), so every row here hangs off a real {@link WorkflowRun}/{@link GitRepo}
 * rather than a bare random {@link UUID} — following the fixture-building idiom in {@code
 * InternalRunControllerPullRequestsAuthTest#setUp}.
 */
@Transactional
public class RunPullRequestStateRepositoryTest extends BaseTest {

    @Autowired
    private RunPullRequestRepository repo;

    @Autowired
    private WorkflowRunRepository runRepo;

    @Autowired
    private GitRepoRepository gitRepoRepo;

    @Autowired
    private GraphTemplateRepository graphTemplateRepo;

    private UUID graphTemplateId;
    private UUID gitRepoId;

    @BeforeEach
    void setUp() {
        GraphTemplate template = new GraphTemplate();
        template.setName("PR State Test Template");
        template.setGraphId("pr-state-test-template");
        template.setVersion(1);
        graphTemplateId = graphTemplateRepo.save(template).getId();

        GitRepo gitRepo = new GitRepo();
        gitRepo.setName("o/r");
        gitRepo.setUrl("https://github.com/o/r");
        gitRepoId = gitRepoRepo.save(gitRepo).getId();
    }

    @Test
    void findUnmergedBatch_excludesMergedRowsAndOrdersByWhenEachIsNextDue() {
        UUID runId = newWorkflowRunId();
        Instant now = Instant.parse("2026-08-17T12:00:00Z");
        RunPullRequest merged = save(runId, "https://github.com/o/r/pull/1", 1, Instant.now(), now.minusSeconds(600));
        RunPullRequest laterDue = save(runId, "https://github.com/o/r/pull/2", 2, null, now.minusSeconds(60));
        RunPullRequest earlierDue = save(runId, "https://github.com/o/r/pull/3", 3, null, now.minusSeconds(600));

        List<RunPullRequest> batch = repo.findUnmergedBatch(now, PageRequest.of(0, 10));
        List<UUID> ids = batch.stream().map(RunPullRequest::getId).toList();

        assertThat(ids).doesNotContain(merged.getId());
        assertThat(ids).containsSubsequence(earlierDue.getId(), laterDue.getId());
    }

    /**
     * A row with no PR number cannot be queried from GitHub by any amount of retrying, so it is not
     * a candidate at all. It used to be selected, reach {@code refreshOne}, return false without an
     * exception and therefore without a stamp — holding a batch slot forever while logging only at
     * debug. It is surfaced on the Autopilot panel instead; see {@code AutopilotService#whyIdle}.
     */
    @Test
    void findUnmergedBatch_excludesRowsWithNoPrNumber() {
        UUID runId = newWorkflowRunId();
        Instant now = Instant.parse("2026-08-17T12:00:00Z");
        RunPullRequest unresolvable = save(runId, "https://github.com/o/r/pull/1", null, null, now.minusSeconds(600));
        RunPullRequest queryable = save(runId, "https://github.com/o/r/pull/2", 2, null, now.minusSeconds(600));

        List<UUID> ids = repo.findUnmergedBatch(now, PageRequest.of(0, 10)).stream()
                .map(RunPullRequest::getId)
                .toList();

        assertThat(ids).contains(queryable.getId()).doesNotContain(unresolvable.getId());
    }

    @Test
    void findUnmergedBatch_excludesRowsThatAreNotYetDue() {
        UUID runId = newWorkflowRunId();
        Instant now = Instant.parse("2026-08-17T12:00:00Z");
        RunPullRequest backedOff = save(runId, "https://github.com/o/r/pull/1", 1, null, now.plusSeconds(60));
        RunPullRequest due = save(runId, "https://github.com/o/r/pull/2", 2, null, now);

        List<UUID> ids = repo.findUnmergedBatch(now, PageRequest.of(0, 10)).stream()
                .map(RunPullRequest::getId)
                .toList();

        assertThat(ids).contains(due.getId()).doesNotContain(backedOff.getId());
    }

    /**
     * Equal due times are the common case, not an edge case: every row backfilled by V17 with no
     * {@code state_checked_at} shares one, and so does every row that failed in the same tick.
     * Without the {@code id} tiebreak the order between them is undefined and may differ from tick
     * to tick, so a row can be skipped indefinitely by nothing more than plan choice.
     *
     * <p><strong>What this does and does not catch.</strong> It pins the expected order, so it
     * fails if the ordering is dropped, reversed, or keyed on the wrong column. It does <em>not</em>
     * fail if only {@code p.id ASC} is removed while {@code idx_run_pull_request_due} still exists
     * — verified by mutation. The index is on {@code (next_check_at, id)}, so an index scan hands
     * back id order whether or not the query asked for it, and "undefined" is free to coincide with
     * the defined answer. The clause still has to be there: the guarantee is what stops a planner
     * from choosing a sequential scan plus a sort on a large table and returning a different order
     * on the next tick. That property is not reachable from a test against a real Postgres, so it
     * rests on the query's javadoc and on review, not on this.
     */
    @Test
    void findUnmergedBatch_breaksTiesDeterministicallyById() {
        UUID runId = newWorkflowRunId();
        Instant now = Instant.parse("2026-08-17T12:00:00Z");
        Instant sameDueTime = now.minusSeconds(600);
        List<UUID> allIds = new ArrayList<>();
        for (int i = 1; i <= 12; i++) {
            allIds.add(save(runId, "https://github.com/o/r/pull/" + i, i, null, sameDueTime)
                    .getId());
        }

        List<UUID> page = repo.findUnmergedBatch(now, PageRequest.of(0, 4)).stream()
                .map(RunPullRequest::getId)
                .toList();

        List<UUID> expected =
                allIds.stream().sorted(POSTGRES_UUID_ORDER).limit(4).toList();
        assertThat(page)
                .as("with every due time equal, the id is the whole order — insertion order must not decide it")
                .isEqualTo(expected);
    }

    /**
     * Postgres compares {@code uuid} as sixteen <em>unsigned</em> bytes.
     * {@link UUID#compareTo(UUID)} compares the most-significant bits as a <em>signed</em> long, so
     * the two disagree for every UUID with the high bit set — which is most of them, and version-4
     * UUIDs about half the time. Asserting {@code isSorted()} on the Java ordering therefore fails
     * against a correctly ordered result, which is a flaky test rather than a strict one.
     */
    private static final Comparator<UUID> POSTGRES_UUID_ORDER =
            (a, b) -> Arrays.compareUnsigned(unsignedBytes(a), unsignedBytes(b));

    private static byte[] unsignedBytes(UUID id) {
        return ByteBuffer.allocate(16)
                .putLong(id.getMostSignificantBits())
                .putLong(id.getLeastSignificantBits())
                .array();
    }

    @Test
    void failureCountAndNextCheckAtRoundTrip() {
        RunPullRequest pr = save(newWorkflowRunId(), "https://github.com/o/r/pull/9", 9, null, Instant.now());
        Instant nextCheckAt = Instant.parse("2026-08-17T12:30:00Z");
        pr.setFailureCount(4);
        pr.setNextCheckAt(nextCheckAt);
        repo.saveAndFlush(pr);

        RunPullRequest reloaded = repo.findById(pr.getId()).orElseThrow();

        assertThat(reloaded.getFailureCount()).isEqualTo(4);
        assertThat(reloaded.getNextCheckAt()).isEqualTo(nextCheckAt);
    }

    /** The column is NOT NULL, so an unset value has to become "due now" rather than a violation. */
    @Test
    void nextCheckAt_defaultsToDueImmediately_whenNotSet() {
        RunPullRequest pr = new RunPullRequest();
        pr.setWorkflowRunId(newWorkflowRunId());
        pr.setGitRepoId(gitRepoId);
        pr.setPrUrl("https://github.com/o/r/pull/10");
        pr.setPrNumber(10);

        RunPullRequest saved = repo.saveAndFlush(pr);

        assertThat(saved.getNextCheckAt()).isNotNull().isEqualTo(saved.getCreatedAt());
        assertThat(saved.getFailureCount()).isZero();
    }

    @Test
    void stateAndMergedAtRoundTrip() {
        RunPullRequest pr = save(newWorkflowRunId(), "https://github.com/o/r/pull/4", 4, null, Instant.now());
        pr.setState(PullRequestState.closed);
        Instant mergedAt = Instant.parse("2026-08-16T10:00:00Z");
        pr.setMergedAt(mergedAt);
        repo.saveAndFlush(pr);

        RunPullRequest reloaded = repo.findById(pr.getId()).orElseThrow();

        assertThat(reloaded.getState()).isEqualTo(PullRequestState.closed);
        assertThat(reloaded.getMergedAt()).isEqualTo(mergedAt);
    }

    private UUID newWorkflowRunId() {
        WorkflowRun run = new WorkflowRun();
        run.setGraphTemplateId(graphTemplateId);
        return runRepo.save(run).getId();
    }

    private RunPullRequest save(UUID runId, String prUrl, Integer prNumber, Instant mergedAt, Instant nextCheckAt) {
        RunPullRequest pr = new RunPullRequest();
        pr.setWorkflowRunId(runId);
        pr.setGitRepoId(gitRepoId);
        pr.setPrUrl(prUrl);
        pr.setPrNumber(prNumber);
        pr.setMergedAt(mergedAt);
        pr.setNextCheckAt(nextCheckAt);
        return repo.saveAndFlush(pr);
    }
}
