package com.choruskube.core.service;

import static org.assertj.core.api.Assertions.*;

import com.choruskube.core.BaseTest;
import com.choruskube.core.model.GitRepo;
import com.choruskube.core.model.GraphTemplate;
import com.choruskube.core.model.RepoGroup;
import com.choruskube.core.model.WorkflowRun;
import com.choruskube.core.repository.GitRepoRepository;
import com.choruskube.core.repository.GraphTemplateRepository;
import com.choruskube.core.repository.WorkflowRunRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.temporal.client.WorkflowClient;
import io.temporal.serviceclient.WorkflowServiceStubs;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

class GraphSnapshotBuilderSoftwareProjectTest extends BaseTest {

    @MockitoBean
    private WorkflowServiceStubs workflowServiceStubs;

    @MockitoBean
    private WorkflowClient workflowClient;

    @Autowired
    private GraphSnapshotBuilder snapshotBuilder;

    @Autowired
    private GitRepoRepository gitRepoRepo;

    @Autowired
    private GraphTemplateRepository templateRepo;

    @Autowired
    private WorkflowRunRepository runRepo;

    @Autowired
    private RepoGroupService repoGroupService;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void snapshot_for_git_repo_software_project_yields_one_repo_and_its_image() throws Exception {
        GitRepo repo = createGitRepoWithName("r", "https://github.com/test/r", "registry/agent:v1", true);

        GraphTemplate template = createTemplateWithSoftwareProjectSchema();

        WorkflowRun run = new WorkflowRun();
        run.setGraphTemplateId(template.getId());
        run.setInputs("{\"software_project_id\":\"" + repo.getId() + "\",\"feature_request\":\"test\"}");
        run = runRepo.save(run);

        String snapshot = snapshotBuilder.buildSnapshotForRun(run);
        JsonNode snapshotJson = objectMapper.readTree(snapshot);

        assertThat(snapshotJson.has("repos")).isTrue();
        JsonNode repos = snapshotJson.get("repos");
        assertThat(repos.isArray()).isTrue();
        assertThat(repos.size()).isEqualTo(1);
        assertThat(repos.get(0).get("name").asText()).isEqualTo("r");

        assertThat(snapshotJson.get("inputs").get("agent_image").asText()).isEqualTo("registry/agent:v1");
        assertThat(snapshotJson.get("enable_docker").asBoolean()).isTrue();
    }

    @Test
    void snapshot_for_repo_group_software_project_uses_groups_image_and_anyDocker() throws Exception {
        GitRepo r1 = createGitRepoWithName("r1", "https://github.com/test/r1", null, false);
        GitRepo r2 = createGitRepoWithName("r2", "https://github.com/test/r2", null, true);

        RepoGroup group =
                repoGroupService.create("g", "registry/group-agent:v1", null, List.of(r1.getId(), r2.getId()));

        GraphTemplate template = createTemplateWithSoftwareProjectSchema();

        WorkflowRun run = new WorkflowRun();
        run.setGraphTemplateId(template.getId());
        run.setInputs("{\"software_project_id\":\"" + group.getId() + "\",\"feature_request\":\"test\"}");
        run = runRepo.save(run);

        String snapshot = snapshotBuilder.buildSnapshotForRun(run);
        JsonNode snapshotJson = objectMapper.readTree(snapshot);

        assertThat(snapshotJson.has("repos")).isTrue();
        JsonNode repos = snapshotJson.get("repos");
        assertThat(repos.isArray()).isTrue();
        assertThat(repos.size()).isEqualTo(2);
        assertThat(repos.get(0).get("name").asText()).isEqualTo("r1");
        assertThat(repos.get(1).get("name").asText()).isEqualTo("r2");

        assertThat(snapshotJson.get("inputs").get("agent_image").asText()).isEqualTo("registry/group-agent:v1");
        assertThat(snapshotJson.get("enable_docker").asBoolean()).isTrue();
    }

    private GraphTemplate createTemplateWithSoftwareProjectSchema() {
        String uniqueSuffix = UUID.randomUUID().toString().substring(0, 8);
        GraphTemplate template = new GraphTemplate();
        template.setName("Software Project Test " + uniqueSuffix);
        template.setGraphId("software-project-test-" + uniqueSuffix);
        template.setVersion(1);
        template.setInputSchema(
                "[{\"name\":\"software_project_id\",\"label\":\"Software Project\",\"type\":\"software_project_id\",\"required\":true},{\"name\":\"feature_request\",\"label\":\"Feature\",\"type\":\"textarea\",\"required\":true}]");
        return templateRepo.save(template);
    }

    private GitRepo createGitRepoWithName(String name, String url, String agentImage, boolean enableDocker) {
        GitRepo gitRepo = new GitRepo();
        gitRepo.setName(name);
        gitRepo.setUrl(url);
        gitRepo.setTestCommand("./test.sh");
        gitRepo.setAgentImage(agentImage);
        gitRepo.setSecrets("[]");
        gitRepo.setEnableDocker(enableDocker);
        return gitRepoRepo.save(gitRepo);
    }
}
