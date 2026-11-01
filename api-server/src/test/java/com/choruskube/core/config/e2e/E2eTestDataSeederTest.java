package com.choruskube.core.config.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import com.choruskube.core.BaseTest;
import com.choruskube.core.config.BaseStoryDesignSeeder;
import com.choruskube.core.config.BaseTaskDeliverySeeder;
import com.choruskube.core.config.GraphIds;
import com.choruskube.core.config.StoryDesignGraph;
import com.choruskube.core.config.TaskDeliveryGraph;
import com.choruskube.core.model.GraphTemplate;
import com.choruskube.core.model.TemplateEdge;
import com.choruskube.core.model.TemplateNode;
import com.choruskube.core.repository.GitRepoRepository;
import com.choruskube.core.repository.GraphTemplateRepository;
import com.choruskube.core.repository.NodeDefinitionRepository;
import com.choruskube.core.repository.TemplateEdgeRepository;
import com.choruskube.core.repository.TemplateNodeRepository;
import com.choruskube.core.service.GraphSnapshotBuilder;
import com.choruskube.core.service.GraphValidationService;
import com.choruskube.core.service.RepoGroupService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.temporal.client.WorkflowClient;
import io.temporal.serviceclient.WorkflowServiceStubs;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;

/** Rolled back after each test: the shared test database must never keep the e2e fixtures. */
@Transactional
class E2eTestDataSeederTest extends BaseTest {

    private static final String MOCK = "/usr/local/bin/mock-agent.sh";

    @MockitoBean
    private WorkflowServiceStubs workflowServiceStubs;

    @MockitoBean
    private WorkflowClient workflowClient;

    @Autowired
    private GraphTemplateRepository templateRepo;

    @Autowired
    private NodeDefinitionRepository nodeDefRepo;

    @Autowired
    private TemplateNodeRepository templateNodeRepo;

    @Autowired
    private TemplateEdgeRepository edgeRepo;

    @Autowired
    private GitRepoRepository gitRepoRepo;

    @Autowired
    private RepoGroupService repoGroupService;

    @Autowired
    private GraphSnapshotBuilder snapshotBuilder;

    @Autowired
    private GraphValidationService graphValidationService;

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void seed() {
        E2eTestDataSeeder seeder = new E2eTestDataSeeder(
                templateRepo, nodeDefRepo, templateNodeRepo, edgeRepo, gitRepoRepo, repoGroupService);
        ReflectionTestUtils.setField(seeder, "agentImage", "claude-code:e2e");
        ReflectionTestUtils.setField(seeder, "mockScriptPath", MOCK);
        seeder.run(null);
    }

    @Test
    void serverStartedTemplatesResolveToSeededCopies() {
        E2eTemplateGraphIdResolver resolver = new E2eTemplateGraphIdResolver();
        for (String graphId : List.of(GraphIds.STORY_DESIGN, GraphIds.TASK_DELIVERY)) {
            assertThat(templateRepo.findFirstByGraphIdOrderByVersionDesc(resolver.resolve(graphId)))
                    .as("the copy %s resolves to", graphId)
                    .isPresent();
        }
    }

    @Test
    void storyDesignCopyIsTheProductionGraphWithTheMockAgent() throws Exception {
        GraphTemplate copy = copy(E2eTemplateGraphIdResolver.STORY_DESIGN_COPY);
        GraphTemplate production = production(GraphIds.STORY_DESIGN);

        assertTemplateMatches(copy, production, BaseStoryDesignSeeder.INPUT_SCHEMA);
        assertConfigsMatch(
                copy,
                production,
                Map.of(
                        StoryDesignGraph.DRAFT, MOCK + " story_spec_draft",
                        StoryDesignGraph.REVIEW,
                                MOCK + " story_spec_review --expect-input draft_story_spec/story_spec.md"));
        assertRunsNoClaudeAndValidates(copy);
    }

    @Test
    void taskDeliveryCopyIsTheProductionGraphWithTheMockAgent() throws Exception {
        GraphTemplate copy = copy(E2eTemplateGraphIdResolver.TASK_DELIVERY_COPY);
        GraphTemplate production = production(GraphIds.TASK_DELIVERY);

        assertTemplateMatches(copy, production, BaseTaskDeliverySeeder.INPUT_SCHEMA);
        // Test keeps production's command: the seeded repos declare no test command, which
        // run-all-tests reports as a pass.
        assertConfigsMatch(
                copy,
                production,
                Map.of(
                        TaskDeliveryGraph.ANALYZE, MOCK + " analyze --expect-input run_input/story_spec.md",
                        TaskDeliveryGraph.TASK_SPEC_REVIEW,
                                MOCK + " task_spec_review --expect-input plan/plan.md"
                                        + " --expect-input plan/task_spec.md",
                        TaskDeliveryGraph.IMPLEMENT,
                                MOCK + " success --artifact summary.md --expect-input plan/plan.md",
                        TaskDeliveryGraph.CODE_REVIEW,
                                MOCK + " gate_approve --artifact review.md --expect-input plan/plan.md"));
        assertRunsNoClaudeAndValidates(copy);
    }

