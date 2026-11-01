package com.choruskube.core.config;

import com.choruskube.core.model.GraphTemplate;
import com.choruskube.core.repository.GraphTemplateRepository;
import com.choruskube.core.repository.NodeDefinitionRepository;
import com.choruskube.core.repository.TemplateEdgeRepository;
import com.choruskube.core.repository.TemplateNodeRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@Order(2)
public class BaseStoryDesignSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(BaseStoryDesignSeeder.class);

    static final String GRAPH_ID = GraphIds.STORY_DESIGN;

    // A stored version is never re-seeded: a change to StoryDesignGraph, StoryDesignPrompts or the
    // schema below reaches a database only with a bump here, so without one, existing databases
    // silently keep the old template while fresh ones get the new.
    static final int VERSION = 1;

    static final String TEMPLATE_NAME = "Story Design";

    private static final String INPUT_SCHEMA = """
            [
              {"name":"software_project_id","label":"Software Project","type":"software_project_id","required":true},
              {"name":"story_request","label":"Story Request","type":"textarea","required":true},
              {"name":"revision_context","label":"Revision Context","type":"textarea","required":false,"default":""}
            ]
            """;

    private final GraphTemplateRepository templateRepo;
    private final TemplateWriter writer;
    private final ObjectMapper objectMapper;

    public BaseStoryDesignSeeder(
            GraphTemplateRepository templateRepo,
            NodeDefinitionRepository nodeDefRepo,
            TemplateNodeRepository templateNodeRepo,
            TemplateEdgeRepository edgeRepo,
            ObjectMapper objectMapper) {
        this.templateRepo = templateRepo;
        this.writer = new TemplateWriter(templateRepo, nodeDefRepo, templateNodeRepo, edgeRepo);
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) throws Exception {
        var existing = templateRepo.findByGraphIdAndVersion(GRAPH_ID, VERSION);
        if (existing.isPresent()) {
            var stored = objectMapper.readValue(existing.get().getInputSchema(), new TypeReference<Object>() {});
            var expected = objectMapper.readValue(INPUT_SCHEMA, new TypeReference<Object>() {});
            if (!stored.equals(expected)) {
                throw new IllegalStateException("BaseStoryDesignSeeder: inputSchema has diverged for graphId='"
                        + GRAPH_ID + "' version=" + VERSION
                        + ". Update the version or reconcile the schema.");
            }
            log.info(
                    "BaseStoryDesignSeeder: template graphId='{}' v{} already exists — skipping seed",
                    GRAPH_ID,
                    VERSION);
            return;
        }

        log.info("BaseStoryDesignSeeder: seeding template graphId='{}' v{}", GRAPH_ID, VERSION);
        GraphTemplate template = new GraphTemplate();
        template.setGraphId(GRAPH_ID);
        template.setVersion(VERSION);
        template.setName(TEMPLATE_NAME);
        template.setDescription("Designs a Story once: drafts its spec and Task breakdown, reviews them, and on"
                + " approval records the spec as the Story's next version and creates the proposed Tasks.");
        template.setInputSchema(INPUT_SCHEMA);
        template.setPromptInputKey("story_request");
        template.setSystem(true);
        template = writer.template(template);

        StoryDesignGraph.seed(writer, template, Map.of());
    }
}
