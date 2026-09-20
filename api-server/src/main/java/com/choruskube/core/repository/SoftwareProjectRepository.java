package com.choruskube.core.repository;

import com.choruskube.core.model.SoftwareProject;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SoftwareProjectRepository
        extends JpaRepository<SoftwareProject, UUID>, JpaSpecificationExecutor<SoftwareProject> {

    Optional<SoftwareProject> findByName(String name);

    /**
     * Resolve a project ref by id including soft-deleted rows. The native query bypasses the
     * entity-level {@code @SQLRestriction("deleted_at IS NULL")} so a Task/Epic linked to a
     * soft-deleted project still reads back (the row is kept precisely so linked work stays
     * readable); selecting only parent-table columns avoids JOINED-inheritance hydration.
     */
    @Query(
            value = "SELECT id AS id, type AS type, name AS name FROM software_project WHERE id = :id",
            nativeQuery = true)
    Optional<SoftwareProjectRefRow> findRefByIdIncludingDeleted(@Param("id") UUID id);

    @Query(
            value = "SELECT id AS id, type AS type, name AS name FROM software_project WHERE id IN (:ids)",
            nativeQuery = true)
    List<SoftwareProjectRefRow> findRefsByIdInIncludingDeleted(@Param("ids") Collection<UUID> ids);
}
