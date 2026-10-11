package com.choruskube.core.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.choruskube.core.BaseTest;
import com.choruskube.core.dto.InternalUpdateNodeExecutionRequest;
import com.choruskube.core.dto.InternalUpdateRunStatusRequest;
import com.choruskube.core.exception.NotFoundException;
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
import com.choruskube.core.repository.GateStampRepository;
import com.choruskube.core.repository.GraphTemplateRepository;
import com.choruskube.core.repository.NodeDefinitionRepository;
import com.choruskube.core.repository.NodeExecutionRepository;
import com.choruskube.core.repository.TemplateNodeRepository;
import com.choruskube.core.repository.WorkflowRunRepository;
import io.temporal.client.WorkflowClient;
import io.temporal.serviceclient.WorkflowServiceStubs;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

@Transactional
class InternalRunServiceFinalizeModeTest extends BaseTest {

    @Autowired
    private InternalRunService internalRunService;

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

    private WorkflowRun run;
    private TemplateNode templateNode;
    private NodeExecution gateExec;

    @BeforeEach
    void setUp() {
        GraphTemplate template = new GraphTemplate();
        template.setName("Finalize Mode Test Template");
        template.setGraphId("finalize-mode-test-" + UUID.randomUUID());
        template.setVersion(1);
        template = graphTemplateRepo.save(template);

        NodeDefinition nodeDef = new NodeDefinition();
        nodeDef.setName("finalize-mode-test-gate-" + UUID.randomUUID());
        nodeDef.setExecutorType(ExecutorType.human);
        nodeDef.setSkills("[]");
        nodeDef.setInputSpec("{}");
        nodeDef.setOutputSpec("{}");
        nodeDef.setSecrets("[]");
        nodeDef = nodeDefRepo.save(nodeDef);

        templateNode = new TemplateNode();
        templateNode.setGraphTemplateId(template.getId());
        templateNode.setNodeDefinitionId(nodeDef.getId());
        templateNode.setLabel("final_approval");
        templateNode.setConfigOverrides("{}");
        templateNode.setEntrypoint(true);
        templateNode = templateNodeRepo.save(templateNode);

        run = new WorkflowRun();
        run.setGraphTemplateId(template.getId());
        run = runRepo.save(run);

        gateExec = new NodeExecution();
        gateExec.setWorkflowRunId(run.getId());
        gateExec.setTemplateNodeId(templateNode.getId());
        gateExec.setGraphVersion(1);
        gateExec.setStatus(NodeExecutionStatus.running);
        gateExec = execRepo.save(gateExec);
    }

    @Test
    void statusUpdate_withFinalizeMode_storesIt() {
        internalRunService.updateNodeExecutionStatus(
                run.getId(),
                gateExec.getId(),
                new InternalUpdateNodeExecutionRequest("awaiting_human", null, null, null, null, null, "async"));

        assertThat(execRepo.findById(gateExec.getId()).orElseThrow().getFinalizeMode())
                .isEqualTo("async");
    }

    @Test
    void statusUpdate_withoutFinalizeMode_leavesItUntouched() {
        internalRunService.updateNodeExecutionStatus(
                run.getId(),
                gateExec.getId(),
                new InternalUpdateNodeExecutionRequest("awaiting_human", null, null, null, null, null, "async"));
        internalRunService.updateNodeExecutionStatus(
                run.getId(),
                gateExec.getId(),
                new InternalUpdateNodeExecutionRequest("running", null, null, null, null, null, null));

        assertThat(execRepo.findById(gateExec.getId()).orElseThrow().getFinalizeMode())
                .isEqualTo("async");
    }

    @Test
    void getNodeExecution_otherRunsExecution_isNotFound() {
        UUID otherRunId = UUID.randomUUID();

        assertThatThrownBy(() -> internalRunService.getNodeExecution(otherRunId, gateExec.getId()))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void cancelWhileFinalizing_voidsApproval() {
        GateStamp stamp =
                gateRecheck.begin(run.getId(), gateExec, "final_approval", StampMode.always, null, null, null);

        internalRunService.updateRunStatus(run.getId(), new InternalUpdateRunStatusRequest("cancelled"));

        GateStamp reloaded = stampRepo.findById(stamp.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(GateStampStatus.voided);
        assertThat(reloaded.getVoidReason()).isEqualTo("The run ended before the approval was finalized.");
    }

    @ParameterizedTest
    @ValueSource(strings = {"completed", "failed", "cancelled"})
    void runEnd_voidsHeldApproval(String terminalStatus) {
        GateStamp stamp = gateRecheck.hold(run.getId(), gateExec, "final_approval", StampMode.always, null, null, null);

        internalRunService.updateRunStatus(run.getId(), new InternalUpdateRunStatusRequest(terminalStatus));

        GateStamp reloaded = stampRepo.findById(stamp.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(GateStampStatus.voided);
        assertThat(reloaded.getVoidReason()).isEqualTo("The run ended before the approval was finalized.");
    }

    @Test
    void runEnd_isIdempotentAndToleratesARunWithNoApprovals() {
        internalRunService.updateRunStatus(run.getId(), new InternalUpdateRunStatusRequest("failed"));

        GateStamp stamp =
                gateRecheck.begin(run.getId(), gateExec, "final_approval", StampMode.always, null, null, null);
        internalRunService.updateRunStatus(run.getId(), new InternalUpdateRunStatusRequest("cancelled"));
        internalRunService.updateRunStatus(run.getId(), new InternalUpdateRunStatusRequest("cancelled"));

        assertThat(stampRepo.findById(stamp.getId()).orElseThrow().getStatus()).isEqualTo(GateStampStatus.voided);
    }

    @Test
    void statusUpdateShortOfTheEnd_leavesApprovalsAlone() {
        GateStamp stamp =
                gateRecheck.begin(run.getId(), gateExec, "final_approval", StampMode.always, null, null, null);

        internalRunService.updateRunStatus(run.getId(), new InternalUpdateRunStatusRequest("awaiting_human"));

        assertThat(stampRepo.findById(stamp.getId()).orElseThrow().getStatus()).isEqualTo(GateStampStatus.finalizing);
    }
}
