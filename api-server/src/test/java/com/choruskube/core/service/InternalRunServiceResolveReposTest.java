package com.choruskube.core.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.choruskube.core.BaseTest;
import com.choruskube.core.dto.RepoGroupRequest;
import com.choruskube.core.model.GitRepo;
import com.choruskube.core.repository.GitRepoRepository;
import com.choruskube.core.repository.RepoGroupRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Deliberately not {@code @Transactional}: roadmap materialization reads the returned repos' URLs
 * after {@code resolveRepos}' own transaction has closed, and only a test with no ambient
 * transaction sees a RepoGroup member's lazy {@code GitRepo} the way that caller does.
 */
class InternalRunServiceResolveReposTest extends BaseTest {

    @Autowired
    private InternalRunService internalRunService;

    @Autowired
    private RepoGroupService repoGroupService;

    @Autowired
    private RepoGroupRepository repoGroupRepo;

    @Autowired
    private GitRepoRepository gitRepoRepo;

    private final List<UUID> repoIds = new ArrayList<>();
    private UUID groupId;

    @AfterEach
    void cleanUp() {
        if (groupId != null) {
            repoGroupRepo.deleteById(groupId);
        }
        gitRepoRepo.deleteAllById(repoIds);
    }

    @Test
    void repoGroupRepos_areReadableAfterItsTransactionCloses() {
        GitRepo a = saveRepo("https://github.com/acme/resolve-repos-a-" + UUID.randomUUID());
        GitRepo b = saveRepo("https://github.com/acme/resolve-repos-b-" + UUID.randomUUID());
        groupId = repoGroupService
                .createInternal(new RepoGroupRequest(
                        "resolve-repos-" + UUID.randomUUID(), null, null, List.of(a.getId(), b.getId()), null, null))
                .getId();

        List<GitRepo> repos = internalRunService.resolveRepos(groupId);

        assertThat(repos).extracting(GitRepo::getUrl).containsExactlyInAnyOrder(a.getUrl(), b.getUrl());
    }

    private GitRepo saveRepo(String url) {
        GitRepo repo = new GitRepo();
        repo.setUrl(url);
        repo.setName(url);
        repo.setSecrets("[]");
        GitRepo saved = gitRepoRepo.save(repo);
        repoIds.add(saved.getId());
        return saved;
    }
}
