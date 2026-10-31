package com.choruskube.core.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.choruskube.core.BaseTest;
import com.choruskube.core.config.WorkflowClientRegistry;
import com.choruskube.core.model.DriftRecheckConfig.StampMode;
import com.choruskube.core.model.GateStamp;
import com.choruskube.core.model.GraphTemplate;
import com.choruskube.core.model.NodeDefinition;
import com.choruskube.core.model.NodeExecution;
import com.choruskube.core.model.TemplateNode;
import com.choruskube.core.model.WorkflowRun;
import com.choruskube.core.model.enums.ExecutorType;
import com.choruskube.core.model.enums.GateStampStatus;
import com.choruskube.core.model.enums.NodeExecutionStatus;
import com.choruskube.core.model.enums.WorkflowRunStatus;
import com.choruskube.core.repository.GateStampRepository;
import com.choruskube.core.repository.GraphTemplateRepository;
import com.choruskube.core.repository.NodeDefinitionRepository;
import com.choruskube.core.repository.NodeExecutionRepository;
import com.choruskube.core.repository.TemplateNodeRepository;
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

/** A cancel ends the run without the orchestrator's status update, so it must void the approvals itself. */
@Transactional
class RunServiceCancelApprovalsTest extends BaseTest {

    @Autowired
    private RunService runService;

    @Autowired
    private GateRecheckService gateRecheck;

    @Autowired
    private GateStampRepository stampRepo;

    @Autowired
    private WorkflowRunRepository runRepo;

    @Autowired
    private NodeExecutionRepository execRepo;

    @Autowired
    private GraphTemplateRepository graphTemplateRepo;

    @Autowired
    private NodeDefinitionRepository nodeDefRepo;

    @Autowired
    private TemplateNodeRepository templateNodeRepo;

    @MockitoBean
    private WorkflowServiceStubs workflowServiceStubs;

    @MockitoBean
    private WorkflowClient workflowClient;

    @MockitoBean
    private WorkflowClientRegistry workflowClientRegistry;

    private WorkflowRun run;
    private NodeExecution gateExec;

    @BeforeEach
    void setUp() {
        WorkflowClient client = mock(WorkflowClient.class);
        when(client.newUntypedWorkflowStub(anyString())).thenReturn(mock(WorkflowStub.class));
        when(workflowClientRegistry.clientFor(any())).thenReturn(client);

        GraphTemplate template = new GraphTemplate();
        template.setName("Cancel Approvals Test Template");
        template.setGraphId("cancel-approvals-test-" + UUID.randomUUID());
        template.setVersion(1);
        template = graphTemplateRepo.save(template);

        NodeDefinition nodeDef = new NodeDefinition();
        nodeDef.setName("cancel-approvals-test-gate-" + UUID.randomUUID());
        nodeDef.setExecutorType(ExecutorType.human);
        nodeDef.setSkills("[]");
        nodeDef.setInputSpec("{}");
        nodeDef.setOutputSpec("{}");
        nodeDef.setSecrets("[]");
        nodeDef = nodeDefRepo.save(nodeDef);

        TemplateNode templateNode = new TemplateNode();
        templateNode.setGraphTemplateId(template.getId());
        templateNode.setNodeDefinitionId(nodeDef.getId());
        templateNode.setLabel("final_approval");
        templateNode.setConfigOverrides("{}");
        templateNode.setEntrypoint(true);
        templateNode = templateNodeRepo.save(templateNode);

        run = new WorkflowRun();
        run.setGraphTemplateId(template.getId());
        run.setStatus(WorkflowRunStatus.awaiting_human);
        run.setExternalRunId("choruskube-run-" + UUID.randomUUID());
        run = runRepo.save(run);

        gateExec = new NodeExecution();
        gateExec.setWorkflowRunId(run.getId());
        gateExec.setTemplateNodeId(templateNode.getId());
        gateExec.setGraphVersion(1);
        gateExec.setStatus(NodeExecutionStatus.finalizing);
        gateExec = execRepo.save(gateExec);
    }

    @Test
    void cancelRun_voidsTheFinalizingApproval() {
        GateStamp stamp =
                gateRecheck.begin(run.getId(), gateExec, "final_approval", StampMode.always, null, null, null);

        runService.cancelRun(run.getId());

        GateStamp reloaded = stampRepo.findById(stamp.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(GateStampStatus.voided);
        assertThat(reloaded.getVoidReason()).isEqualTo("The run ended before the approval was finalized.");
        assertThat(execRepo.findById(gateExec.getId()).orElseThrow().getStatus())
                .isEqualTo(NodeExecutionStatus.skipped);
    }

    @Test
    void cancelRun_voidsTheHeldApproval() {
        GateStamp finalizing =
                gateRecheck.begin(run.getId(), gateExec, "final_approval", StampMode.always, null, null, null);
        GateStamp stamp = gateRecheck.holdFromFinalizing(finalizing.getId());

        runService.cancelRun(run.getId());

        assertThat(stampRepo.findById(stamp.getId()).orElseThrow().getStatus()).isEqualTo(GateStampStatus.voided);
    }
}
