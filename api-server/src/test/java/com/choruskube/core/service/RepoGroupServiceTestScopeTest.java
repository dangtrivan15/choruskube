package com.choruskube.core.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.choruskube.core.SharedContextTest;
import com.choruskube.core.dto.RepoGroupRequest;
import com.choruskube.core.exception.BadRequestException;
import com.choruskube.core.model.GitRepo;
import com.choruskube.core.repository.GitRepoRepository;
import com.choruskube.core.repository.RepoGroupDependencyRepository;
import com.choruskube.core.repository.RepoGroupRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

@Transactional
class RepoGroupServiceTestScopeTest extends SharedContextTest {

    @Autowired
    private RepoGroupService service;

    @Autowired
    private RepoGroupRepository repoGroups;

    @Autowired
    private GitRepoRepository gitRepos;

    @Autowired
    private RepoGroupDependencyRepository dependencies;

    @PersistenceContext
    private EntityManager entityManager;

    private UUID x;
    private UUID y;
    private UUID z;
    private UUID groupId;

    @BeforeEach
    void setUp() {
        x = saveRepo("dep-x");
        y = saveRepo("dep-y");
        z = saveRepo("dep-z");
        groupId = service.create(new RepoGroupRequest(
                        "dep-group-" + UUID.randomUUID(), null, null, List.of(x, y, z), null, null, null, null))
                .getId();
    }

    @Test
    void replaceTestScope_storesSwitchAndEdges() {
        service.replaceTestScope(groupId, true, Map.of(x, Set.of(y), y, Set.of(z)));
        flushAndClear();

        assertThat(repoGroups.findById(groupId).orElseThrow().isTestAffectedOnly())
                .isTrue();
        assertThat(service.dependenciesOf(groupId)).isEqualTo(Map.of(x, List.of(y), y, List.of(z)));
    }

    @Test
    void dependenciesOf_ordersEachListByMemberPosition() {
        service.replaceTestScope(groupId, true, Map.of(x, Set.of(z, y)));
        flushAndClear();

        assertThat(service.dependenciesOf(groupId).get(x)).containsExactly(y, z);
    }

    @Test
    void replaceTestScope_replacesAll() {
        service.replaceTestScope(groupId, true, Map.of(x, Set.of(y), y, Set.of(z)));
        service.replaceTestScope(groupId, false, Map.of(z, Set.of(x)));
        flushAndClear();

        assertThat(repoGroups.findById(groupId).orElseThrow().isTestAffectedOnly())
                .isFalse();
        assertThat(service.dependenciesOf(groupId)).isEqualTo(Map.of(z, List.of(x)));
    }

    @Test
    void replaceTestScope_nonMember_400() {
        UUID outsider = saveRepo("dep-outsider");

        assertThatThrownBy(() -> service.replaceTestScope(groupId, true, Map.of(x, Set.of(outsider))))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining(outsider.toString());
    }

    @Test
    void replaceTestScope_selfEdge_400() {
        assertThatThrownBy(() -> service.replaceTestScope(groupId, true, Map.of(x, Set.of(x))))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void replaceTestScope_cycle_400_namesCycle() {
        assertThatThrownBy(() -> service.replaceTestScope(groupId, true, Map.of(x, Set.of(y), y, Set.of(x))))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("→")
                .hasMessageContaining(x.toString())
                .hasMessageContaining(y.toString());
    }

    @Test
    void replaceMembers_reorder_keepsEdges() {
        service.replaceTestScope(groupId, true, Map.of(x, Set.of(y), y, Set.of(z)));
        flushAndClear();

        service.replaceMembers(groupId, List.of(z, y, x));
        flushAndClear();

        assertThat(service.dependenciesOf(groupId)).isEqualTo(Map.of(x, List.of(y), y, List.of(z)));
    }

    @Test
    void replaceMembers_removal_prunesEdges() {
        UUID w = saveRepo("dep-w");
        service.replaceMembers(groupId, List.of(x, y, z, w));
        service.replaceTestScope(groupId, true, Map.of(x, Set.of(y, w), y, Set.of(z), z, Set.of(w)));
        flushAndClear();

        service.replaceMembers(groupId, List.of(x, z, w));
        flushAndClear();

        assertThat(service.dependenciesOf(groupId)).isEqualTo(Map.of(x, List.of(w), z, List.of(w)));
    }

    @Test
    void delete_clearsEdges() {
        service.replaceTestScope(groupId, true, Map.of(x, Set.of(y)));
        flushAndClear();

        service.delete(groupId);
        flushAndClear();

        assertThat(dependencies.findByRepoGroupId(groupId)).isEmpty();
    }

    private void flushAndClear() {
        entityManager.flush();
        entityManager.clear();
    }

    private UUID saveRepo(String name) {
        GitRepo r = new GitRepo();
        r.setName(name + "-" + UUID.randomUUID());
        r.setUrl("https://github.com/owner/" + name + ".git");
        r.setDefaultBranch("main");
        return gitRepos.save(r).getId();
    }
}
