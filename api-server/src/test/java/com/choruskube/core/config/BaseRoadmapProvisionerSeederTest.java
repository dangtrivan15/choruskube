package com.choruskube.core.config;

import static org.assertj.core.api.Assertions.*;

import com.choruskube.core.BaseTest;
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
        assertThat(template.get().getVersion()).isEqualTo(11);
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
    void threeNodeDefinitionsAreCreated() {
        assertThat(nodeDefRepo.findAll().stream()
                        .filter(nd -> "Roadmap Analyzer".equals(nd.getName()))
                        .count())
                .isEqualTo(1);
        assertThat(nodeDefRepo.findAll().stream()
                        .filter(nd -> "Roadmap Human Gate".equals(nd.getName()))
                        .count())
                .isEqualTo(1);
        assertThat(nodeDefRepo.findAll().stream()
                        .filter(nd -> "Roadmap Feature Creator".equals(nd.getName()))
                        .count())
                .isEqualTo(1);
    }

    @Test
    void templateHasThreeNodesAndThreeEdges() {
        var template = templateRepo.findByName("Roadmap Provisioner").orElseThrow();
        assertThat(templateNodeRepo.findByGraphTemplateId(template.getId())).hasSize(3);
        assertThat(edgeRepo.findByGraphTemplateId(template.getId())).hasSize(3);
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
        // One approved edge (Human Gate → Feature Creator)
        assertThat(edges.stream()
                        .filter(e -> "approved".equals(e.getCondition()))
                        .count())
                .isEqualTo(1);
        // One rejected edge (Human Gate → Analyzer)
        assertThat(edges.stream()
                        .filter(e -> "rejected".equals(e.getCondition()))
                        .count())
                .isEqualTo(1);
    }

    @Test
    void featureCreatorPromptMentionsMultiRepoFlag() {
        var nd = nodeDefRepo.findAll().stream()
                .filter(n -> "Roadmap Feature Creator".equals(n.getName()))
                .findFirst()
                .orElseThrow();
        // v8 bump: the Feature Creator prompt must teach `--repo` for multi-repo proposals.
        assertThat(nd.getPromptTemplate()).contains("--repo");
        assertThat(nd.getPromptTemplate()).contains("ONE or TWO");
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
}
