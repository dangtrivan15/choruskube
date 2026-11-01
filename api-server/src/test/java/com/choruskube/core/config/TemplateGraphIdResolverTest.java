package com.choruskube.core.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.choruskube.core.config.e2e.E2eTemplateGraphIdResolver;
import org.junit.jupiter.api.Test;

class TemplateGraphIdResolverTest {

    @Test
    void defaultResolver_returnsTheGraphIdItWasGiven() {
        TemplateGraphIdResolver resolver = new DefaultTemplateGraphIdResolver();

        assertThat(resolver.resolve(GraphIds.STORY_DESIGN)).isEqualTo(GraphIds.STORY_DESIGN);
        assertThat(resolver.resolve(GraphIds.TASK_DELIVERY)).isEqualTo(GraphIds.TASK_DELIVERY);
    }

    @Test
    void e2eResolver_mapsServerStartedTemplatesToTheirMockCopies() {
        TemplateGraphIdResolver resolver = new E2eTemplateGraphIdResolver();

        assertThat(resolver.resolve(GraphIds.STORY_DESIGN)).isEqualTo("e2e-story-design");
        assertThat(resolver.resolve(GraphIds.TASK_DELIVERY)).isEqualTo("e2e-task-delivery");
        assertThat(resolver.resolve(GraphIds.FEATURE_DEVELOPMENT)).isEqualTo(GraphIds.FEATURE_DEVELOPMENT);
    }
}
