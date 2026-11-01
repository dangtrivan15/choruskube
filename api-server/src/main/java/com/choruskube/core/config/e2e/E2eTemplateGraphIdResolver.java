package com.choruskube.core.config.e2e;

import com.choruskube.core.config.GraphIds;
import com.choruskube.core.config.TemplateGraphIdResolver;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** The e2e stack runs the mock agent, so server-started runs use the mock copies of these graphs. */
@Component
@Profile("e2e")
public class E2eTemplateGraphIdResolver implements TemplateGraphIdResolver {

    static final String STORY_DESIGN_COPY = "e2e-story-design";
    static final String TASK_DELIVERY_COPY = "e2e-task-delivery";

    private static final Map<String, String> MOCK_COPIES = Map.of(
            GraphIds.STORY_DESIGN, STORY_DESIGN_COPY,
            GraphIds.TASK_DELIVERY, TASK_DELIVERY_COPY);

    @Override
    public String resolve(String graphId) {
        return MOCK_COPIES.getOrDefault(graphId, graphId);
    }
}
