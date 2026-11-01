package com.choruskube.core.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.choruskube.core.BaseTest;
import com.choruskube.core.model.GraphTemplate;
import com.choruskube.core.model.TemplateNode;
import com.choruskube.core.model.enums.ExecutorType;
import com.choruskube.core.repository.GraphTemplateRepository;
import com.choruskube.core.repository.NodeDefinitionRepository;
import com.choruskube.core.repository.TemplateEdgeRepository;
import com.choruskube.core.repository.TemplateNodeRepository;
import com.choruskube.core.service.GraphValidationService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.temporal.client.WorkflowClient;
import io.temporal.serviceclient.WorkflowServiceStubs;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

class BaseStoryDesignSeederTest extends BaseTest {

    @MockitoBean
    private WorkflowServiceStubs workflowServiceStubs;

    @MockitoBean
    private WorkflowClient workflowClient;

    @Autowired
    private BaseStoryDesignSeeder seeder;

    @Autowired
    private GraphTemplateRepository templateRepo;

    @Autowired
    private NodeDefinitionRepository nodeDefRepo;

    @Autowired
    private TemplateNodeRepository templateNodeRepo;

    @Autowired
    private TemplateEdgeRepository edgeRepo;

    @Autowired
    private GraphValidationService graphValidationService;

    @Autowired
    private ObjectMapper objectMapper;

    private GraphTemplate template() {
        return templateRepo
                .findByGraphIdAndVersion(GraphIds.STORY_DESIGN, BaseStoryDesignSeeder.VERSION)
                .orElseThrow();
    }

    private Map<String, TemplateNode> nodesByLabel() {
        return templateNodeRepo.findByGraphTemplateId(template().getId()).stream()
                .collect(Collectors.toMap(TemplateNode::getLabel, Function.identity()));
    }

    @Test
    void versionTripwire() {
        // A prompt, graph or schema change needs a bump: a stored version is never re-seeded.
        assertThat(BaseStoryDesignSeeder.VERSION).isEqualTo(1);
        assertThat(templateRepo.findByGraphIdAndVersion("story-design", 1)).isPresent();
    }

    @Test
    void wiring() {
        var nodes = templateNodeRepo.findByGraphTemplateId(template().getId());
        var edges = edgeRepo.findByGraphTemplateId(template().getId());
        assertThat(nodes).hasSize(4);
        assertThat(edges).hasSize(5);

        assertThat(nodes.stream().filter(TemplateNode::isEntrypoint))
                .extracting(TemplateNode::getLabel)
                .containsExactly(StoryDesignGraph.DRAFT);

        Map<UUID, String> labelById =
                nodes.stream().collect(Collectors.toMap(TemplateNode::getId, TemplateNode::getLabel));
        assertThat(edges)
                .extracting(
                        e -> labelById.get(e.getSourceNodeId()),
                        e -> labelById.get(e.getTargetNodeId()),
                        e -> e.getCondition())
                .containsExactlyInAnyOrder(
                        tuple("draft_story_spec", "story_spec_review", null),
                        tuple("story_spec_review", "approve_story_spec", "approved"),
                        tuple("story_spec_review", "story_spec_review", "revised"),
                        tuple("approve_story_spec", "story_spec_review", "rereview"),
                        tuple("approve_story_spec", "draft_story_spec", "redraft"));
    }

    @Test
    void approveGateConfig() throws Exception {
        TemplateNode approve = nodesByLabel().get(StoryDesignGraph.APPROVE);
        assertThat(approve.getConfigOverrides())
                .contains("\"terminal_decisions\": [\"approved\"]")
                .contains("\"materialize\": \"roadmap_extension\"")
                .contains("\"loop_group\": \"story-spec\"")
                .as("the spec is design-level; each Task re-plans against current main")
                .doesNotContain("drift_recheck");

        assertThat(producersOf(approve, "roadmap_candidates.json"))
                .containsExactlyInAnyOrder(StoryDesignGraph.REVIEW, StoryDesignGraph.DRAFT);
        assertThat(requiredArtifactsOf(approve))
                .containsExactlyInAnyOrder("story_spec_review/story_spec.md", "story_spec_review/story_spec_review.md");
    }

    @Test
    void reviewReadsTheDraftAndItsOwnPriorIteration() throws Exception {
        TemplateNode review = nodesByLabel().get(StoryDesignGraph.REVIEW);
        assertThat(requiredArtifactsOf(review)).containsExactly("draft_story_spec/story_spec.md");
        assertThat(producersOf(review, "story_spec_review.md")).containsExactly(StoryDesignGraph.REVIEW);
        assertThat(producersOf(review, "roadmap_candidates.json"))
                .containsExactlyInAnyOrder(StoryDesignGraph.DRAFT, StoryDesignGraph.REVIEW);
        assertThat(review.getConfigOverrides())
                .contains("\"loop_group\": \"story-spec\"")
                .contains("\"model_first_iteration\": \"" + ModelIds.MODEL_OPUS + "\"")
                .contains("\"model_subsequent_iteration\": \"" + ModelIds.MODEL_SONNET + "\"");
    }

