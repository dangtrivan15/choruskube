package com.choruskube.core.service;

import com.choruskube.core.dto.MilestoneAtRiskItemsResponse;
import com.choruskube.core.dto.MilestoneRequest;
import com.choruskube.core.dto.MilestoneResponse;
import com.choruskube.core.dto.MilestoneUpdateRequest;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/**
 * CRUD plus assigned-Epic-count aggregation for Milestones (release/grouping labels, Decision 1
 * of the "Group Epics under a named Milestone / Release" feature). Defined as an interface, with
 * {@link DefaultMilestoneService} as its sole implementation, mirroring {@link EpicService}'s own
 * interface/impl split (Decision 8 of the roadmap-hierarchy feature).
 */
public interface MilestoneService {

    MilestoneResponse create(MilestoneRequest request);

    /**
     * Lists Milestones, optionally narrowed to a single software project. Scoped via {@code
     * ScopeProvider} exactly as {@link EpicService#list} is (§3.4 of the Milestone spec) — never a
     * derived {@code findBySoftwareProjectId…} finder, which would bypass tenant scoping. Every
     * returned {@link MilestoneResponse#epicCount()} is computed as one batch for the whole page,
     * not a per-Milestone query.
     */
    Page<MilestoneResponse> list(UUID softwareProjectId, Pageable pageable);

    MilestoneResponse get(UUID id);

    MilestoneResponse update(UUID id, MilestoneUpdateRequest request);

    /** Deletes the Milestone. Its Epics are un-tagged (not deleted) via the FK's ON DELETE SET NULL. */
    void delete(UUID id);

    /**
     * Drill-down behind {@link MilestoneResponse#atRiskItemCount()}: every Epic tagged with this
     * Milestone, and every Story under one of those Epics, whose {@code targetDate} is strictly
     * before today and whose {@code RollupCalculator} effective status is not {@code done}.
     */
    MilestoneAtRiskItemsResponse getAtRiskItems(UUID id);

    /**
     * Find-or-create by name within a software project (Decision 4 of the roadmap dependencies/
     * priorities/milestones feature) — used by {@code RoadmapCandidateMaterializer} so a candidate
     * Milestone whose name collides with one already tagged in the project reuses that row instead
     * of failing materialization the way {@link #create} would (it rejects duplicate names with
     * {@code ConflictException}, which is the correct behavior for the public create route but
     * would abort a whole batch here). Matched case-insensitively, same as {@link #create}'s own
     * uniqueness check. When an existing Milestone is found, it is returned unchanged — {@code
     * description}/{@code targetDate} on the candidate are NOT applied to it, so a second
     * materialization run against the same-named Milestone never silently overwrites a human's
     * edits to it.
     *
     * <p>Request-scoped path only: {@code RoadmapCandidateMaterializer} runs from a human's gate
     * approval, which carries a real JWT (and therefore a populated {@code TenantContext}) — see
     * {@link #findOrCreateInternal} for the agent/{@code JOB_SECRET} counterpart, which has none.
     *
     * <p>Implemented as a {@code ScopeProvider}-scoped {@code Specification} run through the
     * pre-existing {@code JpaSpecificationExecutor<Milestone>.findOne}, mirroring {@link
     * #list}'s own established pattern — {@code MilestoneRepository} gains no new derived finder
     * for this path (its javadoc forbids one; see the repository for why).
     */
    MilestoneResponse findOrCreate(UUID softwareProjectId, String name, String description, LocalDate targetDate);

    /**
     * Agent/internal counterpart to {@link #findOrCreate} — the imperative {@code create-milestone}
     * CLI's write path (Decision 6), called from {@code InternalRunService#createMilestone} on the
     * {@code JOB_SECRET} path, which has no request-scoped {@code TenantContext}. Mirrors {@code
     * WorkItemDependencyService#createForRun}'s split from {@code #create}: same dedup/create
     * logic as {@link #findOrCreate}, but the org guard is {@code AuthorizationService#assertSameOrg}
     * against the calling {@code runId} instead of a {@code ScopeProvider}-scoped lookup — in this
     * repo's Keycloak-enabled overlay, {@code ScopeProvider} resolves to {@code
     * OwnershipScopeProvider}, whose {@code Specification} reads {@code TenantContext} lazily and
     * throws {@code UnresolvableTenantException} the moment it is evaluated on a thread where that
     * context was never populated — turning every agent-created Milestone into a 403.
     */
    MilestoneResponse findOrCreateInternal(
            UUID softwareProjectId, String name, String description, LocalDate targetDate, UUID runId);
}
