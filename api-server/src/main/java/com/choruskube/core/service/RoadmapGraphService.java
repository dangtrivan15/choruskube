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
}
