package com.choruskube.core.service;

import static org.assertj.core.api.Assertions.*;

import com.choruskube.core.BaseTest;
import com.choruskube.core.config.GraphIds;
import com.choruskube.core.model.GitRepo;
import com.choruskube.core.model.GraphTemplate;
import com.choruskube.core.model.NodeDefinition;
import com.choruskube.core.model.TemplateNode;
import com.choruskube.core.model.WorkflowRun;
import com.choruskube.core.model.enums.ExecutorType;
import com.choruskube.core.repository.GitRepoRepository;
import com.choruskube.core.repository.GraphTemplateRepository;
import com.choruskube.core.repository.NodeDefinitionRepository;
import com.choruskube.core.repository.TemplateNodeRepository;
import com.choruskube.core.repository.WorkflowRunRepository;
import com.choruskube.core.util.RepoNameUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

public class GraphSnapshotBuilderTest extends BaseTest {

    @Autowired
    private GraphSnapshotBuilder snapshotBuilder;

    @Autowired
    private GitRepoRepository gitRepoRepo;

    @Autowired
    private GraphTemplateRepository templateRepo;

    @Autowired
    private TemplateNodeRepository templateNodeRepo;

    @Autowired
    private NodeDefinitionRepository nodeDefRepo;

    @Autowired
    private WorkflowRunRepository runRepo;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void buildSnapshotForRunUsesGraphSourceTemplateId() throws Exception {
        var baseTemplate = templateRepo
                .findFirstByGraphIdOrderByVersionDesc(GraphIds.FEATURE_DEVELOPMENT)
                .orElseThrow();
        var expectedNodeIds = templateNodeRepo.findByGraphTemplateId(baseTemplate.getId());

        // Create a GitRepo and link to template
        GitRepo gitRepo = new GitRepo();
        gitRepo.setUrl("https://github.com/snapshot-test/repo");
        gitRepo.setName(RepoNameUtil.deriveOwnerRepoName("https://github.com/snapshot-test/repo"));
        gitRepo.setTestCommand("t");
        gitRepo.setAgentImage("i");
        gitRepo.setSecrets("[]");
        gitRepo = gitRepoRepo.save(gitRepo);

        WorkflowRun run = new WorkflowRun();
        run.setGraphTemplateId(baseTemplate.getId());
        run.setInputs("{\"feature_request\":\"test\",\"software_project_id\":\"" + gitRepo.getId() + "\"}");
        run = runRepo.save(run);

        String snapshot = snapshotBuilder.buildSnapshotForRun(run);
        JsonNode snapshotJson = objectMapper.readTree(snapshot);

        Set<String> snapshotNodeIds = new HashSet<>();
        for (JsonNode n : snapshotJson.get("nodes")) {
            snapshotNodeIds.add(n.get("template_node_id").asText());
        }

        Set<String> expectedIds = new HashSet<>();
        for (var tn : expectedNodeIds) {
            expectedIds.add(tn.getId().toString());
        }

        assertThat(snapshotNodeIds).isEqualTo(expectedIds);

        // Verify repo fields injected into snapshot inputs
        assertThat(snapshotJson.get("inputs").get("repo_url").asText())
                .isEqualTo("https://github.com/snapshot-test/repo");
    }

    @Test
    void buildSnapshotForRunPropagatesEnableDockerFlagWithoutRegistryConfig() throws Exception {
        var baseTemplate = templateRepo
                .findFirstByGraphIdOrderByVersionDesc(GraphIds.FEATURE_DEVELOPMENT)
                .orElseThrow();

        GitRepo gitRepo = new GitRepo();
        gitRepo.setUrl("https://github.com/docker-flag-test/repo");
        gitRepo.setName(RepoNameUtil.deriveOwnerRepoName("https://github.com/docker-flag-test/repo"));
        gitRepo.setTestCommand("t");
        gitRepo.setAgentImage("i");
        gitRepo.setSecrets("[]");
        gitRepo.setEnableDocker(true);
        gitRepo = gitRepoRepo.save(gitRepo);

        WorkflowRun run = new WorkflowRun();
        run.setGraphTemplateId(baseTemplate.getId());
        run.setInputs("{\"feature_request\":\"test\",\"software_project_id\":\"" + gitRepo.getId() + "\"}");
        run = runRepo.save(run);

        String snapshot = snapshotBuilder.buildSnapshotForRun(run);
        JsonNode snapshotJson = objectMapper.readTree(snapshot);

        assertThat(snapshotJson.get("enable_docker").asBoolean()).isTrue();
        assertThat(snapshotJson.has("docker_config")).isFalse();
        assertThat(snapshotJson.has("namespace")).isFalse();
    }

