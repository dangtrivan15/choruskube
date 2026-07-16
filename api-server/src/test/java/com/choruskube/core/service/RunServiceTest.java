package com.choruskube.core.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.choruskube.core.config.SingleTenant;
import com.choruskube.core.dto.RunResponse;
import com.choruskube.core.dto.RunTaskSummary;
import com.choruskube.core.dto.SignalRequest;
import com.choruskube.core.model.GitRepo;
import com.choruskube.core.model.GraphTemplate;
import com.choruskube.core.model.NodeExecution;
import com.choruskube.core.model.RepoGroup;
import com.choruskube.core.model.Task;
import com.choruskube.core.model.WorkflowRun;
import com.choruskube.core.model.enums.NodeExecutionStatus;
import com.choruskube.core.model.enums.WorkItemStatus;
import com.choruskube.core.model.enums.WorkflowRunStatus;
import com.choruskube.core.repository.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowStub;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

/**
 * Unit tests for RunService covering attachment-related functionality:
 * - signalHumanDecision includes attachmentRefs in signal payload
 * - startRun persists non-null inputAttachmentRefs
 * - startRun without inputAttachmentRefs succeeds (field defaults to "{}")
 * - buildWorkflowParams includes RunInputArtifactRefs when non-empty
 * - buildWorkflowParams omits key when input_artifact_refs is "{}"
 */
@ExtendWith(MockitoExtension.class)
class RunServiceTest {

    @Mock
    private WorkflowRunRepository runRepo;

    @Mock
    private NodeExecutionRepository execRepo;

    @Mock
    private TemplateEdgeRepository edgeRepo;

    @Mock
    private GraphSnapshotBuilder snapshotBuilder;

    @Mock
    private WorkflowClient workflowClient;

    @Mock
    private GraphTemplateRepository graphTemplateRepo;

    @Mock
    private TemplateNodeRepository templateNodeRepo;

    @Mock
    private GraphValidationService validationService;

    @Mock
    private ExecutionLogRepository executionLogRepo;

    @Mock
    private RunEventPublisher eventPublisher;

    @Mock
    private GitRepoRepository gitRepoRepo;

    @Mock
    private StoragePrefixResolver storagePrefixResolver;

    @Mock
    private WorkflowStub workflowStub;

    @Mock
    private TaskRepository taskRepo;

    @Mock
    private SoftwareProjectRepository softwareProjectRepo;

    private RunService service;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final UUID runId = UUID.randomUUID();
    private final UUID nodeExecId = UUID.randomUUID();
    private final UUID templateNodeId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        // storagePrefixResolver default: return system slug (matches single-tenant behaviour).
        // lenient() prevents UnnecessaryStubbingException for tests that don't invoke buildWorkflowParams.
        lenient().when(storagePrefixResolver.storagePrefixForRun(any())).thenReturn(SingleTenant.SLUG);

