package com.choruskube.core.credential;

import static org.assertj.core.api.Assertions.*;

import com.choruskube.core.BaseTest;
import com.choruskube.core.config.GraphIds;
import com.choruskube.core.dto.RepoGroupRequest;
import com.choruskube.core.exception.NotFoundException;
import com.choruskube.core.model.GitRepo;
import com.choruskube.core.model.WorkflowRun;
import com.choruskube.core.repository.GitRepoRepository;
import com.choruskube.core.repository.GraphTemplateRepository;
import com.choruskube.core.repository.RepoGroupRepository;
import com.choruskube.core.repository.WorkflowRunRepository;
import com.choruskube.core.service.RepoGroupService;
import com.choruskube.core.util.RepoNameUtil;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class GitHubTokenScopeResolverTest extends BaseTest {

    @Autowired
    private GitHubTokenScopeResolver resolver;

    @Autowired
    private RepoGroupService repoGroupService;

    @Autowired
    private RepoGroupRepository repoGroupRepo;

    @Autowired
    private GitRepoRepository gitRepoRepo;

    @Autowired
    private WorkflowRunRepository runRepo;

    @Autowired
    private GraphTemplateRepository templateRepo;

    private UUID groupId;

    @AfterEach
    void cleanUp() {
        if (groupId != null) {
            repoGroupRepo.deleteById(groupId);
        }
    }

    private GitRepo saveRepo(String url) {
        GitRepo repo = new GitRepo();
        repo.setUrl(url);
        repo.setName(url);
        repo.setSecrets("[]");
        return gitRepoRepo.save(repo);
    }

    private UUID templateId() {
        return templateRepo
                .findFirstByGraphIdOrderByVersionDesc(GraphIds.FEATURE_DEVELOPMENT)
                .orElseThrow()
                .getId();
    }

    @Test
    void forRun_aProjectOfTwoGitHubReposAndOneNonGitHub_returnsOnlyTheTwo() {
        GitRepo a = saveRepo("https://github.com/scope-resolver-test/repo-a-" + UUID.randomUUID());
        GitRepo b = saveRepo("https://github.com/scope-resolver-test/repo-b-" + UUID.randomUUID());
        GitRepo c = saveRepo("https://example.invalid/scope-resolver-test/repo-c-" + UUID.randomUUID());
        groupId = repoGroupService
                .createInternal(new RepoGroupRequest(
                        "scope-resolver-" + UUID.randomUUID(),
                        null,
                        null,
                        List.of(a.getId(), b.getId(), c.getId()),
                        null,
                        null,
                        null,
                        null))
                .getId();

        WorkflowRun run = new WorkflowRun();
        run.setGraphTemplateId(templateId());
        run.setInputs("{\"feature_request\":\"test\",\"software_project_id\":\"" + groupId + "\"}");
        run = runRepo.save(run);

        GitHubTokenScope scope = resolver.forRun(run.getId());

        assertThat(scope.repositories())
                .containsExactlyInAnyOrder(
                        githubOwnerRepoOf(a.getUrl()).orElseThrow(),
                        githubOwnerRepoOf(b.getUrl()).orElseThrow());
        assertThat(scope.permissions()).isEqualTo(GitHubTokenScope.RUN_PERMISSIONS);
    }

    @Test
    void forRun_singleGitRepoIdRun_returnsThatRepo() {
        GitRepo repo = saveRepo("https://github.com/scope-resolver-test/single-" + UUID.randomUUID());

        WorkflowRun run = new WorkflowRun();
        run.setGraphTemplateId(templateId());
        run.setInputs("{\"feature_request\":\"test\",\"git_repo_id\":\"" + repo.getId() + "\"}");
        run = runRepo.save(run);

        GitHubTokenScope scope = resolver.forRun(run.getId());

        assertThat(scope.repositories())
                .containsExactly(githubOwnerRepoOf(repo.getUrl()).orElseThrow());
    }

    @Test
    void forRun_everyRepoNonGitHub_returnsEmptyScope() {
        GitRepo a = saveRepo("https://example.invalid/scope-resolver-test/repo-a-" + UUID.randomUUID());
        GitRepo b = saveRepo("https://example.invalid/scope-resolver-test/repo-b-" + UUID.randomUUID());
        groupId = repoGroupService
                .createInternal(new RepoGroupRequest(
                        "scope-resolver-" + UUID.randomUUID(),
                        null,
                        null,
                        List.of(a.getId(), b.getId()),
                        null,
                        null,
                        null,
                        null))
                .getId();

        WorkflowRun run = new WorkflowRun();
        run.setGraphTemplateId(templateId());
        run.setInputs("{\"feature_request\":\"test\",\"software_project_id\":\"" + groupId + "\"}");
        run = runRepo.save(run);

        GitHubTokenScope scope = resolver.forRun(run.getId());

        assertThat(scope.repositories()).isEmpty();
    }

    @Test
    void forRun_unknownRun_throwsNotFound() {
        assertThatThrownBy(() -> resolver.forRun(UUID.randomUUID())).isInstanceOf(NotFoundException.class);
    }

    @Test
    void forRepo_githubRepo_returnsItsScope() {
        GitRepo repo = saveRepo("https://github.com/scope-resolver-test/for-repo-" + UUID.randomUUID());

        GitHubTokenScope scope = resolver.forRepo(repo.getId());

        assertThat(scope.repositories())
                .containsExactly(githubOwnerRepoOf(repo.getUrl()).orElseThrow());
    }

    @Test
    void forRepo_nonGitHubRepo_returnsEmptyScope() {
        GitRepo repo = saveRepo("https://example.invalid/scope-resolver-test/for-repo-" + UUID.randomUUID());

        GitHubTokenScope scope = resolver.forRepo(repo.getId());

        assertThat(scope.repositories()).isEmpty();
    }

    @Test
    void forRepo_unknownRepo_throwsNotFound() {
        assertThatThrownBy(() -> resolver.forRepo(UUID.randomUUID())).isInstanceOf(NotFoundException.class);
    }

    private static Optional<String> githubOwnerRepoOf(String url) {
        return RepoNameUtil.githubOwnerRepo(url);
    }
}
