package com.choruskube.core.service;

import com.choruskube.core.config.GraphIds;
import com.choruskube.core.config.e2e.E2eTemplateGraphIdResolver;
import com.choruskube.core.exception.BadRequestException;
import com.choruskube.core.model.GraphTemplate;
import com.choruskube.core.repository.GraphTemplateRepository;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Templates whose runs only a Story or a Task may start. A Story Design run is recorded as a spec
 * version only through the Story stamped on it, and Task Delivery's Analyze escalates without the
 * Story spec a Task start stages, so a run started from the Runs page has neither and is wasted.
 */
@Component
public class RoadmapOnlyTemplates {

    private static final String STORY_DESIGN_REASON =
            "Story Design runs start from a Story: use Design Story on the Story's page.";
    private static final String TASK_DELIVERY_REASON =
            "Task Delivery runs start from a Task: use Start on the Task's page.";

    // The e2e copies are listed too: the mock graphs need the same Story or Task the real ones do.
    private static final Map<String, String> REASON_BY_GRAPH_ID = Map.of(
            GraphIds.STORY_DESIGN, STORY_DESIGN_REASON,
            E2eTemplateGraphIdResolver.STORY_DESIGN_COPY, STORY_DESIGN_REASON,
            GraphIds.TASK_DELIVERY, TASK_DELIVERY_REASON,
            E2eTemplateGraphIdResolver.TASK_DELIVERY_COPY, TASK_DELIVERY_REASON);

    private final GraphTemplateRepository templateRepo;

    public RoadmapOnlyTemplates(GraphTemplateRepository templateRepo) {
        this.templateRepo = templateRepo;
    }

    /** For the public start only: the Story and Task starts call {@link RunService#startRun} directly. */
    public void refuseStandaloneStart(UUID graphTemplateId) {
        templateRepo
                .findById(graphTemplateId)
                .map(GraphTemplate::getGraphId)
                .map(REASON_BY_GRAPH_ID::get)
                .ifPresent(reason -> {
                    throw new BadRequestException(reason);
                });
    }
}
