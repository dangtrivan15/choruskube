package com.choruskube.core.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.choruskube.core.BaseTest;
import com.choruskube.core.config.InternalAuthFilter;
import com.choruskube.core.model.GraphTemplate;
import com.choruskube.core.model.NodeDefinition;
import com.choruskube.core.model.NodeExecution;
import com.choruskube.core.model.TemplateNode;
import com.choruskube.core.model.WorkflowRun;
import com.choruskube.core.model.enums.ExecutorType;
import com.choruskube.core.repository.GraphTemplateRepository;
import com.choruskube.core.repository.NodeDefinitionRepository;
import com.choruskube.core.repository.NodeExecutionRepository;
import com.choruskube.core.repository.TemplateNodeRepository;
import com.choruskube.core.repository.WorkflowRunRepository;
import io.temporal.client.WorkflowClient;
import io.temporal.serviceclient.WorkflowServiceStubs;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/** Runs {@link RunScopeRouteAudit} over core's own route table, with internal auth enforced. */
@AutoConfigureMockMvc
@Transactional
@TestPropertySource(
        properties = {
            // SHA-256 hash of "test-orchestrator-secret"
            "internal.auth.orchestrator-secret-hash=d6c5f99f36089f6757e4a7946de9dd0ef1d69983ab5920d40ce5ee1d5066159d",
            "internal.auth.mode=enforce",
            "worker.registration.token=test-fleet-token"
        })
class RunScopeRoutesTest extends BaseTest {

    private static final String JOB_SECRET = "run-scope-routes-test-job-secret";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping handlerMapping;

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

    private WorkflowRun runA;
    private WorkflowRun runB;
    private NodeExecution execX;

    @BeforeEach
    void setUp() {
        GraphTemplate template = new GraphTemplate();
        template.setName("Run Scope Routes Test Template");
        template.setGraphId("run-scope-routes-test");
        template.setVersion(1);
        template = graphTemplateRepo.save(template);

        NodeDefinition nodeDef = new NodeDefinition();
        nodeDef.setName("run-scope-routes-test-node");
        nodeDef.setExecutorType(ExecutorType.ai);
        nodeDef.setImage("test:latest");
        nodeDef.setPromptTemplate("test");
        nodeDef.setSkills("[]");
        nodeDef.setInputSpec("{}");
        nodeDef.setOutputSpec("{}");
        nodeDef.setSecrets("[]");
        nodeDef = nodeDefRepo.save(nodeDef);

        TemplateNode templateNode = new TemplateNode();
        templateNode.setGraphTemplateId(template.getId());
        templateNode.setNodeDefinitionId(nodeDef.getId());
        templateNode.setLabel("Run Scope Routes Test Node");
        templateNode.setConfigOverrides("{}");
        templateNode.setEntrypoint(true);
        templateNode = templateNodeRepo.save(templateNode);

        runA = new WorkflowRun();
        runA.setGraphTemplateId(template.getId());
        runA = runRepo.save(runA);

        runB = new WorkflowRun();
        runB.setGraphTemplateId(template.getId());
        runB = runRepo.save(runB);

        execX = new NodeExecution();
        execX.setWorkflowRunId(runA.getId());
        execX.setTemplateNodeId(templateNode.getId());
        execX.setGraphVersion(1);
        execX.setJobSecretHash(InternalAuthFilter.sha256Hex(JOB_SECRET));
        execX = execRepo.save(execX);
    }

    @Test
    void coreRouteTable_passesR1ThroughR4() {
        RunScopeRouteAudit.of(handlerMapping, mockMvc)
                .executionInRun(runA.getId(), execX.getId(), JOB_SECRET)
                .foreignRun(runB.getId())
                .check();
    }

    @Test
    void routesIncludesTheNamedRoutes() {
        var routes = RunScopeRouteAudit.of(handlerMapping, mockMvc)
                .executionInRun(runA.getId(), execX.getId(), JOB_SECRET)
                .foreignRun(runB.getId())
                .routes();

        assertThat(routes)
                .contains(
                        "GET /internal/runs/{runId}/node-executions/{nodeExecId}/presign",
                        "POST /internal/runs/{runId}/node-executions/{nodeExecId}/gate-finalize/held",
                        "PUT /worker/runs/{runId}/node-executions/{nodeExecId}/status",
                        "PUT /internal/runs/{runId}/status");
    }
}