    @Test
    void buildSnapshotForRunWithNoGitRepoStillBuilds() throws Exception {
        var baseTemplate = templateRepo
                .findFirstByGraphIdOrderByVersionDesc(GraphIds.FEATURE_DEVELOPMENT)
                .orElseThrow();

        WorkflowRun run = new WorkflowRun();
        run.setGraphTemplateId(baseTemplate.getId());
        run.setInputs("{\"feature_request\":\"test\"}");
        run = runRepo.save(run);

        String snapshot = snapshotBuilder.buildSnapshotForRun(run);
        JsonNode snapshotJson = objectMapper.readTree(snapshot);

        assertThat(snapshotJson.get("nodes")).isNotEmpty();
        assertThat(snapshotJson.has("namespace")).isFalse();
        assertThat(snapshotJson.has("docker_config")).isFalse();
    }

    @Test
    void buildSnapshotIncludesDecisionOptionsForPlainEdgeGate() throws Exception {
        // "Approve Spec & Plan" (feature-development) has three outgoing edges — approved,
        // rereview, redraft — and no terminal_decisions config: a plain edge-driven gate. v35
        // (Decision 2 in the accompanying spec) gave Final Approval a terminal_decisions entry
        // alongside its remaining edge, so it's no longer a plain edge-driven gate — that mixed
        // edge+terminal_decisions shape is instead covered below by "Roadmap Human Gate" (a
        // different node/template with the same general shape: one real edge plus a
        // terminal_decisions entry), not by a dedicated Final-Approval-specific snapshot
        // assertion. v37 retired "Review Escalation" (which this test targeted previously)
        // outright in favor of the edgeless Supervisor routing hub — see
        // V1TemplateSeederTest#v37SupervisorHasNoEdges for that node's own coverage.
        var baseTemplate = templateRepo
                .findFirstByGraphIdOrderByVersionDesc(GraphIds.FEATURE_DEVELOPMENT)
                .orElseThrow();
        var nodes = templateNodeRepo.findByGraphTemplateId(baseTemplate.getId());
        var approveSpecAndPlan = nodes.stream()
                .filter(n -> "approve_spec_and_plan".equals(n.getLabel()))
                .findFirst()
                .orElseThrow();

        WorkflowRun run = new WorkflowRun();
        run.setGraphTemplateId(baseTemplate.getId());
        run.setInputs("{\"feature_request\":\"test\"}");
        run = runRepo.save(run);

        JsonNode snapshotJson = objectMapper.readTree(snapshotBuilder.buildSnapshotForRun(run));
        JsonNode node = findSnapshotNode(snapshotJson, approveSpecAndPlan.getId());

        List<String> decisionOptions = new ArrayList<>();
        node.get("decision_options").forEach(o -> decisionOptions.add(o.asText()));
        assertThat(decisionOptions).containsExactlyInAnyOrder("approved", "rereview", "redraft");
    }

    @Test
    void buildSnapshotIncludesDecisionOptionsForTerminalDecisionGate() throws Exception {
        // "Roadmap Human Gate" (roadmap-provisioner) has one outgoing edge (rejected) plus
        // config_overrides.terminal_decisions == ["approved"] — the Roadmap Provisioner's
        // edge-less "approved ends the run" pattern this fix targets.
        var baseTemplate = templateRepo
                .findFirstByGraphIdOrderByVersionDesc(GraphIds.ROADMAP_PROVISIONER)
                .orElseThrow();
        var nodes = templateNodeRepo.findByGraphTemplateId(baseTemplate.getId());
        var humanGate = nodes.stream()
                .filter(n -> "roadmap_human_gate".equals(n.getLabel()))
                .findFirst()
                .orElseThrow();

        WorkflowRun run = new WorkflowRun();
        run.setGraphTemplateId(baseTemplate.getId());
        run.setInputs("{\"project_context\":\"test\"}");
        run = runRepo.save(run);

        JsonNode snapshotJson = objectMapper.readTree(snapshotBuilder.buildSnapshotForRun(run));
        JsonNode node = findSnapshotNode(snapshotJson, humanGate.getId());

        List<String> decisionOptions = new ArrayList<>();
        node.get("decision_options").forEach(o -> decisionOptions.add(o.asText()));
        assertThat(decisionOptions).containsExactly("rejected", "approved");
    }

