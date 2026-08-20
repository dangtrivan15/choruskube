package com.choruskube.core.service;

import com.choruskube.core.dto.MilestoneAtRiskItemsResponse;
import com.choruskube.core.dto.MilestoneRequest;
import com.choruskube.core.dto.MilestoneResponse;
import com.choruskube.core.dto.MilestoneUpdateRequest;
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
}
