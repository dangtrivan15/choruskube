package com.choruskube.core.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.choruskube.core.config.SingleTenant;
import com.choruskube.core.model.WorkflowRun;
import com.choruskube.core.scope.NoOpScopeProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for the optional {@link RunPlacementResolver} seam in {@link RunService}:
 * - no resolver bound -> {@code buildWorkflowParams} omits {@code WorkerTaskQueue}
 * - resolver present -> its non-blank answer is carried under the {@code WorkerTaskQueue} key
 * - resolver's blank answer is treated the same as no resolver: omitted, not emitted empty
 */
@ExtendWith(MockitoExtension.class)
class RunServicePlacementTest {

    @Mock
    private StoragePrefixResolver storagePrefixResolver;

    private WorkflowRun run;

    @BeforeEach
    void setUp() {
        lenient().when(storagePrefixResolver.storagePrefixForRun(any())).thenReturn(SingleTenant.SLUG);

        run = new WorkflowRun();
        run.setId(UUID.randomUUID());
        run.setGraphVersion(1);
    }

    private RunService newService(Optional<RunPlacementResolver> placementResolver) {
        return new RunService(
                null, // runRepo
                null, // execRepo
                null, // edgeRepo
                null, // snapshotBuilder
                null, // workflowClient
                null, // graphTemplateRepo
                null, // templateNodeRepo
                null, // validationService
                null, // executionLogRepo
                new ObjectMapper(),
                null, // eventPublisher
                null, // gitRepoRepo
                null, // workloadService
                new AuthorizationService(new AlwaysAllowAuthorizationStrategy(), false),
                Optional.empty(), // quotaService
                placementResolver,
                null, // usageSink
                null, // auditSink
                storagePrefixResolver,
                null, // runPullRequestService
                null, // softwareProjectRepo
                null, // repoGroupMemberRepo
                null, // credentialPreflightChecker
                null, // uploadService
                null, // taskRepo
                null, // storyRepo
                null, // epicRepo
                null, // artifactResolutionService
                null, // applicationEventPublisher
                new NoOpScopeProvider(),
                new DecisionOptionsResolver(),
                null, // roadmapCandidateMaterializer
                null, // roadmapCandidatesArtifactResolver
                null, // nodeExecutionClaimService
                null); // escalationContextResolver
    }

    @Test
    void buildWorkflowParams_noResolver_omitsWorkerTaskQueue() {
        RunService service = newService(Optional.empty());
        assertThat(service.buildWorkflowParams(run)).doesNotContainKey("WorkerTaskQueue");
    }

    @Test
    void buildWorkflowParams_resolverPresent_usesItsQueue() {
        RunPlacementResolver resolver = mock(RunPlacementResolver.class);
        when(resolver.taskQueueFor(run.getId())).thenReturn("fleet-acme");

        RunService service = newService(Optional.of(resolver));

        assertThat(service.buildWorkflowParams(run)).containsEntry("WorkerTaskQueue", "fleet-acme");
    }

    @Test
    void buildWorkflowParams_resolverReturnsBlank_omitsWorkerTaskQueue() {
        RunPlacementResolver resolver = mock(RunPlacementResolver.class);
        when(resolver.taskQueueFor(run.getId())).thenReturn("  ");

        RunService service = newService(Optional.of(resolver));

        assertThat(service.buildWorkflowParams(run)).doesNotContainKey("WorkerTaskQueue");
    }
}