    private GraphTemplate copy(String graphId) {
        return templateRepo
                .findByGraphIdAndVersion(graphId, E2eTestDataSeeder.VERSION)
                .orElseThrow(() -> new AssertionError(graphId + " v" + E2eTestDataSeeder.VERSION + " was not seeded"));
    }

    private GraphTemplate production(String graphId) {
        return templateRepo.findFirstByGraphIdOrderByVersionDesc(graphId).orElseThrow();
    }

    private void assertTemplateMatches(GraphTemplate copy, GraphTemplate production, String productionSchema)
            throws Exception {
        assertThat(copy.isSystem()).as("every org's runs resolve to the copy").isTrue();
        assertThat(objectMapper.readTree(copy.getInputSchema()))
                .as("the schema decides which repos a run resolves")
                .isEqualTo(objectMapper.readTree(productionSchema))
                .isEqualTo(objectMapper.readTree(production.getInputSchema()));
        assertThat(copy.getPromptInputKey()).isEqualTo(production.getPromptInputKey());

        assertThat(edgesOf(copy)).isEqualTo(edgesOf(production));
        Map<String, TemplateNode> copyNodes = nodesByLabel(copy);
        Map<String, TemplateNode> productionNodes = nodesByLabel(production);
        assertThat(copyNodes.keySet()).isEqualTo(productionNodes.keySet());
        for (String label : productionNodes.keySet()) {
            TemplateNode c = copyNodes.get(label);
            TemplateNode p = productionNodes.get(label);
            assertThat(c.isEntrypoint()).as(label).isEqualTo(p.isEntrypoint());
            assertThat(json(c.getRequiredInputArtifacts())).as(label).isEqualTo(json(p.getRequiredInputArtifacts()));
            assertThat(nodeDefName(c)).as(label).isEqualTo(nodeDefName(p));
        }
    }

    /** Each mocked label is production's config plus a script executor running its command. */
    private void assertConfigsMatch(GraphTemplate copy, GraphTemplate production, Map<String, String> mockCommands)
            throws Exception {
        Map<String, TemplateNode> copyNodes = nodesByLabel(copy);
        Map<String, TemplateNode> productionNodes = nodesByLabel(production);
        for (String label : productionNodes.keySet()) {
            ObjectNode copyConfig =
                    (ObjectNode) objectMapper.readTree(copyNodes.get(label).getConfigOverrides());
            JsonNode productionConfig =
                    objectMapper.readTree(productionNodes.get(label).getConfigOverrides());
            String command = mockCommands.get(label);
            if (command != null) {
                assertThat(copyConfig.path("executor_type").asText()).as(label).isEqualTo("script");
                assertThat(copyConfig.path("command").asText()).as(label).isEqualTo(command);
                copyConfig.remove(List.of("executor_type", "command"));
            }
            assertThat(copyConfig).as(label).isEqualTo(productionConfig);
        }
    }

    private void assertRunsNoClaudeAndValidates(GraphTemplate copy) {
        assertThat(snapshotBuilder.anyNodeRunsClaude(copy.getId()))
                .as("the e2e stack has no Claude to run")
                .isFalse();
        assertThat(graphValidationService
                        .validate(
                                templateNodeRepo.findByGraphTemplateId(copy.getId()),
                                edgeRepo.findByGraphTemplateId(copy.getId()))
                        .errors())
                .isEmpty();
    }

    /** Compared parsed: a row read back from the database comes normalized by its jsonb column. */
    private JsonNode json(String value) throws Exception {
        return value == null ? null : objectMapper.readTree(value);
    }

    private Map<String, TemplateNode> nodesByLabel(GraphTemplate t) {
        return templateNodeRepo.findByGraphTemplateId(t.getId()).stream()
                .collect(Collectors.toMap(TemplateNode::getLabel, Function.identity(), (a, b) -> a, TreeMap::new));
    }

    private String nodeDefName(TemplateNode node) {
        return nodeDefRepo.findById(node.getNodeDefinitionId()).orElseThrow().getName();
    }

    private Set<List<String>> edgesOf(GraphTemplate t) {
        Map<UUID, String> labelById = templateNodeRepo.findByGraphTemplateId(t.getId()).stream()
                .collect(Collectors.toMap(TemplateNode::getId, TemplateNode::getLabel));
        return edgeRepo.findByGraphTemplateId(t.getId()).stream()
                .map((TemplateEdge e) -> List.of(
                        labelById.get(e.getSourceNodeId()),
                        labelById.get(e.getTargetNodeId()),
                        String.valueOf(e.getCondition())))
                .collect(Collectors.toSet());
    }
}