    @Test
    void buildSnapshotDecisionOptionsEmptyForNodeWithNoEdgesOrTerminalDecisions() throws Exception {
        // feature-development's Supervisor (v37) now has exactly this shape (routing_hub,
        // zero edges, no terminal_decisions), but GraphSnapshotBuilder is expected to special-case
        // routing hubs rather than fall through to this empty-options path — see
        // DecisionOptionsResolver. Build a minimal standalone template with this shape instead of
        // depending on seeded data having an incidental dead-end node — this decouples the
        // regression guard from a structural property of a real template that is free to change
        // for unrelated reasons.
        GraphTemplate template = new GraphTemplate();
        template.setName("Dead-End Node Test Template");
        template.setGraphId("dead-end-node-test");
        template.setVersion(1);
        template = templateRepo.save(template);

        NodeDefinition nodeDef = new NodeDefinition();
        nodeDef.setName("dead-end-test-node");
        nodeDef.setExecutorType(ExecutorType.ai);
        nodeDef.setImage("test:latest");
        nodeDef.setPromptTemplate("test");
        nodeDef.setSkills("[]");
        nodeDef.setInputSpec("{}");
        nodeDef.setOutputSpec("{}");
        nodeDef.setSecrets("[]");
        nodeDef = nodeDefRepo.save(nodeDef);

        TemplateNode deadEnd = new TemplateNode();
        deadEnd.setGraphTemplateId(template.getId());
        deadEnd.setNodeDefinitionId(nodeDef.getId());
        deadEnd.setLabel("dead_end");
        deadEnd.setConfigOverrides("{}");
        deadEnd.setEntrypoint(true);
        deadEnd = templateNodeRepo.save(deadEnd);
        // Deliberately no createEdge(...) call and no terminal_decisions in config_overrides —
        // this node has neither, which is exactly the shape under test.

        WorkflowRun run = new WorkflowRun();
        run.setGraphTemplateId(template.getId());
        run.setInputs("{}");
        run = runRepo.save(run);

        JsonNode snapshotJson = objectMapper.readTree(snapshotBuilder.buildSnapshotForRun(run));
        JsonNode node = findSnapshotNode(snapshotJson, deadEnd.getId());

        assertThat(node.get("decision_options")).isEmpty();
    }

    @Test
    void buildSnapshotOmitsIterationCapForReviewNode() throws Exception {
        // Regression guard: iteration_cap was removed from NodeDefinition (and its
        // epoch-tracking counterpart from NodeExecution) in favor of self-detected
        // review-conflict escalation. "spec_review" is a self-iterating review-loop
        // node — the snapshot must never emit an iteration_cap field for it again.
        var baseTemplate = templateRepo
                .findFirstByGraphIdOrderByVersionDesc(GraphIds.FEATURE_DEVELOPMENT)
                .orElseThrow();
        var nodes = templateNodeRepo.findByGraphTemplateId(baseTemplate.getId());
        var specReview = nodes.stream()
                .filter(n -> "spec_review".equals(n.getLabel()))
                .findFirst()
                .orElseThrow();

        WorkflowRun run = new WorkflowRun();
        run.setGraphTemplateId(baseTemplate.getId());
        run.setInputs("{\"feature_request\":\"test\"}");
        run = runRepo.save(run);

        JsonNode snapshotJson = objectMapper.readTree(snapshotBuilder.buildSnapshotForRun(run));
        JsonNode node = findSnapshotNode(snapshotJson, specReview.getId());

        assertThat(node.has("iteration_cap")).isFalse();
    }

    private JsonNode findSnapshotNode(JsonNode snapshotJson, UUID templateNodeId) {
        for (JsonNode n : snapshotJson.get("nodes")) {
            if (n.get("template_node_id").asText().equals(templateNodeId.toString())) {
                return n;
            }
        }
        throw new AssertionError("No snapshot node found for template_node_id=" + templateNodeId);
    }
}
