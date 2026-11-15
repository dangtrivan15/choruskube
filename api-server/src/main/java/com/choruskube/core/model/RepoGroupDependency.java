package com.choruskube.core.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.util.UUID;

/** Edge saying {@code gitRepoId} depends on {@code dependsOnGitRepoId} within one repo group. */
@Entity
@Table(name = "repo_group_dependency")
@IdClass(RepoGroupDependencyId.class)
public class RepoGroupDependency {

    @Id
    @Column(name = "repo_group_id", nullable = false)
    private UUID repoGroupId;

    @Id
    @Column(name = "git_repo_id", nullable = false)
    private UUID gitRepoId;

    @Id
    @Column(name = "depends_on_git_repo_id", nullable = false)
    private UUID dependsOnGitRepoId;

    protected RepoGroupDependency() {}

    public RepoGroupDependency(UUID repoGroupId, UUID gitRepoId, UUID dependsOnGitRepoId) {
        this.repoGroupId = repoGroupId;
        this.gitRepoId = gitRepoId;
        this.dependsOnGitRepoId = dependsOnGitRepoId;
    }

    public UUID getRepoGroupId() {
        return repoGroupId;
    }

    public UUID getGitRepoId() {
        return gitRepoId;
    }

    public UUID getDependsOnGitRepoId() {
        return dependsOnGitRepoId;
    }
}
