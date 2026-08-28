package com.choruskube.core.service;

import com.choruskube.core.dto.RoadmapTimelineResponse;

/**
 * Assembles the Roadmap Timeline View: every scoped Epic laid out as a lane, with its Stories
 * nested underneath, ready for a client-side time-scale layout — the layout math itself lives
 * in the web-ui, not here.
 */
public interface RoadmapTimelineService {

    RoadmapTimelineResponse getTimeline();
}
