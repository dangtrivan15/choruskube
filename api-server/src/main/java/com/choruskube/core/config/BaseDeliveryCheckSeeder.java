package com.choruskube.core.config;

import com.choruskube.core.model.GraphTemplate;
import com.choruskube.core.model.NodeDefinition;
import com.choruskube.core.model.enums.ExecutorType;
import com.choruskube.core.repository.GraphTemplateRepository;
import com.choruskube.core.repository.NodeDefinitionRepository;
import com.choruskube.core.repository.TemplateEdgeRepository;
import com.choruskube.core.repository.TemplateNodeRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Seeds the Delivery Check: the run a Task's "close as delivered" starts. Its one gate approves
 * itself as it opens and has the Worker verify that every pull request the close named is merged
 * into its repository's default branch; the run then completes and settles the Task like any other.
 */
@Component
@Order(2)
public class BaseDeliveryCheckSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(BaseDeliveryCheckSeeder.class);

    static final String GRAPH_ID = GraphIds.DELIVERY_CHECK;

    // A stored version is never re-seeded: a change to the graph or the schema below reaches a
    // database only with a bump here.
    static final int VERSION = 1;

    static final String TEMPLATE_NAME = "Delivery Check";

    public static final String NOTE_INPUT = "delivery_note";

    public static final String GATE_LABEL = "confirm_delivery";

    public static final String INPUT_SCHEMA = """
            [
              {"name":"software_project_id","label":"Software Project","type":"software_project_id","required":true},
              {"name":"delivery_note","label":"Delivery Note","type":"textarea","required":true}
            ]
            """;

    static final String GATE_CONFIG = "{\"terminal_decisions\": [\"approved\"],"
            + " \"verify_pull_requests_merged\": true, \"approve_on_arrival\": true}";

    private final GraphTemplateRepository templateRepo;
    private final TemplateWriter writer;
    private final ObjectMapper objectMapper;

    public BaseDeliveryCheckSeeder(
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
                throw new IllegalStateException("BaseDeliveryCheckSeeder: inputSchema has diverged for graphId='"
                        + GRAPH_ID + "' version=" + VERSION
                        + ". Update the version or reconcile the schema.");
            }
            log.info(
                    "BaseDeliveryCheckSeeder: template graphId='{}' v{} already exists — skipping seed",
                    GRAPH_ID,
                    VERSION);
            return;
        }

        log.info("BaseDeliveryCheckSeeder: seeding template graphId='{}' v{}", GRAPH_ID, VERSION);
        GraphTemplate template = new GraphTemplate();
        template.setGraphId(GRAPH_ID);
        template.setVersion(VERSION);
        template.setName(TEMPLATE_NAME);
        template.setDescription("Closes a Task whose work merged outside ChorusKube: verifies that each pull"
                + " request named is merged into its repository's default branch, then the Task closes.");
        template.setInputSchema(INPUT_SCHEMA);
        template.setPromptInputKey(NOTE_INPUT);
        template.setSystem(true);
        template = writer.template(template);

        NodeDefinition confirm = writer.nodeDef("Confirm Delivery", ExecutorType.human, null, 86400, null, null);
        writer.node(template, confirm, GATE_LABEL, true, GATE_CONFIG, null);
    }
}
