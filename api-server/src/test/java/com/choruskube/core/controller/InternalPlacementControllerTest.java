package com.choruskube.core.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.choruskube.core.BaseTest;
import com.choruskube.core.model.GraphTemplate;
import com.choruskube.core.model.WorkflowRun;
import com.choruskube.core.model.enums.WorkflowRunStatus;
import com.choruskube.core.repository.GraphTemplateRepository;
import com.choruskube.core.repository.WorkflowRunRepository;
import io.temporal.client.WorkflowClient;
import io.temporal.serviceclient.WorkflowServiceStubs;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

@AutoConfigureMockMvc
@Transactional
class InternalPlacementControllerTest extends BaseTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private WorkflowRunRepository runRepo;

    @Autowired
    private GraphTemplateRepository graphTemplateRepo;

    @MockitoBean
    private WorkflowServiceStubs workflowServiceStubs;

    @MockitoBean
    private WorkflowClient workflowClient;

    /**
     * With no placement policy the roster is the configured namespace alone — which is what an
     * OSS orchestrator must keep serving.
     */
    @Test
    void placements_noResolver_listsTheConfiguredNamespace() throws Exception {
        mockMvc.perform(get("/internal/placements"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.namespaces").isArray())
                .andExpect(jsonPath("$.namespaces[0]").value("choruskube"));
    }

    @Test
    void placement_unknownRun_is404() throws Exception {
        mockMvc.perform(get("/internal/runs/00000000-0000-0000-0000-000000000000/placement"))
                .andExpect(status().isNotFound());
    }

    @Test
    void placement_runWithRecordedNamespace_returnsIt() throws Exception {
        WorkflowRun run = persistRun("tenant-ns");

        mockMvc.perform(get("/internal/runs/{runId}/placement", run.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.namespace").value("tenant-ns"));
    }

    /**
     * A run predating the temporal_namespace column ran in the deployment's configured namespace
     * by construction, so the endpoint reports that rather than deciding a fresh placement for it.
     */
    @Test
    void placement_runWithNullNamespace_returnsTheConfiguredNamespace() throws Exception {
        WorkflowRun run = persistRun(null);

        mockMvc.perform(get("/internal/runs/{runId}/placement", run.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.namespace").value("choruskube"));
    }

    private WorkflowRun persistRun(String namespace) {
        GraphTemplate template = new GraphTemplate();
        template.setName("Placement Template");
        template.setGraphId("placement-template-" + UUID.randomUUID());
        template.setVersion(1);
        template = graphTemplateRepo.save(template);

        WorkflowRun run = new WorkflowRun();
        run.setGraphTemplateId(template.getId());
        run.setStatus(WorkflowRunStatus.pending);
        run.setExternalRunId("choruskube-run-" + UUID.randomUUID());
        run.setTemporalNamespace(namespace);
        return runRepo.save(run);
    }
}
