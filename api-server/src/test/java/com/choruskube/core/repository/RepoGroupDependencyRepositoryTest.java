package com.choruskube.core.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.choruskube.core.SharedContextTest;
import com.choruskube.core.dto.RepoGroupRequest;
import com.choruskube.core.model.GitRepo;
import com.choruskube.core.model.RepoGroup;
import com.choruskube.core.model.RepoGroupDependency;
import com.choruskube.core.service.RepoGroupService;
import com.choruskube.core.util.RepoNameUtil;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

@Transactional
class RepoGroupDependencyRepositoryTest extends SharedContextTest {

    @Autowired
    private RepoGroupDependencyRepository repo;

    @Autowired
    private GitRepoRepository gitRepos;

    @Autowired
    private RepoGroupService groups;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @PersistenceContext
    private EntityManager entityManager;

    private GitRepo newRepo() {
        GitRepo g = new GitRepo();
        g.setUrl("https://github.com/dep/r-" + UUID.randomUUID());
        g.setName(RepoNameUtil.deriveOwnerRepoName(g.getUrl()));
        return gitRepos.saveAndFlush(g);
    }

    private RepoGroup newGroup(GitRepo... members) {
        List<UUID> ids = java.util.Arrays.stream(members).map(GitRepo::getId).toList();
        return groups.create(new RepoGroupRequest("grp-" + UUID.randomUUID(), null, null, ids, null, null, null, null));
    }

    @Test
    void saveAndFindByGroup() {
        GitRepo a = newRepo();
        GitRepo b = newRepo();
        GitRepo c = newRepo();
        RepoGroup group = newGroup(a, b, c);
        repo.saveAndFlush(new RepoGroupDependency(group.getId(), a.getId(), b.getId()));
        repo.saveAndFlush(new RepoGroupDependency(group.getId(), a.getId(), c.getId()));
        entityManager.clear();

        assertThat(repo.findByRepoGroupId(group.getId()))
                .extracting(RepoGroupDependency::getDependsOnGitRepoId)
                .containsExactlyInAnyOrder(b.getId(), c.getId());
    }

    @Test
    void selfEdgeRejected() {
        GitRepo a = newRepo();
        RepoGroup group = newGroup(a);

        assertThatThrownBy(() -> repo.saveAndFlush(new RepoGroupDependency(group.getId(), a.getId(), a.getId())))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void groupHardDeleteCascades() {
        GitRepo a = newRepo();
        GitRepo b = newRepo();
        RepoGroup group = newGroup(a, b);
        repo.saveAndFlush(new RepoGroupDependency(group.getId(), a.getId(), b.getId()));
        entityManager.clear();

        jdbcTemplate.update("DELETE FROM software_project WHERE id = ?", group.getId());
        entityManager.clear();

        assertThat(repo.findByRepoGroupId(group.getId())).isEmpty();
    }

    @Test
    void testAffectedOnlyDefaultsFalse() {
        RepoGroup group = newGroup(newRepo());
        entityManager.flush();
        entityManager.clear();

        RepoGroup fetched = entityManager.find(RepoGroup.class, group.getId());
        assertThat(fetched.isTestAffectedOnly()).isFalse();
    }
}
