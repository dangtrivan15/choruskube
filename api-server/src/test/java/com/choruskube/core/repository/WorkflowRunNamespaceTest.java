package com.choruskube.core.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.choruskube.core.BaseTest;
import com.choruskube.core.model.GraphTemplate;
import com.choruskube.core.model.WorkflowRun;
import io.temporal.client.WorkflowClient;
import io.temporal.serviceclient.WorkflowServiceStubs;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

@Transactional
class WorkflowRunNamespaceTest extends BaseTest {

    @MockitoBean
    private WorkflowServiceStubs workflowServiceStubs;

    @MockitoBean
    private WorkflowClient workflowClient;

    @Autowired
    private WorkflowRunRepository runRepo;

    @Autowired
    private GraphTemplateRepository graphTemplateRepo;

    private WorkflowRun newRun() {
        GraphTemplate tpl = new GraphTemplate();
        tpl.setName("ns-test-tpl-" + UUID.randomUUID());
        tpl.setGraphId("graph-" + UUID.randomUUID());
        tpl.setVersion(1);
        tpl = graphTemplateRepo.saveAndFlush(tpl);

        WorkflowRun run = new WorkflowRun();
        run.setGraphTemplateId(tpl.getId());
        return run;
    }

    /**
     * Nullable on purpose: every run that predates the column executed in the configured
     * namespace, so a backfill would assert a value the migration cannot know.
     */
    @Test
    void temporalNamespace_unset_savesAsNull() {
        WorkflowRun saved = runRepo.saveAndFlush(newRun());

        assertThat(runRepo.findById(saved.getId()).orElseThrow().getTemporalNamespace())
                .isNull();
    }

    @Test
    void temporalNamespace_roundTrips() {
        WorkflowRun run = newRun();
        run.setTemporalNamespace("tenant-ns");
        WorkflowRun saved = runRepo.saveAndFlush(run);

        assertThat(runRepo.findById(saved.getId()).orElseThrow().getTemporalNamespace())
                .isEqualTo("tenant-ns");
    }
}
