package com.choruskube.core.service;

import static org.assertj.core.api.Assertions.*;

import com.choruskube.core.BaseTest;
import com.choruskube.core.config.GraphIds;
import com.choruskube.core.model.GitRepo;
import com.choruskube.core.model.WorkflowRun;
import com.choruskube.core.repository.GitRepoRepository;
import com.choruskube.core.repository.GraphTemplateRepository;
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
        // "Final Approval" (feature-development) has two outgoing edges — approved, rereview —
        // and no terminal_decisions config: a plain edge-driven gate.
        var baseTemplate = templateRepo
                .findFirstByGraphIdOrderByVersionDesc(GraphIds.FEATURE_DEVELOPMENT)
                .orElseThrow();
        var nodes = templateNodeRepo.findByGraphTemplateId(baseTemplate.getId());
        var finalApproval = nodes.stream()
                .filter(n -> "final_approval".equals(n.getLabel()))
                .findFirst()
                .orElseThrow();

        WorkflowRun run = new WorkflowRun();
        run.setGraphTemplateId(baseTemplate.getId());
        run.setInputs("{\"feature_request\":\"test\"}");
        run = runRepo.save(run);

        JsonNode snapshotJson = objectMapper.readTree(snapshotBuilder.buildSnapshotForRun(run));
        JsonNode node = findSnapshotNode(snapshotJson, finalApproval.getId());

        List<String> decisionOptions = new ArrayList<>();
        node.get("decision_options").forEach(o -> decisionOptions.add(o.asText()));
        assertThat(decisionOptions).containsExactlyInAnyOrder("approved", "rereview");
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
        // "push_create_pr" (feature-development) is a terminal node: no outgoing edges and no
        // terminal_decisions config.
        var baseTemplate = templateRepo
                .findFirstByGraphIdOrderByVersionDesc(GraphIds.FEATURE_DEVELOPMENT)
                .orElseThrow();
        var nodes = templateNodeRepo.findByGraphTemplateId(baseTemplate.getId());
        var pushCreatePr = nodes.stream()
                .filter(n -> "push_create_pr".equals(n.getLabel()))
                .findFirst()
                .orElseThrow();

        WorkflowRun run = new WorkflowRun();
        run.setGraphTemplateId(baseTemplate.getId());
        run.setInputs("{\"feature_request\":\"test\"}");
        run = runRepo.save(run);

        JsonNode snapshotJson = objectMapper.readTree(snapshotBuilder.buildSnapshotForRun(run));
        JsonNode node = findSnapshotNode(snapshotJson, pushCreatePr.getId());

        assertThat(node.get("decision_options")).isEmpty();
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
