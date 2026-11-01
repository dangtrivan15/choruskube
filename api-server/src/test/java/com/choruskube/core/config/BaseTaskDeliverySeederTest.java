package com.choruskube.core.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.choruskube.core.BaseTest;
import com.choruskube.core.model.GraphTemplate;
import com.choruskube.core.model.NodeDefinition;
import com.choruskube.core.model.TemplateNode;
import com.choruskube.core.model.enums.ExecutorType;
import com.choruskube.core.repository.GraphTemplateRepository;
import com.choruskube.core.repository.NodeDefinitionRepository;
import com.choruskube.core.repository.TemplateEdgeRepository;
import com.choruskube.core.repository.TemplateNodeRepository;
import com.choruskube.core.service.GraphValidationService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.temporal.client.WorkflowClient;
import io.temporal.serviceclient.WorkflowServiceStubs;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

class BaseTaskDeliverySeederTest extends BaseTest {

    @MockitoBean
    private WorkflowServiceStubs workflowServiceStubs;

    @MockitoBean
    private WorkflowClient workflowClient;

    @Autowired
    private BaseTaskDeliverySeeder seeder;

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
                .findByGraphIdAndVersion(GraphIds.TASK_DELIVERY, BaseTaskDeliverySeeder.VERSION)
                .orElseThrow();
    }

    private GraphTemplate featureDevelopment() {
        return templateRepo
                .findByGraphIdAndVersion(GraphIds.FEATURE_DEVELOPMENT, BaseFeatureDevSeeder.CURRENT_VERSION)
                .orElseThrow();
    }

    private Map<String, TemplateNode> nodesByLabel(GraphTemplate t) {
        return templateNodeRepo.findByGraphTemplateId(t.getId()).stream()
                .collect(Collectors.toMap(TemplateNode::getLabel, Function.identity()));
    }

    private NodeDefinition definitionOf(TemplateNode node) {
        return nodeDefRepo.findById(node.getNodeDefinitionId()).orElseThrow();
    }

    // What VERSION 1 seeds: its prompts (shared DeliveryPrompts text included), node configs,
    // input specs and graph. A stored version is never re-seeded, so a change here without a bump
    // leaves existing databases on the old template: bump VERSION and update both pins together.
    private static final int PINNED_VERSION = 1;
    private static final String PINNED_SHA256 = "9c97ea57a025ac859b5304e4c13080a29f5b1c8ee2253092e314780f3f9631ff";

    @Test
    void versionTripwire() throws Exception {
        assertThat(SeededTemplateDigest.sha256(BaseTaskDeliverySeeder::new))
                .as("seeded content changed: bump BaseTaskDeliverySeeder.VERSION and re-pin PINNED_SHA256 with it")
                .isEqualTo(PINNED_SHA256);
        assertThat(BaseTaskDeliverySeeder.VERSION)
                .as("a VERSION bump re-pins PINNED_SHA256 to what the new version seeds")
                .isEqualTo(PINNED_VERSION);
        assertThat(templateRepo.findByGraphIdAndVersion("task-delivery", PINNED_VERSION))
                .isPresent();
    }

    @Test
    void wiring() {
        var nodes = templateNodeRepo.findByGraphTemplateId(template().getId());
        var edges = edgeRepo.findByGraphTemplateId(template().getId());
        assertThat(nodes).hasSize(9);
        assertThat(edges).hasSize(15);

        assertThat(nodes.stream().filter(TemplateNode::isEntrypoint))
                .extracting(TemplateNode::getLabel)
                .containsExactly(TaskDeliveryGraph.ANALYZE);

        Map<UUID, String> labelById =
                nodes.stream().collect(Collectors.toMap(TemplateNode::getId, TemplateNode::getLabel));
        assertThat(edges)
                .extracting(
                        e -> labelById.get(e.getSourceNodeId()),
                        e -> labelById.get(e.getTargetNodeId()),
                        e -> e.getCondition())
                .containsExactlyInAnyOrder(
                        tuple("analyze", "implement", "planned"),
                        tuple("analyze", "task_spec_review", "needs_spec"),
                        tuple("analyze", "await_story_revision", "story_conflict"),
                        tuple("await_story_revision", "analyze", "resume"),
                        tuple("task_spec_review", "approve_task_spec", "approved"),
                        tuple("task_spec_review", "task_spec_review", "revised"),
                        tuple("approve_task_spec", "implement", "approved"),
                        tuple("approve_task_spec", "task_spec_review", "rereview"),
                        tuple("approve_task_spec", "analyze", "redraft"),
                        tuple("implement", "code_review", null),
                        tuple("code_review", "code_review", "revised"),
                        tuple("code_review", "test", "approved"),
                        tuple("test", "final_approval", "passed"),
                        tuple("test", "implement", "failed"),
                        tuple("final_approval", "code_review", "rereview"));
    }

    @Test
    void awaitGateTimeoutIsZero() throws Exception {
        TemplateNode await = nodesByLabel(template()).get(TaskDeliveryGraph.AWAIT_STORY_REVISION);
        NodeDefinition def = definitionOf(await);
        assertThat(def.getName()).isEqualTo("Await Story Revision");
        assertThat(def.getExecutorType()).isEqualTo(ExecutorType.human);
        assertThat(def.getTimeoutSeconds())
                .as("0 is the orchestrator's 7-day gate timer; a revision takes longer than a day")
                .isZero();
        assertThat(objectMapper.readTree(await.getConfigOverrides())).isEqualTo(objectMapper.readTree("{}"));
        assertThat(requiredArtifactsOf(await)).containsExactly("analyze/story_conflict.md");
    }

    @Test
    void gatesDeclareStoryVersionRecheck() throws Exception {
        Map<String, TemplateNode> nodes = nodesByLabel(template());
        JsonNode recheck = objectMapper.readTree("{\"route\": \"rereview\"}");

        JsonNode approve = objectMapper.readTree(
                nodes.get(TaskDeliveryGraph.APPROVE_TASK_SPEC).getConfigOverrides());
        assertThat(approve.get("story_version_recheck")).isEqualTo(recheck);
        assertThat(approve.get("loop_group").asText()).isEqualTo("task-spec");
        assertThat(approve.get("drift_recheck"))
                .isEqualTo(objectMapper.readTree("{\"route\": \"rereview\", \"stamp\": \"always\"}"));

        ObjectNode finalApproval = (ObjectNode) objectMapper.readTree(
                nodes.get(TaskDeliveryGraph.FINAL_APPROVAL).getConfigOverrides());
        assertThat(finalApproval.get("story_version_recheck")).isEqualTo(recheck);
        finalApproval.remove("story_version_recheck");
        assertThat(finalApproval)
                .as("Feature Development's terminal_decisions, materialize, merge_pull_requests and drift_recheck")
                .isEqualTo(objectMapper.readTree(
                        nodesByLabel(featureDevelopment()).get("final_approval").getConfigOverrides()));
    }

    @Test
    void implementAndCodeReviewDeclarePlanGroup() throws Exception {
        Map<String, TemplateNode> nodes = nodesByLabel(template());
        for (String label : List.of(TaskDeliveryGraph.IMPLEMENT, TaskDeliveryGraph.CODE_REVIEW)) {
            JsonNode plan = planGroupOf(nodes.get(label));
            assertThat(plan).as(label).isNotNull();
            assertThat(plan.get("newest_of")).isEqualTo(objectMapper.readTree("[\"analyze\", \"task_spec_review\"]"));
            assertThat(plan.get("alias").asText()).isEqualTo("plan");
            assertThat(artifactFlags(plan))
                    .as(label + ": a stale task_spec.md must not reach a re-planned Task")
                    .containsExactlyInAnyOrderEntriesOf(Map.of("plan.md", true, "task_spec.md", false));
        }
    }

    @Test
    void tailMatchesFeatureDevelopmentExceptItsSpecInputs() throws Exception {
        // Task Delivery copies Feature Development's tail; a change to one must reach the other.
        Map<String, TemplateNode> ours = nodesByLabel(template());
        Map<String, TemplateNode> theirs = nodesByLabel(featureDevelopment());
        for (String label : List.of(
                TaskDeliveryGraph.IMPLEMENT,
                TaskDeliveryGraph.TEST,
                TaskDeliveryGraph.CODE_REVIEW,
                TaskDeliveryGraph.FINAL_APPROVAL,
                TaskDeliveryGraph.SUPERVISOR)) {
            NodeDefinition ourDef = definitionOf(ours.get(label));
            NodeDefinition theirDef = definitionOf(theirs.get(label));
            assertThat(ourDef.getName()).as(label).isEqualTo(theirDef.getName());
            assertThat(ourDef.getExecutorType()).as(label).isEqualTo(theirDef.getExecutorType());
            assertThat(ourDef.getModel()).as(label).isEqualTo(theirDef.getModel());
            assertThat(ourDef.getTimeoutSeconds()).as(label).isEqualTo(theirDef.getTimeoutSeconds());
            assertThat(objectMapper.readTree(ourDef.getOutputSpec()))
                    .as(label)
                    .isEqualTo(objectMapper.readTree(theirDef.getOutputSpec()));
            if (!label.equals(TaskDeliveryGraph.FINAL_APPROVAL)) {
                assertThat(objectMapper.readTree(ours.get(label).getConfigOverrides()))
                        .as(label)
                        .isEqualTo(objectMapper.readTree(theirs.get(label).getConfigOverrides()));
            }
        }

        assertThat(inputGroupsExcept(ours.get(TaskDeliveryGraph.IMPLEMENT), "plan"))
                .isEqualTo(inputGroupsExcept(theirs.get("implement"), "spec_review"));
        assertThat(inputGroupsExcept(ours.get(TaskDeliveryGraph.CODE_REVIEW), "plan"))
                .isEqualTo(inputGroupsExcept(theirs.get("code_review"), null));
        assertThat(inputGroupsExcept(ours.get(TaskDeliveryGraph.FINAL_APPROVAL), null))
                .isEqualTo(inputGroupsExcept(theirs.get("final_approval"), null));
    }

    @Test
    void analyzePlansWithoutABranch() throws Exception {
        TemplateNode analyze = nodesByLabel(template()).get(TaskDeliveryGraph.ANALYZE);
        NodeDefinition def = definitionOf(analyze);
        assertThat(def.getName()).isEqualTo("Analyze Task");
        assertThat(def.getExecutorType()).isEqualTo(ExecutorType.ai);
        assertThat(def.getModel()).isEqualTo(ModelIds.MODEL_OPUS);
        assertThat(def.getTimeoutSeconds()).isEqualTo(3600);
        assertThat(def.getPromptTemplate()).isEqualTo(TaskDeliveryPrompts.ANALYZE_PROMPT);
        assertThat(outputFlags(def))
                .containsExactlyInAnyOrderEntriesOf(
                        Map.of("plan.md", true, "task_spec.md", false, "story_conflict.md", false));

        JsonNode config = objectMapper.readTree(analyze.getConfigOverrides());
        assertThat(config.get("effort").asText()).isEqualTo("high");
        assertThat(config.has("needs_branch")).as("Analyze never pushes").isFalse();
        assertThat(config.has("loop_group"))
                .as("the prompt reads no {review_history}, which only a loop-group node gets")
                .isFalse();
    }

    @Test
    void taskSpecReviewOwnsThePlanAndSpecItReviews() throws Exception {
        TemplateNode review = nodesByLabel(template()).get(TaskDeliveryGraph.TASK_SPEC_REVIEW);
        NodeDefinition def = definitionOf(review);
        assertThat(def.getName()).isEqualTo("Task Spec Review");
        assertThat(def.getPromptTemplate()).isEqualTo(TaskDeliveryPrompts.TASK_SPEC_REVIEW_PROMPT);
        assertThat(outputFlags(def))
                .containsExactlyInAnyOrderEntriesOf(
                        Map.of("plan.md", true, "task_spec.md", true, "task_spec_review.md", true));
        JsonNode plan = planGroupOf(review);
        assertThat(plan)
                .as("after a redraft, Analyze's new copy must win over the reviewer's older one")
                .isNotNull();
        assertThat(plan.get("newest_of")).isEqualTo(objectMapper.readTree("[\"analyze\", \"task_spec_review\"]"));
        assertThat(artifactFlags(plan))
                .containsExactlyInAnyOrderEntriesOf(Map.of("plan.md", true, "task_spec.md", true));
        assertThat(requiredArtifactsOf(review)).isEmpty();
        assertThat(producersOf(review, "task_spec_review.md")).containsExactly(TaskDeliveryGraph.TASK_SPEC_REVIEW);

        JsonNode config = objectMapper.readTree(review.getConfigOverrides());
        JsonNode specReview = objectMapper.readTree(
                nodesByLabel(featureDevelopment()).get("spec_review").getConfigOverrides());
        assertThat(config.get("loop_group").asText()).isEqualTo("task-spec");
        for (String key : List.of(
                "model_first_iteration",
                "effort_first_iteration",
                "model_subsequent_iteration",
                "effort_subsequent_iteration")) {
            assertThat(config.get(key)).as(key).isEqualTo(specReview.get(key));
        }
    }

    @Test
    void approveTaskSpecShowsTheNewestPlanAndTheReview() throws Exception {
        TemplateNode approve = nodesByLabel(template()).get(TaskDeliveryGraph.APPROVE_TASK_SPEC);
        NodeDefinition def = definitionOf(approve);
        assertThat(def.getName()).isEqualTo("Approve Task Spec");
        assertThat(def.getExecutorType()).isEqualTo(ExecutorType.human);
        assertThat(def.getTimeoutSeconds()).isEqualTo(86400);

        JsonNode plan = planGroupOf(approve);
        assertThat(plan).isNotNull();
        assertThat(artifactFlags(plan))
                .containsExactlyInAnyOrderEntriesOf(Map.of("plan.md", true, "task_spec.md", false));
        assertThat(requiredArtifactsOf(approve)).containsExactly("task_spec_review/task_spec_review.md");
    }

    @Test
    void templateTakesFeatureDevelopmentsInput() throws Exception {
        GraphTemplate t = template();
        assertThat(t.getName()).isEqualTo("Task Delivery");
        assertThat(t.getPromptInputKey()).isEqualTo("feature_request");
        assertThat(t.isSystem()).isTrue();
        assertThat(objectMapper.readTree(t.getInputSchema()))
                .as("a Task start composes the same input for either template")
                .isEqualTo(objectMapper.readTree(featureDevelopment().getInputSchema()));
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
        assertThat(templateRepo.findAllByGraphId(GraphIds.TASK_DELIVERY)).hasSize(1);
        assertThat(taskDeliveryNodeDefsNamed("Analyze Task")).isEqualTo(1);
        assertThat(taskDeliveryNodeDefsNamed("Implement")).isEqualTo(1);
    }

    /**
     * Rows with this name, minus those another template's nodes use: Feature Development shares the
     * tail's names. A row no template uses still counts, so a leaked re-seed is caught.
     */
    private long taskDeliveryNodeDefsNamed(String name) {
        Set<UUID> ourTemplateIds = templateRepo.findAllByGraphId(GraphIds.TASK_DELIVERY).stream()
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

    /** The node's {@code newest_of} group aliased {@code plan}, or null. */
    private JsonNode planGroupOf(TemplateNode node) throws Exception {
        for (JsonNode group : objectMapper.readTree(node.getRequiredInputArtifacts())) {
            if ("plan".equals(group.path("alias").asText(null))) {
                return group;
            }
        }
        return null;
    }

    /** The node's input groups in order, minus the one named (by alias or producer label) {@code excludedAliasOrLabel}. */
    private List<JsonNode> inputGroupsExcept(TemplateNode node, String excludedAliasOrLabel) throws Exception {
        List<JsonNode> groups = new ArrayList<>();
        for (JsonNode group : objectMapper.readTree(node.getRequiredInputArtifacts())) {
            String name = group.has("alias")
                    ? group.get("alias").asText()
                    : group.get("template_node_label").asText();
            if (!name.equals(excludedAliasOrLabel)) {
                groups.add(group);
            }
        }
        return groups;
    }

    private static Map<String, Boolean> artifactFlags(JsonNode group) {
        return flags((ArrayNode) group.get("artifacts"));
    }

    private Map<String, Boolean> outputFlags(NodeDefinition def) throws Exception {
        return flags((ArrayNode) objectMapper.readTree(def.getOutputSpec()).get("files"));
    }

    private static Map<String, Boolean> flags(ArrayNode entries) {
        Map<String, Boolean> flags = new HashMap<>();
        entries.forEach(
                e -> flags.put(e.get("name").asText(), e.path("required").asBoolean(false)));
        return flags;
    }

    /** The node labels whose label group declares {@code fileName}. */
    private List<String> producersOf(TemplateNode node, String fileName) throws Exception {
        List<String> producers = new ArrayList<>();
        for (JsonNode group : objectMapper.readTree(node.getRequiredInputArtifacts())) {
            for (JsonNode artifact : group.get("artifacts")) {
                if (fileName.equals(artifact.get("name").asText()) && group.has("template_node_label")) {
                    producers.add(group.get("template_node_label").asText());
                }
            }
        }
        return producers;
    }

    /** {@code <label>/<file>} for every artifact a label group declares as required. */
    private List<String> requiredArtifactsOf(TemplateNode node) throws Exception {
        List<String> required = new ArrayList<>();
        for (JsonNode group : objectMapper.readTree(node.getRequiredInputArtifacts())) {
            if (!group.has("template_node_label")) {
                continue;
            }
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
