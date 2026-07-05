package com.choruskube.core.service;

import com.choruskube.core.exception.BadRequestException;
import com.choruskube.core.exception.ConflictException;
import com.choruskube.core.repository.GitRepoRepository;
import com.choruskube.core.repository.SoftwareProjectRepository;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * {@link RepoUniquenessChecker} for the OSS / single-tenant deployment. Active when {@code
 * auth.enabled} is absent or {@code false} (the default).
 *
 * <p>Uniqueness is GLOBAL — the correct single-tenant semantic, since there is exactly one tenant:
 * a repo URL or a software-project name may exist at most once across the whole deployment. The
 * exception types and messages are identical to the prior org-scoped checks in
 * {@code GitRepoService} / {@code RepoGroupService}, so the API contract is unchanged.
 */
@Component
@ConditionalOnProperty(name = "auth.enabled", havingValue = "false", matchIfMissing = true)
public class GlobalRepoUniquenessChecker implements RepoUniquenessChecker {

    private final GitRepoRepository gitRepos;
    private final SoftwareProjectRepository softwareProjects;

    public GlobalRepoUniquenessChecker(GitRepoRepository gitRepos, SoftwareProjectRepository softwareProjects) {
        this.gitRepos = gitRepos;
        this.softwareProjects = softwareProjects;
    }

    @Override
    public void assertUrlAvailable(String url, UUID excludeRepoId) {
        gitRepos.findByUrl(url).ifPresent(existing -> {
            if (excludeRepoId != null && excludeRepoId.equals(existing.getId())) {
                return; // the repo being updated — not a conflict with itself
            }
            throw new ConflictException("Git repo already exists with URL: " + url);
        });
    }

    @Override
    public void assertNameAvailable(String name) {
        softwareProjects.findByName(name).ifPresent(existing -> {
            throw new BadRequestException("A SoftwareProject named '" + name + "' already exists");
        });
    }
}
