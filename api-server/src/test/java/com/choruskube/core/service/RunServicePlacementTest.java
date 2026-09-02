package com.choruskube.core.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;

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
 * The queue half of a placement travels to the Go side as a workflow parameter; the namespace
 * half selects the client and is persisted on the run. This covers the parameter half.
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

    private RunService newService() {
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
                null, // placements
                null, // workflowClients
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

    /**
     * Always emitted, never conditionally. The value equals the workflow's own queue in a
     * single-namespace deployment, so the key being present costs nothing and removes the
     * blank-means-default convention that used to hide a misconfigured placement.
     */
    @Test
    void buildWorkflowParams_carriesThePlacementsQueue() {
        RunService service = newService();

        assertThat(service.buildWorkflowParams(run, new RunPlacement("choruskube", "choruskube")))
                .containsEntry("WorkerTaskQueue", "choruskube");
    }

    @Test
    void buildWorkflowParams_carriesACustomerQueue() {
        RunService service = newService();

        assertThat(service.buildWorkflowParams(run, new RunPlacement("tenant-ns", "fleet-acme")))
                .containsEntry("WorkerTaskQueue", "fleet-acme");
    }

    /**
     * The namespace is not a workflow input — it selects the client and is stored on the run.
     * Pinning the exact key set (not just the absence of one guessed key name) is what actually
     * catches a namespace leaking in under some other key.
     */
    @Test
    void buildWorkflowParams_doesNotLeakTheNamespaceIntoWorkflowInput() {
        RunService service = newService();

        assertThat(service.buildWorkflowParams(run, new RunPlacement("tenant-ns", "fleet-acme")))
                .containsOnlyKeys("RunID", "GraphVersion", "OrgSlug", "WorkerTaskQueue");
    }
}
