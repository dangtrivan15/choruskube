package com.choruskube.core.service;

import com.choruskube.core.config.GraphIds;
import com.choruskube.core.config.TemplateGraphIdResolver;
import com.choruskube.core.exception.BadRequestException;
import com.choruskube.core.model.GraphTemplate;
import com.choruskube.core.repository.GraphTemplateRepository;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Templates whose runs only a Story or a Task may start. A Story Design run is recorded as a spec
 * version only through the Story stamped on it, and Task Delivery's Analyze escalates without the
 * Story spec a Task start stages, so a run started from the Runs page has neither and is wasted. A
 * Delivery Check verifies the pull requests a Task's close names, and has none without one.
 * The same sentence refuses such a start and tells the Start Run dialog why it lists them disabled.
 */
@Component
public class RoadmapOnlyTemplates {

    private static final String STORY_DESIGN_REASON = "Starts from a Story: use Design Story on the Story's page.";
    private static final String TASK_DELIVERY_REASON = "Starts from a Task: use Start on the Task's page.";
    private static final String DELIVERY_CHECK_REASON =
            "Starts from a Task: use Close as delivered on the Task's page.";

    private static final Map<String, String> REASON_BY_CANONICAL_GRAPH_ID = Map.of(
            GraphIds.STORY_DESIGN, STORY_DESIGN_REASON,
            GraphIds.TASK_DELIVERY, TASK_DELIVERY_REASON,
            GraphIds.DELIVERY_CHECK, DELIVERY_CHECK_REASON);

    private final GraphTemplateRepository templateRepo;
    private final Map<String, String> reasonByGraphId;

    public RoadmapOnlyTemplates(GraphTemplateRepository templateRepo, TemplateGraphIdResolver graphIdResolver) {
        this.templateRepo = templateRepo;
        // A Story or Task start runs the template the resolver picks, which needs that Story or Task too.
        Map<String, String> reasons = new HashMap<>(REASON_BY_CANONICAL_GRAPH_ID);
        REASON_BY_CANONICAL_GRAPH_ID.forEach(
                (graphId, reason) -> reasons.put(graphIdResolver.resolve(graphId), reason));
        this.reasonByGraphId = Map.copyOf(reasons);
    }

    /** Why a run of this graph cannot be started on its own; empty when it can. */
    public Optional<String> standaloneStartRefusal(String graphId) {
        return Optional.ofNullable(graphId).map(reasonByGraphId::get);
    }

    /** For the public start only: the Story and Task starts call {@link RunService#startRun} directly. */
    public void refuseStandaloneStart(UUID graphTemplateId) {
        templateRepo
                .findById(graphTemplateId)
                .map(GraphTemplate::getGraphId)
                .flatMap(this::standaloneStartRefusal)
                .ifPresent(reason -> {
                    throw new BadRequestException(reason);
                });
    }
}
