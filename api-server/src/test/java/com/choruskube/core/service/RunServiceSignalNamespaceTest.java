package com.choruskube.core.service;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.choruskube.core.BaseTest;
import com.choruskube.core.config.WorkflowClientRegistry;
import com.choruskube.core.model.GraphTemplate;
import com.choruskube.core.model.WorkflowRun;
import com.choruskube.core.model.enums.WorkflowRunStatus;
import com.choruskube.core.repository.GraphTemplateRepository;
import com.choruskube.core.repository.WorkflowRunRepository;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowStub;
import io.temporal.serviceclient.WorkflowServiceStubs;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

/**
 * A signal built against the deployment's default namespace still reaches Temporal — it just
 * reports the workflow not found there, and cancelRun/pauseRun swallow exactly that. So the only
 * way to catch a wrong-namespace stub is to observe which namespace the client was requested for,
 * which is what these drive through the real RunService rather than reimplementing its logic.
 */
@Transactional
class RunServiceSignalNamespaceTest extends BaseTest {

    private static final String TENANT_NAMESPACE = "tenant-ns";

    @Autowired
    private RunService runService;

    @Autowired
    private WorkflowRunRepository runRepo;

    @Autowired
    private GraphTemplateRepository graphTemplateRepo;

    @MockitoBean
    private WorkflowServiceStubs workflowServiceStubs;

    @MockitoBean
    private WorkflowClient workflowClient;

    @MockitoBean
    private WorkflowClientRegistry workflowClientRegistry;

    private WorkflowClient tenantClient;
    private WorkflowStub stub;

    @BeforeEach
    void setUp() {
        tenantClient = mock(WorkflowClient.class);
        stub = mock(WorkflowStub.class);
        when(tenantClient.newUntypedWorkflowStub(anyString())).thenReturn(stub);
        when(workflowClientRegistry.clientFor(TENANT_NAMESPACE)).thenReturn(tenantClient);
    }

    private WorkflowRun persistRunningRun() {
        GraphTemplate template = new GraphTemplate();
        template.setName("Signal Namespace Template");
        template.setGraphId("signal-namespace-template-" + UUID.randomUUID());
        template.setVersion(1);
        template = graphTemplateRepo.save(template);

        WorkflowRun run = new WorkflowRun();
        run.setGraphTemplateId(template.getId());
        run.setStatus(WorkflowRunStatus.running);
        run.setExternalRunId("choruskube-run-" + UUID.randomUUID());
        run.setTemporalNamespace(TENANT_NAMESPACE);
        return runRepo.save(run);
    }

    @Test
    void cancelRun_signalsTheWorkflowInTheRunsRecordedNamespace() {
        WorkflowRun run = persistRunningRun();

        runService.cancelRun(run.getId());

        verify(workflowClientRegistry).clientFor(TENANT_NAMESPACE);
        verify(tenantClient).newUntypedWorkflowStub(run.getExternalRunId());
        verify(stub).signal("cancel");
    }

    @Test
    void pauseRun_signalsTheWorkflowInTheRunsRecordedNamespace() {
        WorkflowRun run = persistRunningRun();

        runService.pauseRun(run.getId());

        verify(workflowClientRegistry).clientFor(TENANT_NAMESPACE);
        verify(tenantClient).newUntypedWorkflowStub(run.getExternalRunId());
        verify(stub).signal("pause");
    }
}
