package com.choruskube.core.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.choruskube.core.dto.GraphRuntimeSnapshotResponse;
import com.choruskube.core.model.WorkflowRun;
import com.choruskube.core.repository.WorkflowRunRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class InternalRunServiceGraphRuntimeTest {

    @Mock
    private WorkflowRunRepository runRepo;

    @Mock
    private GraphSnapshotBuilder snapshotBuilder;

    private ObjectMapper objectMapper = new ObjectMapper();
    private InternalRunService service;

    private static final UUID NODE_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID EDGE_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID TARGET_NODE_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID GIT_REPO_ID = UUID.fromString("44444444-4444-4444-4444-444444444444");

    private static final String SNAPSHOT_JSON = """
            {
              "nodes": [{
                "template_node_id": "11111111-1111-1111-1111-111111111111",
                "label": "Code Review",
                "executor_type": "ai",
                "image": "agent:latest",
                "prompt_template": "Review the code...",
                "input_spec": {},
                "output_spec": {},
                "timeout_seconds": 1800,
                "secrets": [{"name": "GITHUB_TOKEN"}],
                "skills": [],
                "is_entrypoint": true,
                "config_overrides": {"loop_group": "review"}
              }],
              "edges": [{
                "template_edge_id": "22222222-2222-2222-2222-222222222222",
                "source_node_id": "11111111-1111-1111-1111-111111111111",
                "target_node_id": "33333333-3333-3333-3333-333333333333",
                "condition": "approved"
              }],
              "enable_docker": true,
              "docker_config": {"registry_mirrors": ["mirror.local"]},
              "namespace": "agent-ns",
              "inputs": {"repo_url": "https://github.com/test/repo", "git_repo_id": "44444444-4444-4444-4444-444444444444"}
            }
            """;

    private static final String SNAPSHOT_WITH_REPOS_JSON = """
            {
              "nodes": [{
                "template_node_id": "11111111-1111-1111-1111-111111111111",
                "label": "Implement",
                "executor_type": "ai",
                "image": "agent:latest",
                "prompt_template": "Implement...",
                "input_spec": {},
                "output_spec": {},
                "timeout_seconds": 3600,
                "secrets": [],
                "skills": [],
                "is_entrypoint": true,
                "config_overrides": {}
              }],
              "edges": [],
              "inputs": {"feature_request": "test"},
              "repos": [
                {"id": "aaaa0000-0000-0000-0000-000000000001", "url": "https://github.com/org/backend-api", "name": "backend-api", "test_command": "./gradlew test", "agent_image": "agent:v2"},
                {"id": "aaaa0000-0000-0000-0000-000000000002", "url": "https://github.com/org/frontend-app", "name": "frontend-app"}
              ]
            }
            """;

    @BeforeEach
    void setUp() {
        service = new InternalRunService(
                runRepo,
                null,
                null,
                snapshotBuilder,
                null,
                objectMapper,
                null,
                null,
                Optional.empty(),
                null,
                null,
                null,
                null,
                null,
                null,
                null);
    }

    @Test
    void getGraphRuntimeSnapshot_callsSnapshotBuilder() throws Exception {
        UUID runId = UUID.randomUUID();
        WorkflowRun run = new WorkflowRun();
        when(runRepo.findById(runId)).thenReturn(Optional.of(run));
        when(snapshotBuilder.buildSnapshotForRun(run)).thenReturn(SNAPSHOT_JSON);

        service.getGraphRuntimeSnapshot(runId);

        verify(snapshotBuilder).buildSnapshotForRun(run);
    }

    @Test
    void getGraphRuntimeSnapshot_projectsNodeWorkflowFields() throws Exception {
        UUID runId = UUID.randomUUID();
        WorkflowRun run = new WorkflowRun();
        when(runRepo.findById(runId)).thenReturn(Optional.of(run));
        when(snapshotBuilder.buildSnapshotForRun(run)).thenReturn(SNAPSHOT_JSON);

        GraphRuntimeSnapshotResponse response = service.getGraphRuntimeSnapshot(runId);

        assertThat(response.nodes()).hasSize(1);
        GraphRuntimeSnapshotResponse.RuntimeNode node = response.nodes().get(0);
        assertThat(node.templateNodeId()).isEqualTo(NODE_ID);
        assertThat(node.label()).isEqualTo("Code Review");
        assertThat(node.executorType()).isEqualTo("ai");
        assertThat(node.promptTemplate()).isEqualTo("Review the code...");
        assertThat(node.timeoutSeconds()).isEqualTo(1800);
        assertThat(node.isEntrypoint()).isTrue();
        assertThat(node.configOverrides()).containsEntry("loop_group", "review");
    }

    @Test
    void getGraphRuntimeSnapshot_projectsEdgeFields() throws Exception {
        UUID runId = UUID.randomUUID();
        WorkflowRun run = new WorkflowRun();
        when(runRepo.findById(runId)).thenReturn(Optional.of(run));
        when(snapshotBuilder.buildSnapshotForRun(run)).thenReturn(SNAPSHOT_JSON);

        GraphRuntimeSnapshotResponse response = service.getGraphRuntimeSnapshot(runId);

        assertThat(response.edges()).hasSize(1);
        GraphRuntimeSnapshotResponse.RuntimeEdge edge = response.edges().get(0);
        assertThat(edge.templateEdgeId()).isEqualTo(EDGE_ID);
        assertThat(edge.sourceNodeId()).isEqualTo(NODE_ID);
        assertThat(edge.targetNodeId()).isEqualTo(TARGET_NODE_ID);
        assertThat(edge.condition()).isEqualTo("approved");
    }

    @Test
    void getGraphRuntimeSnapshot_includesInputs() throws Exception {
        UUID runId = UUID.randomUUID();
        WorkflowRun run = new WorkflowRun();
        when(runRepo.findById(runId)).thenReturn(Optional.of(run));
        when(snapshotBuilder.buildSnapshotForRun(run)).thenReturn(SNAPSHOT_JSON);

        GraphRuntimeSnapshotResponse response = service.getGraphRuntimeSnapshot(runId);

        assertThat(response.inputs()).containsEntry("repo_url", "https://github.com/test/repo");
        assertThat(response.inputs()).containsEntry("git_repo_id", GIT_REPO_ID.toString());
    }

    @Test
    void getGraphRuntimeSnapshot_doesNotExposeInfraFields() throws Exception {
        UUID runId = UUID.randomUUID();
        WorkflowRun run = new WorkflowRun();
        when(runRepo.findById(runId)).thenReturn(Optional.of(run));
        when(snapshotBuilder.buildSnapshotForRun(run)).thenReturn(SNAPSHOT_JSON);

        GraphRuntimeSnapshotResponse response = service.getGraphRuntimeSnapshot(runId);

        // RuntimeNode record does not have image, secrets, input_spec, output_spec, skills
        // Verifying by confirming the record type only has the workflow fields
        GraphRuntimeSnapshotResponse.RuntimeNode node = response.nodes().get(0);
        // These fields are simply not present on the record — the type system enforces this
        assertThat(node).isNotNull();
        assertThat(node.templateNodeId()).isNotNull();
        // namespace, docker_config, enable_docker are not on the response record either
        // (inputs map comes from the snapshot's "inputs" field, not from namespace/docker_config)
        assertThat(response.inputs()).doesNotContainKey("namespace");
        assertThat(response.inputs()).doesNotContainKey("enable_docker");
        assertThat(response.inputs()).doesNotContainKey("docker_config");
    }

    @Test
    void getGraphRuntimeSnapshot_projectsMultiRepoFields() throws Exception {
        UUID runId = UUID.randomUUID();
        WorkflowRun run = new WorkflowRun();
        when(runRepo.findById(runId)).thenReturn(Optional.of(run));
        when(snapshotBuilder.buildSnapshotForRun(run)).thenReturn(SNAPSHOT_WITH_REPOS_JSON);

        GraphRuntimeSnapshotResponse response = service.getGraphRuntimeSnapshot(runId);

        assertThat(response.repos()).hasSize(2);

        GraphRuntimeSnapshotResponse.RuntimeRepo repo1 = response.repos().get(0);
        assertThat(repo1.id()).isEqualTo("aaaa0000-0000-0000-0000-000000000001");
        assertThat(repo1.url()).isEqualTo("https://github.com/org/backend-api");
        assertThat(repo1.name()).isEqualTo("backend-api");
        assertThat(repo1.testCommand()).isEqualTo("./gradlew test");
        assertThat(repo1.agentImage()).isEqualTo("agent:v2");

        GraphRuntimeSnapshotResponse.RuntimeRepo repo2 = response.repos().get(1);
        assertThat(repo2.id()).isEqualTo("aaaa0000-0000-0000-0000-000000000002");
        assertThat(repo2.url()).isEqualTo("https://github.com/org/frontend-app");
        assertThat(repo2.name()).isEqualTo("frontend-app");
        assertThat(repo2.testCommand()).isNull();
        assertThat(repo2.agentImage()).isNull();
    }

    @Test
    void getGraphRuntimeSnapshot_withNoRepos_returnsEmptyReposList() throws Exception {
        UUID runId = UUID.randomUUID();
        WorkflowRun run = new WorkflowRun();
        when(runRepo.findById(runId)).thenReturn(Optional.of(run));
        when(snapshotBuilder.buildSnapshotForRun(run)).thenReturn(SNAPSHOT_JSON);

        GraphRuntimeSnapshotResponse response = service.getGraphRuntimeSnapshot(runId);

        // SNAPSHOT_JSON has no repos array → should default to empty list
        assertThat(response.repos()).isEmpty();
    }
}