        service = new RunService(
                runRepo,
                execRepo,
                edgeRepo,
                snapshotBuilder,
                workflowClient,
                graphTemplateRepo,
                templateNodeRepo,
                validationService,
                executionLogRepo,
                objectMapper,
                eventPublisher,
                gitRepoRepo,
                null,
                new AuthorizationService(new AlwaysAllowAuthorizationStrategy(), false),
                Optional.empty(),
                null,
                null,
                storagePrefixResolver,
                null,
                softwareProjectRepo,
                null,
                null,
                null,
                taskRepo,
                mock(ArtifactResolutionService.class),
                mock(ApplicationEventPublisher.class),
                new com.choruskube.core.scope.NoOpScopeProvider());
    }

    // -----------------------------------------------------------------------
    // signalHumanDecision — attachmentRefs in payload
    // -----------------------------------------------------------------------

    private void stubRunWithEdges(String... conditions) {
        WorkflowRun run = new WorkflowRun();
        run.setId(runId);
        run.setStatus(WorkflowRunStatus.running);
        run.setExternalRunId("test-wf-id");
        when(runRepo.findById(runId)).thenReturn(Optional.of(run));

        StringBuilder edges = new StringBuilder();
        for (int i = 0; i < conditions.length; i++) {
            if (i > 0) edges.append(",");
            edges.append("""
                {"source_node_id":"%s","target_node_id":"%s","condition":"%s"}""".formatted(templateNodeId, UUID.randomUUID(), conditions[i]));
        }
        String snapshot = """
                {"nodes":[{"template_node_id":"%s","label":"gate","executor_type":"human","timeout_seconds":86400}],
                 "edges":[%s]}""".formatted(templateNodeId, edges);
        when(snapshotBuilder.buildSnapshotForRun(run)).thenReturn(snapshot);
        when(workflowClient.newUntypedWorkflowStub("test-wf-id")).thenReturn(workflowStub);
    }

    private NodeExecution stubExec() {
        NodeExecution exec = new NodeExecution();
        exec.setId(nodeExecId);
        exec.setWorkflowRunId(runId);
        exec.setTemplateNodeId(templateNodeId);
        exec.setStatus(NodeExecutionStatus.awaiting_human);
        exec.setGraphVersion(1);
        when(execRepo.findById(nodeExecId)).thenReturn(Optional.of(exec));
        return exec;
    }

    private JsonNode captureSignalPayload() {
        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(workflowStub).signal(eq("human-decision-" + nodeExecId), captor.capture());
        return objectMapper.valueToTree(captor.getValue());
    }

    @Test
    void signalHumanDecision_withAttachmentRefs_includesRefsInPayload() {
        stubExec();
        stubRunWithEdges("approved", "rejected");

        String refs = "{\"doc.pdf\":\"acme/runs/123/gate-attachments/456/doc.pdf\"}";
        service.signalHumanDecision(runId, nodeExecId, new SignalRequest("approved", null, refs));

        JsonNode payload = captureSignalPayload();
        assertThat(payload.get("attachmentRefs").asText()).isEqualTo(refs);
        assertThat(payload.get("decision").asText()).isEqualTo("approved");
        assertThat(payload.get("nodeExecutionId").asText()).isEqualTo(nodeExecId.toString());
    }

    @Test
    void signalHumanDecision_nullAttachmentRefs_usesEmptyJson() {
        stubExec();
        stubRunWithEdges("approved");

        service.signalHumanDecision(runId, nodeExecId, new SignalRequest("approved", null, null));

        JsonNode payload = captureSignalPayload();
        assertThat(payload.get("attachmentRefs").asText()).isEqualTo("{}");
    }

    @Test
    void signalHumanDecision_withFeedbackAndAttachmentRefs_bothPresent() {
        stubExec();
        stubRunWithEdges("approved");

        service.signalHumanDecision(
                runId,
                nodeExecId,
                new SignalRequest("approved", "Looks good", "{\"file.txt\":\"acme/gate/file.txt\"}"));

        JsonNode payload = captureSignalPayload();
        assertThat(payload.get("feedback").asText()).contains("Looks good");
        assertThat(payload.get("attachmentRefs").asText()).isEqualTo("{\"file.txt\":\"acme/gate/file.txt\"}");
    }

    // -----------------------------------------------------------------------
    // buildWorkflowParams — RunInputArtifactRefs
    // -----------------------------------------------------------------------

    @Test
    void buildWorkflowParams_withNonEmptyArtifactRefs_includesKey() {
        WorkflowRun run = new WorkflowRun();
        run.setId(UUID.randomUUID());
        run.setGraphVersion(1);
        run.setInputArtifactRefs("{\"file.pdf\":\"org/staging/uuid/file.pdf\"}");

        Map<String, Object> params = service.buildWorkflowParams(run);

        assertThat(params).containsKey("RunInputArtifactRefs");
        assertThat(params.get("RunInputArtifactRefs")).isEqualTo("{\"file.pdf\":\"org/staging/uuid/file.pdf\"}");
    }

    @Test
    void buildWorkflowParams_withEmptyJsonArtifactRefs_omitsKey() {
        WorkflowRun run = new WorkflowRun();
        run.setId(UUID.randomUUID());
        run.setGraphVersion(1);
        run.setInputArtifactRefs("{}");

        Map<String, Object> params = service.buildWorkflowParams(run);

        assertThat(params).doesNotContainKey("RunInputArtifactRefs");
    }

    @Test
    void buildWorkflowParams_withNullArtifactRefs_omitsKey() {
        WorkflowRun run = new WorkflowRun();
        run.setId(UUID.randomUUID());
        run.setGraphVersion(1);
        run.setInputArtifactRefs(null);

        Map<String, Object> params = service.buildWorkflowParams(run);

        assertThat(params).doesNotContainKey("RunInputArtifactRefs");
    }

    @Test
    void buildWorkflowParams_includesOrgSlugFromResolver() {
        // storagePrefixForRun returns the org slug via the seam; system slug in OSS mode.
        when(storagePrefixResolver.storagePrefixForRun(any())).thenReturn("acme");

        WorkflowRun run = new WorkflowRun();
        run.setId(UUID.randomUUID());
        run.setGraphVersion(1);
        run.setInputArtifactRefs("{}");

        Map<String, Object> params = service.buildWorkflowParams(run);

        assertThat(params).containsEntry("OrgSlug", "acme");
        assertThat(params).doesNotContainKey("RunInputArtifactRefs");
    }

    // -----------------------------------------------------------------------
    // WorkflowRun entity: inputArtifactRefs field defaults
    // -----------------------------------------------------------------------

    @Test
    void workflowRun_defaultInputArtifactRefs_isEmptyJson() {
        WorkflowRun run = new WorkflowRun();
        assertThat(run.getInputArtifactRefs()).isEqualTo("{}");
    }

    @Test
    void workflowRun_setInputArtifactRefs_roundTrips() {
        WorkflowRun run = new WorkflowRun();
        String refs = "{\"file.txt\":\"org/staging/abc/file.txt\"}";
        run.setInputArtifactRefs(refs);
        assertThat(run.getInputArtifactRefs()).isEqualTo(refs);
    }

    // -----------------------------------------------------------------------
    // renameRun — 30-character trim
    // -----------------------------------------------------------------------

    @Test
    void renameRun_nameExceeds30Chars_trimsAndAppendsEllipsis() {
        String longName = "A".repeat(40);
        WorkflowRun run = new WorkflowRun();
        run.setId(runId);
        when(runRepo.findById(runId)).thenReturn(Optional.of(run));

        service.renameRun(runId, longName);

        String expected = "A".repeat(29) + "…";
        verify(runRepo).save(argThat(saved -> saved.getName().equals(expected)));
    }

    @Test
    void renameRun_nameExactly30Chars_unchanged() {
        String name30 = "B".repeat(30);
        WorkflowRun run = new WorkflowRun();
        run.setId(runId);
        when(runRepo.findById(runId)).thenReturn(Optional.of(run));

        service.renameRun(runId, name30);

        verify(runRepo).save(argThat(saved -> saved.getName().equals(name30)));
    }

    @Test
    void renameRun_nameShorterThan30Chars_unchanged() {
        String shortName = "Short name";
        WorkflowRun run = new WorkflowRun();
        run.setId(runId);
        when(runRepo.findById(runId)).thenReturn(Optional.of(run));

        service.renameRun(runId, shortName);

        verify(runRepo).save(argThat(saved -> saved.getName().equals(shortName)));
    }

    // -----------------------------------------------------------------------
    // buildTaskSummary — via getRun(). Reads run.getTaskId() directly (Decision 1) — no
    // reverse "find by run id" lookup, only a forward lookup of the Task itself by id.
    // -----------------------------------------------------------------------

    private WorkflowRun stubRunForGetRun() {
        WorkflowRun run = new WorkflowRun();
        run.setId(runId);
        run.setStatus(WorkflowRunStatus.running);
        run.setGraphVersion(1);
        when(runRepo.findById(runId)).thenReturn(Optional.of(run));
        when(execRepo.findByWorkflowRunId(runId)).thenReturn(List.of());
        when(graphTemplateRepo.findById(any())).thenReturn(Optional.empty());
        try {
            when(snapshotBuilder.buildSnapshotForRun(run)).thenReturn("{\"nodes\":[],\"edges\":[]}");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return run;
    }

    @Test
    void getRun_noTaskLinked_taskIsNull() {
        stubRunForGetRun();

        RunResponse response = service.getRun(runId);

        assertThat(response.task()).isNull();
    }

    @Test
    void getRun_taskFoundWithProject_returnsFullSummary() {
        WorkflowRun run = stubRunForGetRun();

        UUID taskId = UUID.randomUUID();
        UUID projectId = UUID.randomUUID();
        run.setTaskId(taskId);

        Task task = new Task();
        task.setId(taskId);
        task.setTitle("Dark mode");
        task.setStatus(WorkItemStatus.in_progress);
        task.setSoftwareProjectId(projectId);

        GitRepo gitRepo = new GitRepo();
        gitRepo.setId(projectId);
        gitRepo.setName("my-repo");

        when(taskRepo.findById(taskId)).thenReturn(Optional.of(task));
        when(softwareProjectRepo.findById(projectId)).thenReturn(Optional.of(gitRepo));

        RunResponse response = service.getRun(runId);

        assertThat(response.task()).isNotNull();
        RunTaskSummary summary = response.task();
        assertThat(summary.id()).isEqualTo(taskId);
        assertThat(summary.title()).isEqualTo("Dark mode");
        assertThat(summary.status()).isEqualTo("in_progress");
        assertThat(summary.softwareProject()).isNotNull();
        assertThat(summary.softwareProject().id()).isEqualTo(projectId);
        assertThat(summary.softwareProject().type()).isEqualTo("git_repo");
        assertThat(summary.softwareProject().name()).isEqualTo("my-repo");
    }

    @Test
    void getRun_taskFoundWithRepoGroup_returnsRepoGroupType() {
        WorkflowRun run = stubRunForGetRun();

        UUID taskId = UUID.randomUUID();
        UUID projectId = UUID.randomUUID();
        run.setTaskId(taskId);

        Task task = new Task();
        task.setId(taskId);
        task.setTitle("Refactor services");
        task.setStatus(WorkItemStatus.backlog);
        task.setSoftwareProjectId(projectId);

        RepoGroup repoGroup = new RepoGroup();
        repoGroup.setId(projectId);
        repoGroup.setName("services-group");

        when(taskRepo.findById(taskId)).thenReturn(Optional.of(task));
        when(softwareProjectRepo.findById(projectId)).thenReturn(Optional.of(repoGroup));

        RunResponse response = service.getRun(runId);

        assertThat(response.task()).isNotNull();
        assertThat(response.task().softwareProject().type()).isEqualTo("repo_group");
    }

    @Test
    void getRun_taskFoundButProjectDeleted_softwareProjectIsNull() {
        WorkflowRun run = stubRunForGetRun();

        UUID taskId = UUID.randomUUID();
        UUID projectId = UUID.randomUUID();
        run.setTaskId(taskId);

        Task task = new Task();
        task.setId(taskId);
        task.setTitle("Orphaned task");
        task.setStatus(WorkItemStatus.backlog);
        task.setSoftwareProjectId(projectId);

        when(taskRepo.findById(taskId)).thenReturn(Optional.of(task));
        when(softwareProjectRepo.findById(projectId)).thenReturn(Optional.empty());

        RunResponse response = service.getRun(runId);

        assertThat(response.task()).isNotNull();
        assertThat(response.task().id()).isEqualTo(taskId);
        assertThat(response.task().softwareProject()).isNull();
    }

    // -----------------------------------------------------------------------
    // trimName — shared helper used by createRun + renameRun
    // -----------------------------------------------------------------------

    @Test
    void trimName_nullInput_returnsNull() {
        assertThat(RunService.trimName(null)).isNull();
    }

    @Test
    void trimName_shorterThanLimit_unchanged() {
        assertThat(RunService.trimName("hello")).isEqualTo("hello");
    }

    @Test
    void trimName_exactlyAtLimit_unchanged() {
        String exactly30 = "C".repeat(RunService.RUN_NAME_MAX_LENGTH);
        assertThat(RunService.trimName(exactly30)).isEqualTo(exactly30);
    }

    @Test
    void trimName_longerThanLimit_endsWithEllipsisAndStaysAtLimit() {
        String long40 = "D".repeat(40);
        String trimmed = RunService.trimName(long40);
        assertThat(trimmed).isEqualTo("D".repeat(29) + RunService.RUN_NAME_TRUNCATION_MARKER);
        assertThat(trimmed).hasSize(RunService.RUN_NAME_MAX_LENGTH);
    }

    // -----------------------------------------------------------------------
    // promptText extraction — via getRun()
    // -----------------------------------------------------------------------

    private GraphTemplate stubTemplateWithPromptKey(String promptInputKey) {
        GraphTemplate template = new GraphTemplate();
        template.setId(UUID.randomUUID());
        template.setName("Feature Development");
        template.setPromptInputKey(promptInputKey);
        when(graphTemplateRepo.findById(any())).thenReturn(Optional.of(template));
        return template;
    }

    @Test
    void getRun_promptKeyPresentInInputs_returnsPromptText() {
        WorkflowRun run = stubRunForGetRun();
        run.setInputs("{\"feature_request\":\"Add dark mode\",\"software_project_id\":\"abc\"}");
        stubTemplateWithPromptKey("feature_request");

        RunResponse response = service.getRun(runId);

        assertThat(response.promptText()).isEqualTo("Add dark mode");
    }

    @Test
    void getRun_promptInputKeyNullOnTemplate_promptTextIsNull() {
        WorkflowRun run = stubRunForGetRun();
        run.setInputs("{\"feature_request\":\"Add dark mode\"}");
        stubTemplateWithPromptKey(null);

        RunResponse response = service.getRun(runId);

        assertThat(response.promptText()).isNull();
    }

    @Test
    void getRun_promptKeyMissingFromInputs_promptTextIsNull() {
        WorkflowRun run = stubRunForGetRun();
        run.setInputs("{\"software_project_id\":\"abc\"}");
        stubTemplateWithPromptKey("feature_request");

        RunResponse response = service.getRun(runId);

        assertThat(response.promptText()).isNull();
    }

    @Test
    void getRun_inputsBlank_promptTextIsNull() {
        WorkflowRun run = stubRunForGetRun();
        run.setInputs("");
        stubTemplateWithPromptKey("feature_request");

        RunResponse response = service.getRun(runId);

        assertThat(response.promptText()).isNull();
    }

    @Test
    void getRun_inputsMalformedJson_promptTextIsNull() {
        WorkflowRun run = stubRunForGetRun();
        run.setInputs("not-json");
        stubTemplateWithPromptKey("feature_request");

        RunResponse response = service.getRun(runId);

        assertThat(response.promptText()).isNull();
    }
}
