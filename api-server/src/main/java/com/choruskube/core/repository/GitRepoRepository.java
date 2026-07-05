package com.choruskube.core.repository;

import com.choruskube.core.model.GitRepo;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface GitRepoRepository extends JpaRepository<GitRepo, UUID>, JpaSpecificationExecutor<GitRepo> {
    Optional<GitRepo> findByUrl(String url);

    /**
     * Hard-delete a single tombstoned row. The WHERE clause scopes to rows that are already
     * soft-deleted, so this is idempotent: a second call affects zero rows. Used by both the
     * afterCommit cleanup hook and the reconciler.
     *
     * <p>After V44, {@code deleted_at} lives on {@code software_project}. The DELETE there
     * cascades to {@code git_repo} via {@code git_repo_id_fk ... ON DELETE CASCADE}.
     */
    @Modifying
    @Query(
            value = "DELETE FROM software_project " + "WHERE id = :id AND type = 'git_repo' AND deleted_at IS NOT NULL",
            nativeQuery = true)
    int hardDeleteTombstoneById(@Param("id") UUID id);

    /**
     * Reconciler driver query — returns id projections for tombstoned rows.
     * Repos no longer own namespaces — just need the id for hard-delete.
     * Native query bypasses the entity-level @SQLRestriction("deleted_at IS NULL").
     *
     * <p>After V44, {@code deleted_at} lives on {@code software_project}. Filter to
     * {@code type='git_repo'} so the reconciler's git-repo path doesn't pick up groups.
     */
    @Query(
            value = "SELECT id AS id FROM software_project "
                    + "WHERE type = 'git_repo' AND deleted_at IS NOT NULL LIMIT :batchSize",
            nativeQuery = true)
    List<TombstonedGitRepoRef> findTombstonedBatch(@Param("batchSize") int batchSize);
}
