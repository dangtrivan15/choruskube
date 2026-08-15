package com.choruskube.core.repository;

import com.choruskube.core.model.Milestone;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

/**
 * The tenant-facing Milestone list is never backed by a derived {@code
 * findBySoftwareProjectId…} finder on this repository — that would bypass {@code ScopeProvider}
 * and leak another org's Milestones when a caller supplies a foreign {@code softwareProjectId}
 * (§3.4 of the Milestone spec). {@code DefaultMilestoneService#list} instead builds {@code
 * scopeProvider.scope(Milestone.class)} as a {@link org.springframework.data.jpa.domain.Specification}
 * — optionally {@code .and}-ing the {@code softwareProjectId} equality — and runs it through
 * {@link JpaSpecificationExecutor#findAll}, exactly as {@code DefaultEpicService.list} does.
 */
public interface MilestoneRepository extends JpaRepository<Milestone, UUID>, JpaSpecificationExecutor<Milestone> {

    boolean existsBySoftwareProjectIdAndNameIgnoreCase(UUID softwareProjectId, String name);
}