    @Test
    void nodeDefinitionsCarryExecutorModelAndTimeout() {
        var nodes = nodesByLabel();
        var draft = nodeDefRepo
                .findById(nodes.get(StoryDesignGraph.DRAFT).getNodeDefinitionId())
                .orElseThrow();
        assertThat(draft.getName()).isEqualTo("Draft Story Spec");
        assertThat(draft.getExecutorType()).isEqualTo(ExecutorType.ai);
        assertThat(draft.getModel()).isEqualTo(ModelIds.MODEL_OPUS);
        assertThat(draft.getTimeoutSeconds()).isEqualTo(3600);
        assertThat(draft.getOutputSpec()).contains("story_spec.md").contains("roadmap_candidates.json");
        assertThat(nodes.get(StoryDesignGraph.DRAFT).getConfigOverrides()).contains("\"effort\": \"xhigh\"");

        var review = nodeDefRepo
                .findById(nodes.get(StoryDesignGraph.REVIEW).getNodeDefinitionId())
                .orElseThrow();
        assertThat(review.getName()).isEqualTo("Story Spec Review");
        assertThat(review.getExecutorType()).isEqualTo(ExecutorType.ai);
        assertThat(review.getTimeoutSeconds()).isEqualTo(1800);
        assertThat(review.getOutputSpec())
                .contains("story_spec.md")
                .contains("story_spec_review.md")
                .contains("roadmap_candidates.json");

        var approve = nodeDefRepo
                .findById(nodes.get(StoryDesignGraph.APPROVE).getNodeDefinitionId())
                .orElseThrow();
        assertThat(approve.getName()).isEqualTo("Approve Story Spec");
        assertThat(approve.getExecutorType()).isEqualTo(ExecutorType.human);

        TemplateNode hub = nodes.get(StoryDesignGraph.SUPERVISOR);
        assertThat(hub.getConfigOverrides()).contains("\"routing_hub\": true");
        var supervisor = nodeDefRepo.findById(hub.getNodeDefinitionId()).orElseThrow();
        assertThat(supervisor.getName()).isEqualTo("Supervisor");
        // GraphValidationService sees no NodeDefinition, so the hub's executor is pinned here.
        assertThat(supervisor.getExecutorType()).isEqualTo(ExecutorType.human);
    }

    @Test
    void templateTakesTheStoryRequestAndAnOptionalRevisionContext() throws Exception {
        GraphTemplate t = template();
        assertThat(t.getName()).isEqualTo("Story Design");
        assertThat(t.getPromptInputKey()).isEqualTo("story_request");
        assertThat(t.isSystem()).isTrue();

        Map<String, JsonNode> fields = new java.util.HashMap<>();
        objectMapper
                .readTree(t.getInputSchema())
                .forEach(f -> fields.put(f.get("name").asText(), f));
        assertThat(fields).containsOnlyKeys("software_project_id", "story_request", "revision_context");
        assertThat(fields.get("software_project_id").get("type").asText()).isEqualTo("software_project_id");
        assertThat(fields.get("software_project_id").get("required").asBoolean())
                .isTrue();
        assertThat(fields.get("story_request").get("type").asText()).isEqualTo("textarea");
        assertThat(fields.get("story_request").get("required").asBoolean()).isTrue();
        assertThat(fields.get("revision_context").get("type").asText()).isEqualTo("textarea");
        assertThat(fields.get("revision_context").get("required").asBoolean()).isFalse();
        assertThat(fields.get("revision_context").get("default").asText())
                .as("both prompts reference {run.revision_context}; an absent value fails the node")
                .isEmpty();
    }

    @Test
    void passesGraphValidation() {
        var nodes = templateNodeRepo.findByGraphTemplateId(template().getId());
        var edges = edgeRepo.findByGraphTemplateId(template().getId());
        assertThat(graphValidationService.validate(nodes, edges).errors()).isEmpty();
    }

    @Test
    void idempotentSecondRun() throws Exception {
        seeder.run(null);
        assertThat(templateRepo.findAllByGraphId(GraphIds.STORY_DESIGN)).hasSize(1);
        assertThat(storyDesignNodeDefsNamed("Draft Story Spec")).isEqualTo(1);
    }

    /**
     * Rows with this name, minus those another template's nodes use: the e2e copy of Story Design
     * reuses these names. A row no template uses still counts, so a leaked re-seed is caught.
     */
    private long storyDesignNodeDefsNamed(String name) {
        Set<UUID> ourTemplateIds = templateRepo.findAllByGraphId(GraphIds.STORY_DESIGN).stream()
                .map(GraphTemplate::getId)
                .collect(Collectors.toSet());
        Set<UUID> usedByOtherTemplates = templateNodeRepo.findAll().stream()
                .filter(tn -> !ourTemplateIds.contains(tn.getGraphTemplateId()))
                .map(TemplateNode::getNodeDefinitionId)
                .collect(Collectors.toSet());
        return nodeDefRepo.findAll().stream()
                .filter(nd -> name.equals(nd.getName()) && !usedByOtherTemplates.contains(nd.getId()))
                .count();
    }

    /** The node labels whose group declares {@code fileName}. */
    private List<String> producersOf(TemplateNode node, String fileName) throws Exception {
        List<String> producers = new ArrayList<>();
        for (JsonNode group : objectMapper.readTree(node.getRequiredInputArtifacts())) {
            for (JsonNode artifact : group.get("artifacts")) {
                if (fileName.equals(artifact.get("name").asText())) {
                    producers.add(group.get("template_node_label").asText());
                }
            }
        }
        return producers;
    }

    /** {@code <label>/<file>} for every artifact the node declares as required. */
    private List<String> requiredArtifactsOf(TemplateNode node) throws Exception {
        List<String> required = new ArrayList<>();
        for (JsonNode group : objectMapper.readTree(node.getRequiredInputArtifacts())) {
            for (JsonNode artifact : group.get("artifacts")) {
                if (artifact.path("required").asBoolean(false)) {
                    required.add(group.get("template_node_label").asText() + "/"
                            + artifact.get("name").asText());
                }
            }
        }
        return required;
    }
}
