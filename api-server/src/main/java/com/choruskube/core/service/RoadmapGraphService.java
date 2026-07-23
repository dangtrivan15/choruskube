package com.choruskube.core.service;

import com.choruskube.core.dto.RoadmapGraphSnapshot;
import java.util.UUID;

/**
 * Assembles the Roadmap Graph View: an Epic's full Story/Task tree plus its "blocking" dependency
 * edges (Part 2) — both intra-Epic edges and edges to items outside the Epic. Defined as an
 * interface, with {@link DefaultRoadmapGraphService} as its sole implementation (Decision 8).
 */
public interface RoadmapGraphService {

    /** @throws com.choruskube.core.exception.NotFoundException if the Epic does not exist. */
    RoadmapGraphSnapshot getGraph(UUID epicId);

    /**
     * Agent/internal mirror of {@link #getGraph(UUID)} for the {@code /internal/**} JOB_SECRET
     * path (Decision 1, Decision 5): validated via {@code assertSameOrg}/project-match against
     * {@code runId}/{@code runSoftwareProjectId} instead of the request-scoped
     * {@code checkOrgAccess} chain {@link #getGraph(UUID)} uses internally — there is no tenant
     * context on this path.
     *
     * @throws com.choruskube.core.exception.NotFoundException if the Epic does not exist.
     * @throws com.choruskube.core.exception.ForbiddenException if the Epic does not belong to the
     *     run's software project (or org).
     */
    RoadmapGraphSnapshot getGraph(UUID epicId, UUID runId, UUID runSoftwareProjectId);
}
