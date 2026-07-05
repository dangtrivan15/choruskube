package com.choruskube.core.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

@Entity
@Table(name = "repo_group_member")
@IdClass(RepoGroupMember.PK.class)
@EntityListeners(AuditingEntityListener.class)
public class RepoGroupMember {

    @Id
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "repo_group_id", nullable = false)
    private RepoGroup repoGroup;

    @Id
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "git_repo_id", nullable = false)
    private GitRepo gitRepo;

    @Column(nullable = false)
    private int position;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public RepoGroup getRepoGroup() {
        return repoGroup;
    }

    public void setRepoGroup(RepoGroup repoGroup) {
        this.repoGroup = repoGroup;
    }

    public GitRepo getGitRepo() {
        return gitRepo;
    }

    public void setGitRepo(GitRepo gitRepo) {
        this.gitRepo = gitRepo;
    }

    public int getPosition() {
        return position;
    }

    public void setPosition(int position) {
        this.position = position;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    /** Composite primary key used by JPA. */
    public static class PK implements java.io.Serializable {
        private UUID repoGroup;
        private UUID gitRepo;

        public PK() {}

        public PK(UUID repoGroup, UUID gitRepo) {
            this.repoGroup = repoGroup;
            this.gitRepo = gitRepo;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof PK pk)) return false;
            return Objects.equals(repoGroup, pk.repoGroup) && Objects.equals(gitRepo, pk.gitRepo);
        }

        @Override
        public int hashCode() {
            return Objects.hash(repoGroup, gitRepo);
        }
    }
}
