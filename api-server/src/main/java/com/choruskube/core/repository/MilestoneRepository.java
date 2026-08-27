package com.choruskube.core.repository;

import com.choruskube.core.model.Milestone;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

/**
 * The tenant-facing Milestone list is never backed by a derived {@code
 * findBySoftwareProjectId…} finder on this repository — that would bypass {@code ScopeProvider}
 * and leak another org's Milestones when a caller supplies a foreign {@code softwareProjectId}
 * (of the Milestone spec). {@code DefaultMilestoneService#list} instead builds {@code
 * scopeProvider.scope(Milestone.class)} as a {@link org.springframework.data.jpa.domain.Specification}
 * — optionally {@code .and}-ing the {@code softwareProjectId} equality — and runs it through
 * {@link JpaSpecificationExecutor#findAll}, exactly as {@code DefaultEpicService.list} does.
 */
public interface MilestoneRepository extends JpaRepository<Milestone, UUID>, JpaSpecificationExecutor<Milestone> {

    boolean existsBySoftwareProjectIdAndNameIgnoreCase(UUID softwareProjectId, String name);

    /**
     * Derived finder used ONLY by {@code DefaultMilestoneService#findOrCreateInternal} (the
     * agent/{@code JOB_SECRET} path, part of the roadmap dependencies/priorities/milestones
     * feature) — this is a narrow, deliberate exception to the class-level rule above, not a
     * violation of it. That rule protects a caller-supplied {@code softwareProjectId} with no other
     * verification; here, the caller (`InternalRunService#createMilestone`) has already asserted the
     * project belongs to the same org as the calling run via {@code AuthorizationService#assertSameOrg}
     * before this finder ever runs, so there is no unscoped lookup left for {@code ScopeProvider} to
     * guard — and {@code ScopeProvider} cannot run here anyway (this repo's {@code
     * OwnershipScopeProvider} reads the request-scoped {@code TenantContext}, which does not exist on
     * the {@code JOB_SECRET} path; see {@code findOrCreateInternal}'s Javadoc).
     */
    Optional<Milestone> findFirstBySoftwareProjectIdAndNameIgnoreCase(UUID softwareProjectId, String name);
}
