package com.choruskube.core.repository;

import java.util.UUID;

/**
 * Native-query projection of the fields a {@code SoftwareProjectRef} needs, selected straight from
 * the {@code software_project} parent table. It exists so read paths can resolve a project that has
 * been soft-deleted: the native query bypasses the entity-level
 * {@code @SQLRestriction("deleted_at IS NULL")}, and selecting only parent-table columns sidesteps
 * the JOINED-inheritance hydration a full-entity fetch would require.
 */
public interface SoftwareProjectRefRow {
    UUID getId();

    String getType();

    String getName();
}
