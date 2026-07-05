package com.choruskube.core.repository;

import static org.assertj.core.api.Assertions.*;

import com.choruskube.core.BaseTest;
import com.choruskube.core.model.GitRepo;
import com.choruskube.core.util.RepoNameUtil;
import io.temporal.client.WorkflowClient;
import io.temporal.serviceclient.WorkflowServiceStubs;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

/**
 * Repository-layer tests for the soft-delete pilot on {@code git_repo}. These focus on
 * native queries that must bypass the entity-level {@code @SQLRestriction} and on the
 * idempotency of the reconciler's primitives.
 *
 * <p>Each test creates a fresh organization so count-based assertions are not polluted
 * by rows seeded into the system org by earlier migrations (see V21/V27).
 */
@Transactional
class GitRepoRepositoryTest extends BaseTest {

    @MockitoBean
    private WorkflowServiceStubs workflowServiceStubs;

    @MockitoBean
    private WorkflowClient workflowClient;

    @Autowired
    private GitRepoRepository repo;

    @PersistenceContext
    private EntityManager entityManager;

    private GitRepo buildRepo(String url) {
        GitRepo g = new GitRepo();
        g.setUrl(url);
        g.setName(RepoNameUtil.deriveOwnerRepoName(url));
        return g;
    }

    @Test
    void findTombstonedBatch_returnsOnlyTombstoned() {
        GitRepo live = repo.saveAndFlush(buildRepo("https://github.com/tomb/live-" + UUID.randomUUID()));
        GitRepo dead = repo.saveAndFlush(buildRepo("https://github.com/tomb/dead-" + UUID.randomUUID()));

        dead.setDeletedAt(Instant.now());
        repo.saveAndFlush(dead);

        List<TombstonedGitRepoRef> batch = repo.findTombstonedBatch(100);

        assertThat(batch)
                .extracting(TombstonedGitRepoRef::getId)
                .contains(dead.getId())
                .doesNotContain(live.getId());
    }

    @Test
    void hardDeleteTombstoneById_doesNothingForLiveRow() {
        GitRepo live = repo.saveAndFlush(buildRepo("https://github.com/hd/live-" + UUID.randomUUID()));

        int rows = repo.hardDeleteTombstoneById(live.getId());
        entityManager.flush();
        entityManager.clear();

        assertThat(rows).isZero();
        // Row still exists. After V44, the parent software_project row drives existence;
        // git_repo is the subtype-only side of the JOINED inheritance.
        Long count = ((Number) entityManager
                        .createNativeQuery("SELECT COUNT(*) FROM software_project WHERE id = :id")
                        .setParameter("id", live.getId())
                        .getSingleResult())
                .longValue();
        assertThat(count).isEqualTo(1L);
    }
}
