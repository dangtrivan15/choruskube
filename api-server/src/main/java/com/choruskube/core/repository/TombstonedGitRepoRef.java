package com.choruskube.core.repository;

import java.util.UUID;

/**
 * Projection of a soft-deleted {@link com.choruskube.core.model.GitRepo} — the minimum a cleanup path
 * needs (id for DB delete). Repos no longer own K8s namespaces — provisioning is at the org level.
 * Used by the reconciler's native driver query so @SQLRestriction on the entity doesn't filter
 * out tombstoned rows.
 */
public interface TombstonedGitRepoRef {
    UUID getId();
}
