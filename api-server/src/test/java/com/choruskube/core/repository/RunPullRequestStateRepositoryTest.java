package com.choruskube.core.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.choruskube.core.BaseTest;
import com.choruskube.core.model.GitRepo;
import com.choruskube.core.model.GraphTemplate;
import com.choruskube.core.model.RunPullRequest;
import com.choruskube.core.model.WorkflowRun;
import com.choruskube.core.model.enums.PullRequestState;
import java.time.Instant;
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
    void findUnmergedBatch_excludesMergedRowsAndOrdersNeverCheckedFirst() {
        UUID runId = newWorkflowRunId();
        RunPullRequest merged = save(runId, "https://github.com/o/r/pull/1", Instant.now(), Instant.now());
        RunPullRequest checked = save(runId, "https://github.com/o/r/pull/2", null, Instant.now());
        RunPullRequest neverChecked = save(runId, "https://github.com/o/r/pull/3", null, null);

        List<RunPullRequest> batch = repo.findUnmergedBatch(PageRequest.of(0, 10));
        List<UUID> ids = batch.stream().map(RunPullRequest::getId).toList();

        assertThat(ids).doesNotContain(merged.getId());
        assertThat(ids).containsSubsequence(neverChecked.getId(), checked.getId());
    }

    @Test
    void stateAndMergedAtRoundTrip() {
        RunPullRequest pr = save(newWorkflowRunId(), "https://github.com/o/r/pull/4", null, null);
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

    private RunPullRequest save(UUID runId, String prUrl, Instant mergedAt, Instant checkedAt) {
        RunPullRequest pr = new RunPullRequest();
        pr.setWorkflowRunId(runId);
        pr.setGitRepoId(gitRepoId);
        pr.setPrUrl(prUrl);
        pr.setMergedAt(mergedAt);
        pr.setStateCheckedAt(checkedAt);
        return repo.saveAndFlush(pr);
    }
}
