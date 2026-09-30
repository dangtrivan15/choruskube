package com.choruskube.core.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.choruskube.core.BaseTest;
import com.choruskube.core.config.GraphIds;
import com.choruskube.core.model.GraphTemplate;
import com.choruskube.core.model.WorkflowRun;
import com.choruskube.core.model.enums.RoadmapMaterializeMode;
import com.choruskube.core.repository.GraphTemplateRepository;
import com.choruskube.core.repository.WorkflowRunRepository;
import io.temporal.client.WorkflowClient;
import io.temporal.serviceclient.WorkflowServiceStubs;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Discovery, against the real seeded system templates: whether a run's workflow declares a
 * roadmap gate, and if so which node/label/mode.
 */
class RoadmapGateResolverTest extends BaseTest {

    @MockitoBean
    private WorkflowServiceStubs workflowServiceStubs;

    @MockitoBean
    private WorkflowClient workflowClient;

    @Autowired
    private RoadmapGateResolver resolver;

    @Autowired
    private GraphTemplateRepository templateRepo;

    @Autowired
    private WorkflowRunRepository runRepo;

    @Test
    void featureDevV43Run_resolvesFinalApprovalRoadmapExtension() {
        GraphTemplate template = templateRepo
                .findFirstByGraphIdOrderByVersionDesc(GraphIds.FEATURE_DEVELOPMENT)
                .orElseThrow();
        WorkflowRun run = saveRun(template.getId());

        var gate = resolver.forRun(run.getId());

        assertThat(gate).isPresent();
        assertThat(gate.get().label()).isEqualTo("final_approval");
        assertThat(gate.get().mode()).isEqualTo(RoadmapMaterializeMode.roadmap_extension);
    }

    @Test
    void roadmapProvisionerRun_resolvesHumanGateRoadmapCandidates() {
        GraphTemplate template = templateRepo
                .findFirstByGraphIdOrderByVersionDesc(GraphIds.ROADMAP_PROVISIONER)
                .orElseThrow();
        WorkflowRun run = saveRun(template.getId());

        var gate = resolver.forRun(run.getId());

        assertThat(gate).isPresent();
        assertThat(gate.get().label()).isEqualTo("roadmap_human_gate");
        assertThat(gate.get().mode()).isEqualTo(RoadmapMaterializeMode.roadmap_candidates);
    }

    @Test
    void templateWithNoGate_resolvesEmpty() {
        GraphTemplate template = new GraphTemplate();
        template.setGraphId("gate-resolver-test-no-gate");
        template.setVersion(1);
        template.setName("No Gate");
        template.setInputSchema("[]");
        template = templateRepo.save(template);
        WorkflowRun run = saveRun(template.getId());

        assertThat(resolver.forRun(run.getId())).isEmpty();
    }

    @Test
    void templateWithNoNodes_resolvesEmpty() {
        GraphTemplate template = new GraphTemplate();
        template.setGraphId("gate-resolver-test-no-nodes");
        template.setVersion(1);
        template.setName("No Nodes");
        template.setInputSchema("[]");
        template = templateRepo.save(template);
        WorkflowRun run = saveRun(template.getId());

        assertThat(resolver.forRun(run.getId())).isEmpty();
    }

    private WorkflowRun saveRun(java.util.UUID templateId) {
        WorkflowRun run = new WorkflowRun();
        run.setGraphTemplateId(templateId);
        run.setInputs("{}");
        return runRepo.save(run);
    }
}
