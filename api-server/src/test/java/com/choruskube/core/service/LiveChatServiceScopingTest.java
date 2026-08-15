package com.choruskube.core.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.choruskube.core.exception.NotFoundException;
import com.choruskube.core.executor.WorkloadExecutor;
import com.choruskube.core.model.NodeExecution;
import com.choruskube.core.model.WorkflowRun;
import com.choruskube.core.model.enums.NodeExecutionStatus;
import com.choruskube.core.repository.LiveChatMessageRepository;
import com.choruskube.core.repository.LiveChatSessionRepository;
import com.choruskube.core.repository.NodeExecutionRepository;
import com.choruskube.core.repository.WorkflowRunRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

/**
 * Verifies that live-chat endpoints reject a nodeExecId belonging to a different run before any
 * pod is launched or any execution row is mutated.
 */
@ExtendWith(MockitoExtension.class)
class LiveChatServiceScopingTest {

    @Mock
    private LiveChatSessionRepository sessionRepo;

    @Mock
    private LiveChatMessageRepository messageRepo;

    @Mock
    private NodeExecutionRepository execRepo;

    @Mock
    private WorkflowRunRepository runRepo;

    @Mock
    private RunEventPublisher eventPublisher;

    @Mock
    private GraphSnapshotBuilder snapshotBuilder;

    @Mock
    private WorkloadExecutor executor;

    @Mock
    private StoragePrefixResolver storagePrefixResolver;

    @Mock
    private ApplicationEventPublisher applicationEventPublisher;

    private LiveChatService service;

    private final UUID runId = UUID.randomUUID();
    private final UUID foreignExecId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new LiveChatService(
                sessionRepo,
                messageRepo,
                execRepo,
                runRepo,
                eventPublisher,
                snapshotBuilder,
                new ObjectMapper(),
                executor,
                "agent-image:test",
                "http://api-server:8080",
                new AuthorizationService(new AlwaysAllowAuthorizationStrategy(), false),
                storagePrefixResolver,
                applicationEventPublisher);
    }

    /** A node execution that belongs to some OTHER run. */
    private NodeExecution foreignExec(NodeExecutionStatus status) {
        NodeExecution exec = new NodeExecution();
        exec.setId(foreignExecId);
        exec.setWorkflowRunId(UUID.randomUUID());
        exec.setStatus(status);
        return exec;
    }

    @Test
    void startLiveChat_execBelongsToDifferentRun_throwsNotFoundAndLaunchesNothing() {
        when(execRepo.findById(foreignExecId)).thenReturn(Optional.of(foreignExec(NodeExecutionStatus.awaiting_human)));
        WorkflowRun run = new WorkflowRun();
        run.setId(runId);
        lenient().when(runRepo.findById(runId)).thenReturn(Optional.of(run));

        assertThrows(NotFoundException.class, () -> service.startLiveChat(runId, foreignExecId));

        verifyNoInteractions(executor);
        verify(execRepo, never()).save(any());
    }
}
