package com.choruskube.core.config;

import static org.assertj.core.api.Assertions.*;

import com.choruskube.core.BaseTest;
import com.choruskube.core.model.GraphTemplate;
import com.choruskube.core.repository.GraphTemplateRepository;
import com.choruskube.core.repository.NodeDefinitionRepository;
import com.choruskube.core.repository.TemplateEdgeRepository;
import com.choruskube.core.repository.TemplateNodeRepository;
import io.temporal.client.WorkflowClient;
import io.temporal.serviceclient.WorkflowServiceStubs;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

class BaseRoadmapProvisionerSeederTest extends BaseTest {

    @MockitoBean
    private WorkflowServiceStubs workflowServiceStubs;

    @MockitoBean
    private WorkflowClient workflowClient;

    @Autowired
    private BaseRoadmapProvisionerSeeder roadmapSeeder;

    @Autowired
    private GraphTemplateRepository templateRepo;

    @Autowired
    private NodeDefinitionRepository nodeDefRepo;

    @Autowired
    private TemplateNodeRepository templateNodeRepo;

    @Autowired
    private TemplateEdgeRepository edgeRepo;

    // --- Base template tests ---

    @Test
    void seedsRoadmapTemplateOnStartup() {
        var template = templateRepo.findByName("Roadmap Provisioner");
        assertThat(template).isPresent();
        assertThat(template.get().getGraphId()).isEqualTo("roadmap-provisioner");
        // v13 (Decision 6/2/3): terminal-decision human gate + deterministic materialization,
        // replacing the v12 3-node analyzer → gate → feature-creator shape.
        assertThat(template.get().getVersion()).isEqualTo(13);
    }

    @Test
    void idempotentSecondRun() throws Exception {
        roadmapSeeder.run(null);
        var templates = templateRepo.findAll().stream()
                .filter(t -> "Roadmap Provisioner".equals(t.getName()))
                .toList();
        assertThat(templates).hasSize(1);
    }

    @Test
    void templateHasCorrectInputSchema() {
        var template = templateRepo.findByName("Roadmap Provisioner").orElseThrow();
        assertThat(template.getInputSchema()).contains("software_project_id");
        assertThat(template.getInputSchema()).contains("\"type\": \"software_project_id\"");
        assertThat(template.getInputSchema()).contains("project_context");
        assertThat(template.getInputSchema()).doesNotContain("git_repo_id");
        assertThat(template.getInputSchema()).doesNotContain("\"type\": \"git_repo\"");
        assertThat(template.getInputSchema()).doesNotContain("api_base_url");
        assertThat(template.getInputSchema()).doesNotContain("feature_dev_graph_id");
        assertThat(template.getInputSchema()).doesNotContain("repo_url");
        assertThat(template.getInputSchema()).doesNotContain("agent_image");
        assertThat(template.getInputSchema()).doesNotContain("secrets");
    }

    @Test
    void projectContextFieldIsOptionalWithEmptyDefault() {
        var template = templateRepo.findByName("Roadmap Provisioner").orElseThrow();
        assertThat(template.getInputSchema()).contains("\"required\": false");
        assertThat(template.getInputSchema()).contains("\"default\": \"\"");
    }

    @Test
    void projectContextFieldUsesTextareaType() {
        var template = templateRepo.findByName("Roadmap Provisioner").orElseThrow();
        assertThat(template.getInputSchema()).contains("\"type\": \"textarea\"");
    }

    @Test
    void twoNodeDefinitionsAreCreated() {
        assertThat(nodeDefRepo.findAll().stream()
                        .filter(nd -> "Roadmap Analyzer".equals(nd.getName()))
                        .count())
                .isEqualTo(1);
        assertThat(nodeDefRepo.findAll().stream()
                        .filter(nd -> "Roadmap Human Gate".equals(nd.getName()))
                        .count())
                .isEqualTo(1);
        // v13 removes the "Roadmap Feature Creator" node entirely (Decision 2/3) — its
        // job (materializing approved candidates) is now a deterministic API server step.
        assertThat(nodeDefRepo.findAll().stream()
                        .filter(nd -> "Roadmap Feature Creator".equals(nd.getName()))
                        .count())
                .isEqualTo(0);
    }

    @Test
    void templateHasTwoNodesAndTwoEdges() {
        var template = templateRepo.findByName("Roadmap Provisioner").orElseThrow();
        assertThat(templateNodeRepo.findByGraphTemplateId(template.getId())).hasSize(2);
        assertThat(edgeRepo.findByGraphTemplateId(template.getId())).hasSize(2);
    }

    @Test
    void entrypointIsRoadmapAnalyzer() {
        var template = templateRepo.findByName("Roadmap Provisioner").orElseThrow();
        var nodes = templateNodeRepo.findByGraphTemplateId(template.getId());
        var entrypoints = nodes.stream().filter(n -> n.isEntrypoint()).toList();
        assertThat(entrypoints).hasSize(1);
        assertThat(entrypoints.get(0).getLabel()).isEqualTo("roadmap_analyzer");
    }

    @Test
    void edgesHaveCorrectConditions() {
        var template = templateRepo.findByName("Roadmap Provisioner").orElseThrow();
        var edges = edgeRepo.findByGraphTemplateId(template.getId());

        // One unconditional edge (Analyzer → Human Gate)
        assertThat(edges.stream().filter(e -> e.getCondition() == null).count()).isEqualTo(1);
        // One rejected edge (Human Gate → Analyzer)
        assertThat(edges.stream()
                        .filter(e -> "rejected".equals(e.getCondition()))
                        .count())
                .isEqualTo(1);
        // No "approved" edge at all — v13 makes "approved" a terminal_decisions entry
        // instead (Decision 2), not an edge to a third node.
        assertThat(edges.stream()
                        .filter(e -> "approved".equals(e.getCondition()))
                        .count())
                .isEqualTo(0);
    }

