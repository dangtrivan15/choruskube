package com.choruskube.core.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.choruskube.core.dto.CreateRunPullRequestRequest;
import com.choruskube.core.dto.TaskStatusUpdateRequest;
import com.choruskube.core.exception.NotFoundException;
import com.choruskube.core.model.NodeExecution;
import com.choruskube.core.model.enums.WorkItemStatus;
import com.choruskube.core.repository.GitRepoRepository;
import com.choruskube.core.repository.NodeExecutionRepository;
import com.choruskube.core.repository.RunPullRequestRepository;
import com.choruskube.core.repository.WorkflowRunRepository;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

/**
 * Proves the run-scoping guard on the four {@code /internal} agent endpoints that authenticate on
 * {@code nodeExecId} but previously acted on a caller-supplied {@code runId} with no cross-check
 * ({@code InternalAuthFilter} only verifies the JOB_SECRET against {@code nodeExecId}'s own stored
 * hash — it never compares the path's {@code runId} segment to that execution's actual {@code
 * workflow_run_id}, see {@link com.choruskube.core.util.NodeExecutionUtil}). Each test stubs an
 * execution belonging to a run OTHER than the one requested and asserts both that {@link
 * NotFoundException} is thrown and that the guarded effect never ran, proving the guard fired
 * first rather than merely that something eventually threw.
 */
@ExtendWith(MockitoExtension.class)
class InternalRunScopingTest {

    @Mock
    private WorkflowRunRepository runRepo;

    @Mock
    private NodeExecutionRepository execRepo;

    @Mock
    private GitRepoRepository gitRepoRepo;

    @Mock
    private RunPullRequestRepository prRepo;

    @Mock
    private RunEventPublisher eventPublisher;

    @Mock
    private TaskService taskService;

    @Mock
    private RoadmapGraphService roadmapGraphService;

    private final UUID runId = UUID.randomUUID();
    private final UUID foreignExecId = UUID.randomUUID();

    /** A node execution that belongs to some OTHER run than {@code runId}. */
    private void stubForeignExec() {
        NodeExecution exec = new NodeExecution();
        exec.setId(foreignExecId);
        exec.setWorkflowRunId(UUID.randomUUID());
        when(execRepo.findById(foreignExecId)).thenReturn(Optional.of(exec));
    }

    @Test
    void createPullRequest_execBelongsToDifferentRun_throwsNotFoundAndSavesNothing() {
        stubForeignExec();
        RunPullRequestService service = new RunPullRequestService(
                prRepo, runRepo, gitRepoRepo, execRepo, eventPublisher, mock(ApplicationEventPublisher.class));

        var req = new CreateRunPullRequestRequest(
                UUID.randomUUID(), "https://github.com/org/repo/pull/1", 1, "title", "repo");

        assertThatThrownBy(() -> service.createPullRequest(runId, foreignExecId, req))
                .isInstanceOf(NotFoundException.class)
                .hasMessage("Node execution not found: " + foreignExecId);

        verify(prRepo, never()).save(any());
    }

    @Test
    void updateTaskStatus_execBelongsToDifferentRun_throwsNotFoundAndDoesNotTouchTask() {
        stubForeignExec();
        InternalRunService service = newInternalRunService();

        var req = new TaskStatusUpdateRequest(WorkItemStatus.done, null, null);

        assertThatThrownBy(() -> service.updateTaskStatus(runId, foreignExecId, UUID.randomUUID(), req))
                .isInstanceOf(NotFoundException.class)
                .hasMessage("Node execution not found: " + foreignExecId);

        verifyNoInteractions(taskService);
    }

    @Test
    void getGraph_execBelongsToDifferentRun_throwsNotFound() {
        stubForeignExec();
        InternalRunService service = newInternalRunService();

        assertThatThrownBy(() -> service.getGraph(runId, foreignExecId, UUID.randomUUID()))
                .isInstanceOf(NotFoundException.class)
                .hasMessage("Node execution not found: " + foreignExecId);

        verifyNoInteractions(roadmapGraphService);
    }

    @Test
    void getGraphForTriggeringTask_execBelongsToDifferentRun_throwsNotFound() {
        stubForeignExec();
        InternalRunService service = newInternalRunService();

        assertThatThrownBy(() -> service.getGraphForTriggeringTask(runId, foreignExecId))
                .isInstanceOf(NotFoundException.class)
                .hasMessage("Node execution not found: " + foreignExecId);

        verifyNoInteractions(roadmapGraphService);
    }

    private InternalRunService newInternalRunService() {
        return new InternalRunService(
                runRepo,
                execRepo,
                null, // logRepo
                null, // snapshotBuilder
                null, // eventPublisher
                null, // objectMapper
                null, // epicService
                null, // storyService
                taskService,
                null, // runService
                Optional.empty(), // quotaService
                null, // usageSink
                gitRepoRepo,
                null, // runPullRequestService
                null, // graphTemplateRepo
                null, // softwareProjectRepo
                null, // templateNodeRepo
                null, // nodeDefinitionRepo
                null, // storyRepo
                null, // taskRepo
                null, // epicRepo
                roadmapGraphService,
                null, // decisionOptionsResolver
                null, // dependencyRepo
                null, // artifactService
                null, // workItemDependencyService
                null); // milestoneService
    }
}
