package com.choruskube.core.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.choruskube.core.BaseTest;
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
import io.temporal.serviceclient.WorkflowServiceStubs;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

@Transactional
class GateRecheckServiceLifecycleTest extends BaseTest {

    @Autowired
    private GateStampRepository stampRepo;

    @Autowired
    private GateRecheckService service;

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
        template.setName("Gate Recheck Lifecycle Test Template");
        template.setGraphId("gate-recheck-lifecycle-test-" + UUID.randomUUID());
        template.setVersion(1);
        template = graphTemplateRepo.save(template);

        NodeDefinition nodeDef = new NodeDefinition();
        nodeDef.setName("gate-recheck-lifecycle-test-gate-" + UUID.randomUUID());
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
        gateExec.setStatus(NodeExecutionStatus.awaiting_human);
        gateExec = execRepo.save(gateExec);
    }

    @Test
    void holdThenHoldAgain_incrementsRounds() {
        GateStamp stamp = service.hold(run.getId(), gateExec, "final_approval", StampMode.always, "ship it", null);
        assertThat(stamp.getRecheckRounds()).isEqualTo(1);
        assertThat(stamp.getStatus()).isEqualTo(GateStampStatus.pending);
        assertThat(stamp.getStampedNodeExecutionId()).isEqualTo(gateExec.getId());
        assertThat(stamp.getFeedback()).isEqualTo("ship it");

        assertThat(service.holdAgain(stamp.getId()).getRecheckRounds()).isEqualTo(2);
    }

    @Test
    void consume_onlyFromPending() {
        GateStamp stamp = service.hold(run.getId(), gateExec, "final_approval", StampMode.always, null, null);
        service.voidStamp(stamp.getId(), "because");
        service.consume(stamp.getId());

        GateStamp reloaded = stampRepo.findById(stamp.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(GateStampStatus.voided);
        assertThat(reloaded.getVoidReason()).isEqualTo("because");
        assertThat(service.findPending(stamp.getId())).isEmpty();
    }

    @Test
    void voidPendingFor_voidsOnlyThatGate() {
        GateStamp stamp = service.hold(run.getId(), gateExec, "final_approval", StampMode.always, null, null);
        service.voidPendingFor(run.getId(), UUID.randomUUID(), GateRecheckService.REVIEWER_DECIDED);
        assertThat(service.findPending(stamp.getId())).isPresent();

        service.voidPendingFor(run.getId(), templateNode.getId(), GateRecheckService.REVIEWER_DECIDED);
        assertThat(stampRepo.findById(stamp.getId()).orElseThrow().getVoidReason())
                .isEqualTo(GateRecheckService.REVIEWER_DECIDED);
    }

    @Test
    void voidPendingOnEndedRuns_voidsOnlyEndedRuns() {
        GateStamp live = service.hold(run.getId(), gateExec, "final_approval", StampMode.always, null, null);

        WorkflowRun ended = new WorkflowRun();
        ended.setGraphTemplateId(run.getGraphTemplateId());
        ended.setStatus(WorkflowRunStatus.cancelled);
        ended = runRepo.save(ended);
        NodeExecution endedGate = new NodeExecution();
        endedGate.setWorkflowRunId(ended.getId());
        endedGate.setTemplateNodeId(templateNode.getId());
        endedGate.setGraphVersion(1);
        endedGate = execRepo.save(endedGate);
        GateStamp orphan = service.hold(ended.getId(), endedGate, "final_approval", StampMode.always, null, null);

        run.setStatus(WorkflowRunStatus.running);
        runRepo.saveAndFlush(run);

        assertThat(service.voidPendingOnEndedRuns()).isEqualTo(1);
        assertThat(stampRepo.findById(orphan.getId()).orElseThrow().getVoidReason())
                .isEqualTo(GateRecheckService.RUN_ENDED);
        assertThat(service.findPending(live.getId())).isPresent();
    }

    @Test
    void findReadyToReapply_requiresAwaitingGate() {
        GateStamp stamp = service.hold(run.getId(), gateExec, "final_approval", StampMode.always, null, null);
        assertThat(service.findReadyToReapply()).extracting(GateStamp::getId).contains(stamp.getId());

        gateExec.setStatus(NodeExecutionStatus.running);
        execRepo.saveAndFlush(gateExec);
        assertThat(service.findReadyToReapply()).extracting(GateStamp::getId).doesNotContain(stamp.getId());
    }
}
