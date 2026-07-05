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
}
