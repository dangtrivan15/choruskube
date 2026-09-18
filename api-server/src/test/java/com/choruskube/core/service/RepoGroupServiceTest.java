package com.choruskube.core.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.choruskube.core.BaseTest;
import com.choruskube.core.dto.RepoGroupRequest;
import com.choruskube.core.exception.BadRequestException;
import com.choruskube.core.model.GitRepo;
import com.choruskube.core.model.RepoGroup;
import com.choruskube.core.repository.GitRepoRepository;
import com.choruskube.core.repository.RepoGroupRepository;
import io.temporal.client.WorkflowClient;
import io.temporal.serviceclient.WorkflowServiceStubs;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

@Transactional
class RepoGroupServiceTest extends BaseTest {

    @MockitoBean
    private WorkflowServiceStubs workflowServiceStubs;

    @MockitoBean
    private WorkflowClient workflowClient;

    @Autowired
    private RepoGroupService service;

    @Autowired
    private RepoGroupRepository repoGroups;

    @Autowired
    private GitRepoRepository gitRepos;

    @PersistenceContext
    private EntityManager entityManager;

    @Test
    void create_group_persists_and_returns_with_members_in_order() {
        GitRepo r1 = saveRepo("r1");
        GitRepo r2 = saveRepo("r2");

        RepoGroup created = service.create(new RepoGroupRequest(
                "proj-a", "registry/agent:v1", "desc", List.of(r1.getId(), r2.getId()), null, null));

        RepoGroup loaded = repoGroups.findById(created.getId()).orElseThrow();
        assertThat(loaded.getName()).isEqualTo("proj-a");
        assertThat(loaded.resolveRepos()).extracting(GitRepo::getName).containsExactly("r1", "r2");
    }

    @Test
    void create_allows_any_member_under_always_allow_strategy() {
        GitRepo member = saveRepo("member");

        RepoGroup created =
                service.create(new RepoGroupRequest("proj-x", null, null, List.of(member.getId()), null, null));

        assertThat(created.resolveRepos()).extracting(GitRepo::getName).containsExactly("member");
    }

    @Test
    void create_rejects_duplicate_name_in_org_against_existing_repo_or_group() {
        saveRepo("name-clash");
        GitRepo other = saveRepo("other");

        assertThatThrownBy(() -> service.create(
                        new RepoGroupRequest("name-clash", null, null, List.of(other.getId()), null, null)))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("name");
    }

    @Test
    void replace_members_updates_set_and_position_atomically() {
        GitRepo r1 = saveRepo("r1");
        GitRepo r2 = saveRepo("r2");
        GitRepo r3 = saveRepo("r3");

        RepoGroup group =
                service.create(new RepoGroupRequest("proj-b", null, null, List.of(r1.getId(), r2.getId()), null, null));

        entityManager.flush();
        entityManager.clear();

        service.replaceMembers(group.getId(), List.of(r3.getId(), r1.getId()));

        entityManager.flush();
        entityManager.clear();

        RepoGroup loaded = repoGroups.findById(group.getId()).orElseThrow();
        assertThat(loaded.resolveRepos()).extracting(GitRepo::getName).containsExactly("r3", "r1");
    }

    @Test
    void delete_archives_group_and_keeps_the_row() {
        GitRepo r1 = saveRepo("r1");
        RepoGroup group = service.create(new RepoGroupRequest("proj-c", null, null, List.of(r1.getId()), null, null));

        service.delete(group.getId());

        // Archive, not hard-delete: findById no longer returns it (@SQLRestriction hides deleted_at
        // rows), but the row physically survives so Epics/Tasks/runs keep a valid FK target. Clear the
        // session first, or findById returns the still-cached entity and skips the SQL filter.
        entityManager.flush();
        entityManager.clear();
        assertThat(repoGroups.findById(group.getId())).isEmpty();
        List<?> rows = entityManager
                .createNativeQuery("SELECT deleted_at FROM software_project WHERE id = :id")
                .setParameter("id", group.getId())
                .getResultList();
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0)).isNotNull();
    }

    private GitRepo saveRepo(String name) {
        GitRepo r = new GitRepo();
        r.setName(name);
        r.setUrl("https://github.com/owner/" + name + ".git");
        r.setDefaultBranch("main");
        return gitRepos.save(r);
    }
}
