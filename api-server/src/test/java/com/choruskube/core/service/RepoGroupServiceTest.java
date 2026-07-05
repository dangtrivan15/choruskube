package com.choruskube.core.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.choruskube.core.BaseTest;
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

        RepoGroup created = service.create("proj-a", "registry/agent:v1", "desc", List.of(r1.getId(), r2.getId()));

        RepoGroup loaded = repoGroups.findById(created.getId()).orElseThrow();
        assertThat(loaded.getName()).isEqualTo("proj-a");
        assertThat(loaded.resolveRepos()).extracting(GitRepo::getName).containsExactly("r1", "r2");
    }

    @Test
    void create_allows_any_member_under_always_allow_strategy() {
        GitRepo member = saveRepo("member");

        RepoGroup created = service.create("proj-x", null, null, List.of(member.getId()));

        assertThat(created.resolveRepos()).extracting(GitRepo::getName).containsExactly("member");
    }

    @Test
    void create_rejects_duplicate_name_in_org_against_existing_repo_or_group() {
        saveRepo("name-clash");
        GitRepo other = saveRepo("other");

        assertThatThrownBy(() -> service.create("name-clash", null, null, List.of(other.getId())))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("name");
    }

    @Test
    void replace_members_updates_set_and_position_atomically() {
        GitRepo r1 = saveRepo("r1");
        GitRepo r2 = saveRepo("r2");
        GitRepo r3 = saveRepo("r3");

        RepoGroup group = service.create("proj-b", null, null, List.of(r1.getId(), r2.getId()));

        entityManager.flush();
        entityManager.clear();

        service.replaceMembers(group.getId(), List.of(r3.getId(), r1.getId()));

        entityManager.flush();
        entityManager.clear();

        RepoGroup loaded = repoGroups.findById(group.getId()).orElseThrow();
        assertThat(loaded.resolveRepos()).extracting(GitRepo::getName).containsExactly("r3", "r1");
    }

    @Test
    void delete_removes_group_when_no_external_references() {
        GitRepo r1 = saveRepo("r1");
        RepoGroup group = service.create("proj-c", null, null, List.of(r1.getId()));

        service.delete(group.getId());

        assertThat(repoGroups.findById(group.getId())).isEmpty();
    }

    private GitRepo saveRepo(String name) {
        GitRepo r = new GitRepo();
        r.setName(name);
        r.setUrl("https://github.com/owner/" + name + ".git");
        r.setDefaultBranch("main");
        return gitRepos.save(r);
    }
}
