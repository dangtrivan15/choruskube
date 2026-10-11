package com.choruskube.core.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.choruskube.core.BaseTest;
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
        GateStamp stamp = service.hold(
                run.getId(), gateExec, "final_approval", StampMode.always, "ship it", "{\"a.pdf\":\"k\"}", null);
        assertThat(stamp.getRecheckRounds()).isEqualTo(1);
        assertThat(stamp.getStatus()).isEqualTo(GateStampStatus.pending);
        assertThat(stamp.getStampedNodeExecutionId()).isEqualTo(gateExec.getId());
        assertThat(stamp.getFeedback()).isEqualTo("ship it");
        assertThat(stamp.getAttachmentRefs()).contains("a.pdf");

        assertThat(service.holdAgain(stamp.getId()).getRecheckRounds()).isEqualTo(2);
    }

    @Test
    void consume_onlyFromPending() {
        GateStamp stamp = service.hold(run.getId(), gateExec, "final_approval", StampMode.always, null, null, null);
        service.voidStamp(stamp.getId(), "because", gateExec.getId());
        service.consume(stamp.getId());

        GateStamp reloaded = stampRepo.findById(stamp.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(GateStampStatus.voided);
        assertThat(reloaded.getVoidReason()).isEqualTo("because");
        assertThat(reloaded.getParkedNodeExecutionId()).isEqualTo(gateExec.getId());
        assertThat(service.findPending(stamp.getId())).isEmpty();
    }

    @Test
    void voidPendingFor_voidsOnlyThatGate() {
        GateStamp stamp = service.hold(run.getId(), gateExec, "final_approval", StampMode.always, null, null, null);
        service.voidPendingFor(run.getId(), UUID.randomUUID(), GateRecheckService.REVIEWER_DECIDED);
        assertThat(service.findPending(stamp.getId())).isPresent();

        service.voidPendingFor(run.getId(), templateNode.getId(), GateRecheckService.REVIEWER_DECIDED);
        assertThat(stampRepo.findById(stamp.getId()).orElseThrow().getVoidReason())
                .isEqualTo(GateRecheckService.REVIEWER_DECIDED);
    }

    @Test
    void voidPendingOnEndedRuns_voidsOnlyEndedRuns() {
        GateStamp live = service.hold(run.getId(), gateExec, "final_approval", StampMode.always, null, null, null);

        WorkflowRun ended = new WorkflowRun();
        ended.setGraphTemplateId(run.getGraphTemplateId());
        ended.setStatus(WorkflowRunStatus.cancelled);
        ended = runRepo.save(ended);
        NodeExecution endedGate = new NodeExecution();
        endedGate.setWorkflowRunId(ended.getId());
        endedGate.setTemplateNodeId(templateNode.getId());
        endedGate.setGraphVersion(1);
        endedGate = execRepo.save(endedGate);
        GateStamp orphan = service.hold(ended.getId(), endedGate, "final_approval", StampMode.always, null, null, null);

        run.setStatus(WorkflowRunStatus.running);
        runRepo.saveAndFlush(run);

        assertThat(service.voidPendingOnEndedRuns()).isEqualTo(1);
        assertThat(stampRepo.findById(orphan.getId()).orElseThrow().getVoidReason())
                .isEqualTo(GateRecheckService.RUN_ENDED);
        assertThat(service.findPending(live.getId())).isPresent();
    }

    @Test
    void voidLiveForRun_voidsHeldAndFinalizingApprovalsOfThatRunOnly() {
        GateStamp held = service.hold(run.getId(), gateExec, "final_approval", StampMode.always, null, null, null);
        TemplateNode secondNode = new TemplateNode();
        secondNode.setGraphTemplateId(templateNode.getGraphTemplateId());
        secondNode.setNodeDefinitionId(templateNode.getNodeDefinitionId());
        secondNode.setLabel("second_approval");
        secondNode.setConfigOverrides("{}");
        secondNode = templateNodeRepo.save(secondNode);
        NodeExecution otherGate = new NodeExecution();
        otherGate.setWorkflowRunId(run.getId());
        otherGate.setTemplateNodeId(secondNode.getId());
        otherGate.setGraphVersion(1);
        otherGate = execRepo.save(otherGate);
        GateStamp done = service.begin(run.getId(), otherGate, "second_approval", StampMode.always, null, null, null);
        service.consume(done.getId());
        stampRepo.flush();
        GateStamp finalizing =
                service.begin(run.getId(), otherGate, "second_approval", StampMode.always, null, null, null);

        WorkflowRun other = new WorkflowRun();
        other.setGraphTemplateId(run.getGraphTemplateId());
        other = runRepo.save(other);
        NodeExecution otherRunGate = new NodeExecution();
        otherRunGate.setWorkflowRunId(other.getId());
        otherRunGate.setTemplateNodeId(templateNode.getId());
        otherRunGate.setGraphVersion(1);
        otherRunGate = execRepo.save(otherRunGate);
        GateStamp elsewhere =
                service.hold(other.getId(), otherRunGate, "final_approval", StampMode.always, null, null, null);

        assertThat(service.voidLiveForRun(run.getId(), GateRecheckService.RUN_ENDED))
                .isEqualTo(2);

        assertThat(stampRepo.findById(held.getId()).orElseThrow().getStatus()).isEqualTo(GateStampStatus.voided);
        assertThat(stampRepo.findById(held.getId()).orElseThrow().getVoidReason())
                .isEqualTo("The run ended before the approval was finalized.");
        assertThat(stampRepo.findById(finalizing.getId()).orElseThrow().getStatus())
                .isEqualTo(GateStampStatus.voided);
        assertThat(stampRepo.findById(done.getId()).orElseThrow().getStatus()).isEqualTo(GateStampStatus.consumed);
        assertThat(stampRepo.findById(elsewhere.getId()).orElseThrow().getStatus())
                .isEqualTo(GateStampStatus.pending);
        assertThat(service.voidLiveForRun(run.getId(), GateRecheckService.RUN_ENDED))
                .isZero();
    }

    @Test
    void begin_recordsAFinalizingApprovalAtRoundZero() {
        GateStamp stamp = service.begin(
                run.getId(), gateExec, "final_approval", StampMode.if_unchanged, "ship it", "{\"a.pdf\":\"k\"}", null);

        GateStamp reloaded = stampRepo.findById(stamp.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(GateStampStatus.finalizing);
        assertThat(reloaded.getRecheckRounds()).isZero();
        assertThat(reloaded.getStampMode()).isEqualTo(StampMode.if_unchanged);
        assertThat(reloaded.getStampedNodeExecutionId()).isEqualTo(gateExec.getId());
        assertThat(reloaded.getMaterializedAt()).isNull();
        assertThat(service.findFinalizingFor(run.getId(), templateNode.getId()))
                .map(GateStamp::getId)
                .contains(stamp.getId());
        assertThat(service.findHeldFor(run.getId(), templateNode.getId())).isEmpty();
    }

    @Test
    void holdFromFinalizing_thenClaimHeld_roundTripsAndCountsARound() {
        GateStamp stamp = service.begin(run.getId(), gateExec, "final_approval", StampMode.always, null, null, null);

        GateStamp held = service.holdFromFinalizing(stamp.getId());
        assertThat(held.getStatus()).isEqualTo(GateStampStatus.pending);
        assertThat(held.getRecheckRounds()).isEqualTo(1);
        assertThat(service.findHeldFor(run.getId(), templateNode.getId()))
                .map(GateStamp::getId)
                .contains(stamp.getId());

        GateStamp claimed = service.claimHeld(run.getId(), templateNode.getId()).orElseThrow();
        assertThat(claimed.getId()).isEqualTo(stamp.getId());
        assertThat(claimed.getStatus()).isEqualTo(GateStampStatus.finalizing);
        assertThat(service.claimHeld(run.getId(), templateNode.getId())).isEmpty();
    }

    @Test
    void holdFromFinalizing_refusesAnApprovalThatIsNotFinalizing() {
        GateStamp stamp = service.hold(run.getId(), gateExec, "final_approval", StampMode.always, null, null, null);

        assertThatThrownBy(() -> service.holdFromFinalizing(stamp.getId())).isInstanceOf(NotFoundException.class);
    }

    @Test
    void voidAndConsume_actOnAFinalizingApproval() {
        GateStamp voided = service.begin(run.getId(), gateExec, "final_approval", StampMode.always, null, null, null);
        service.voidStamp(voided.getId(), "refused", gateExec.getId());
        stampRepo.flush();
        assertThat(stampRepo.findById(voided.getId()).orElseThrow().getStatus()).isEqualTo(GateStampStatus.voided);

        GateStamp consumed = service.begin(run.getId(), gateExec, "final_approval", StampMode.always, null, null, null);
        service.consume(consumed.getId());
        assertThat(stampRepo.findById(consumed.getId()).orElseThrow().getStatus())
                .isEqualTo(GateStampStatus.consumed);
    }

    @Test
    void findReadyToReapply_requiresAwaitingGate() {
        GateStamp stamp = service.hold(run.getId(), gateExec, "final_approval", StampMode.always, null, null, null);
        assertThat(service.findReadyToReapply()).extracting(GateStamp::getId).contains(stamp.getId());

        gateExec.setStatus(NodeExecutionStatus.running);
        execRepo.saveAndFlush(gateExec);
        assertThat(service.findReadyToReapply()).extracting(GateStamp::getId).doesNotContain(stamp.getId());
    }
}
