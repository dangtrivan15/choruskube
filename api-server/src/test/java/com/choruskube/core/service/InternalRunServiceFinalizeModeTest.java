package com.choruskube.core.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.choruskube.core.BaseTest;
import com.choruskube.core.dto.InternalUpdateNodeExecutionRequest;
import com.choruskube.core.exception.NotFoundException;
import com.choruskube.core.model.GraphTemplate;
import com.choruskube.core.model.NodeDefinition;
import com.choruskube.core.model.NodeExecution;
import com.choruskube.core.model.TemplateNode;
import com.choruskube.core.model.WorkflowRun;
import com.choruskube.core.model.enums.ExecutorType;
import com.choruskube.core.model.enums.NodeExecutionStatus;
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
class InternalRunServiceFinalizeModeTest extends BaseTest {

    @Autowired
    private InternalRunService internalRunService;

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

        TemplateNode templateNode = new TemplateNode();
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
}