    @Test
    void humanGateHasTerminalDecisionsAndMaterializeConfig() throws Exception {
        var template = templateRepo.findByName("Roadmap Provisioner").orElseThrow();
        var humanGate = templateNodeRepo.findByGraphTemplateId(template.getId()).stream()
                .filter(n -> "roadmap_human_gate".equals(n.getLabel()))
                .findFirst()
                .orElseThrow();

        // Parsed rather than substring-matched: the config_overrides column is `jsonb`,
        // which Postgres re-serializes on storage (reordered keys, normalized whitespace) —
        // the exact text written by the seeder is not what comes back on read.
        var objectMapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var configOverrides = objectMapper.readTree(humanGate.getConfigOverrides());
        assertThat(configOverrides.get("terminal_decisions").toString()).isEqualTo("[\"approved\"]");
        assertThat(configOverrides.get("materialize").asText()).isEqualTo("roadmap_candidates");
        assertThat(humanGate.getRequiredInputArtifacts()).contains("roadmap_candidates.json");
    }

    @Test
    void analyzerOutputSpecDeclaresBothFiles() {
        var nd = nodeDefRepo.findAll().stream()
                .filter(n -> "Roadmap Analyzer".equals(n.getName()))
                .findFirst()
                .orElseThrow();
        assertThat(nd.getOutputSpec()).contains("roadmap_analysis.md");
        assertThat(nd.getOutputSpec()).contains("roadmap_candidates.json");
    }

    @Test
    void analyzerPromptDescribesStructuredCandidateSchema() {
        var nd = nodeDefRepo.findAll().stream()
                .filter(n -> "Roadmap Analyzer".equals(n.getName()))
                .findFirst()
                .orElseThrow();
        String prompt = nd.getPromptTemplate();
        assertThat(prompt).contains("roadmap_candidates.json");
        assertThat(prompt).contains("\"stories\"");
        assertThat(prompt).contains("\"tasks\"");
        // No fixed 1:1 language (Decision 5) — a variable-depth breakdown instead.
        assertThat(prompt).doesNotContain("one Story, containing one Task");
    }

    @Test
    void analyzerPromptReferencesMultiRepoWorkspace() {
        var nd = nodeDefRepo.findAll().stream()
                .filter(n -> "Roadmap Analyzer".equals(n.getName()))
                .findFirst()
                .orElseThrow();
        // v9 bump: the Analyzer prompt must use multi-repo workspace language aligned
        // with BaseFeatureDevSeeder, so RepoGroup runs aren't misdescribed.
        assertThat(nd.getPromptTemplate()).contains("/workspace/repo/<name>");
        assertThat(nd.getPromptTemplate()).contains("repos");
        // The legacy single-repo line must be gone.
        assertThat(nd.getPromptTemplate()).doesNotContain("The repository is cloned at /workspace/repo/.");
    }

    @Test
    void analyzerAndHumanGateShareLoopGroup() {
        var template = templateRepo.findByName("Roadmap Provisioner").orElseThrow();
        var nodes = templateNodeRepo.findByGraphTemplateId(template.getId());

        var analyzer = nodes.stream()
                .filter(n -> "roadmap_analyzer".equals(n.getLabel()))
                .findFirst()
                .orElseThrow();
        var humanGate = nodes.stream()
                .filter(n -> "roadmap_human_gate".equals(n.getLabel()))
                .findFirst()
                .orElseThrow();

        assertThat(analyzer.getConfigOverrides()).contains("\"loop_group\": \"proposal-review\"");
        assertThat(humanGate.getConfigOverrides()).contains("\"loop_group\": \"proposal-review\"");
    }

    @Test
    void reseedDoesNotTouchPreExistingOlderVersionRow() throws Exception {
        // Simulate a real deployment that already has the old v12 3-node shape seeded
        // (immutable seed data — never dropped, never mutated in place, per Decision 6).
        // Uses a distinct `name` (rather than the real "Roadmap Provisioner") so this
        // fixture row doesn't break `GraphTemplateRepository.findByName`'s single-result
        // assumption for every other test in this class/suite — only graphId+version
        // (what the seeder's own idempotency check keys on) needs to collide with v13.
        GraphTemplate v12 = new GraphTemplate();
        v12.setGraphId(BaseRoadmapProvisionerSeeder.GRAPH_ID);
        v12.setVersion(12);
        v12.setName("Roadmap Provisioner (legacy v12 test fixture)");
        v12.setDescription("pre-existing v12 row");
        v12.setInputSchema(
                templateRepo.findByName("Roadmap Provisioner").orElseThrow().getInputSchema());
        v12.setSystem(true);
        templateRepo.save(v12);

        roadmapSeeder.run(null);

        var v12Reloaded = templateRepo.findByGraphIdAndVersion(BaseRoadmapProvisionerSeeder.GRAPH_ID, 12);
        assertThat(v12Reloaded).isPresent();
        assertThat(v12Reloaded.get().getDescription()).isEqualTo("pre-existing v12 row");

        var v13 = templateRepo.findByGraphIdAndVersion(BaseRoadmapProvisionerSeeder.GRAPH_ID, 13);
        assertThat(v13).isPresent();

        var allRoadmapTemplates = templateRepo.findAll().stream()
                .filter(t -> BaseRoadmapProvisionerSeeder.GRAPH_ID.equals(t.getGraphId()))
                .toList();
        assertThat(allRoadmapTemplates).hasSize(2);
    }
}
