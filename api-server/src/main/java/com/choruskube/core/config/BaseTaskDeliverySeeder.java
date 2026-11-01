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
public class BaseTaskDeliverySeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(BaseTaskDeliverySeeder.class);

    static final String GRAPH_ID = GraphIds.TASK_DELIVERY;

    // A stored version is never re-seeded: a change to TaskDeliveryGraph, TaskDeliveryPrompts, the
    // shared DeliveryPrompts or the schema below reaches a database only with a bump here, so without
    // one, existing databases silently keep the old template while fresh ones get the new.
    static final int VERSION = 1;

    static final String TEMPLATE_NAME = "Task Delivery";

    public static final String INPUT_SCHEMA = """
            [
              {"name":"software_project_id","label":"Software Project","type":"software_project_id","required":true},
              {"name":"feature_request","label":"Feature Request","type":"textarea","required":true}
            ]
            """;

    private final GraphTemplateRepository templateRepo;
    private final TemplateWriter writer;
    private final ObjectMapper objectMapper;

    public BaseTaskDeliverySeeder(
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
                throw new IllegalStateException("BaseTaskDeliverySeeder: inputSchema has diverged for graphId='"
                        + GRAPH_ID + "' version=" + VERSION
                        + ". Update the version or reconcile the schema.");
            }
            log.info(
                    "BaseTaskDeliverySeeder: template graphId='{}' v{} already exists — skipping seed",
                    GRAPH_ID,
                    VERSION);
            return;
        }

        log.info("BaseTaskDeliverySeeder: seeding template graphId='{}' v{}", GRAPH_ID, VERSION);
        GraphTemplate template = new GraphTemplate();
        template.setGraphId(GRAPH_ID);
        template.setVersion(VERSION);
        template.setName(TEMPLATE_NAME);
        template.setDescription("Delivers one Task of a designed Story: plans its files against current main,"
                + " drafts a Task spec for review only when the Story spec leaves a design choice open, sends a"
                + " Story spec it finds wrong back for revision, then implements, reviews and tests the change.");
        template.setInputSchema(INPUT_SCHEMA);
        template.setPromptInputKey("feature_request");
        template.setSystem(true);
        template = writer.template(template);

        TaskDeliveryGraph.seed(writer, template, Map.of());
    }
}
