package com.choruskube.core.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.choruskube.core.BaseTest;
import com.choruskube.core.model.DriftRecheckConfig.StampMode;
import com.choruskube.core.model.GateStamp;
import com.choruskube.core.model.GraphTemplate;
import com.choruskube.core.model.NodeDefinition;
import com.choruskube.core.model.NodeExecution;
import com.choruskube.core.model.NodeExecutionRepoBase;
import com.choruskube.core.model.TemplateNode;
import com.choruskube.core.model.WorkflowRun;
import com.choruskube.core.model.enums.ExecutorType;
import com.choruskube.core.model.enums.GateStampStatus;
import com.choruskube.core.model.enums.NodeExecutionStatus;
import io.temporal.client.WorkflowClient;
import io.temporal.serviceclient.WorkflowServiceStubs;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

@Transactional
class GateStampRepositoryTest extends BaseTest {

    @Autowired
    private GateStampRepository stampRepo;

    @Autowired
    private NodeExecutionRepoBaseRepository baseRepo;

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
        template.setName("Gate Stamp Repo Test Template");
        template.setGraphId("gate-stamp-repo-test-" + UUID.randomUUID());
        template.setVersion(1);
        template = graphTemplateRepo.save(template);

        NodeDefinition nodeDef = new NodeDefinition();
        nodeDef.setName("gate-stamp-repo-test-gate-" + UUID.randomUUID());
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
    void secondPendingStampForOneGate_isRejected() {
        stampRepo.saveAndFlush(stamp(GateStampStatus.pending));
        assertThatThrownBy(() -> stampRepo.saveAndFlush(stamp(GateStampStatus.pending)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void pendingAlongsideVoided_isAllowed() {
        stampRepo.saveAndFlush(stamp(GateStampStatus.voided));
        stampRepo.saveAndFlush(stamp(GateStampStatus.pending));
        assertThat(stampRepo.findByWorkflowRunIdOrderByCreatedAtAsc(run.getId()))
                .hasSize(2);
    }

    @Test
    void readyToReapply_onlyWhenTheGateIsAwaitingAgain() {
        GateStamp stamp = stampRepo.saveAndFlush(stamp(GateStampStatus.pending));
        gateExec.setStatus(NodeExecutionStatus.running);
        execRepo.saveAndFlush(gateExec);
        assertThat(stampRepo.findPendingWithGateIn(GateStampStatus.pending, NodeExecutionStatus.awaiting_human))
                .isEmpty();

        gateExec.setStatus(NodeExecutionStatus.awaiting_human);
        execRepo.saveAndFlush(gateExec);
        assertThat(stampRepo.findPendingWithGateIn(GateStampStatus.pending, NodeExecutionStatus.awaiting_human))
                .extracting(GateStamp::getId)
                .containsExactly(stamp.getId());
    }

    @Test
    void repoBase_isUniquePerExecutionAndRepo() {
        UUID repoId = UUID.randomUUID();
        baseRepo.saveAndFlush(base(repoId));
        assertThatThrownBy(() -> baseRepo.saveAndFlush(base(repoId)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void repoBase_roundTripsConflictedFiles() {
        NodeExecutionRepoBase saved = base(UUID.randomUUID());
        saved.setOutcome("conflict");
        saved.setBaseSha(null);
        saved.setConflictedFiles("[\"a.txt\",\"b/c.txt\"]");
        baseRepo.saveAndFlush(saved);

        assertThat(baseRepo.findByNodeExecutionIdAndGitRepoId(gateExec.getId(), saved.getGitRepoId()))
                .get()
                .satisfies(b -> {
                    assertThat(b.getConflictedFiles()).contains("a.txt").contains("b/c.txt");
                    assertThat(b.getRecordedAt()).isNotNull();
                });
    }

    private GateStamp stamp(GateStampStatus status) {
        GateStamp stamp = new GateStamp();
        stamp.setWorkflowRunId(run.getId());
        stamp.setGateTemplateNodeId(templateNode.getId());
        stamp.setGateLabel("final_approval");
        stamp.setStampedNodeExecutionId(gateExec.getId());
        stamp.setStampMode(StampMode.always);
        stamp.setStatus(status);
        return stamp;
    }

    private NodeExecutionRepoBase base(UUID repoId) {
        NodeExecutionRepoBase base = new NodeExecutionRepoBase();
        base.setNodeExecutionId(gateExec.getId());
        base.setGitRepoId(repoId);
        base.setOutcome("clean");
        base.setBaseSha("abc");
        return base;
    }
}
