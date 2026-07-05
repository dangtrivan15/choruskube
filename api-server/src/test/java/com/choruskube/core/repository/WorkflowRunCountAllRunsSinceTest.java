package com.choruskube.core.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.choruskube.core.BaseTest;
import com.choruskube.core.model.GraphTemplate;
import com.choruskube.core.model.WorkflowRun;
import com.choruskube.core.model.enums.WorkflowRunStatus;
import io.temporal.client.WorkflowClient;
import io.temporal.serviceclient.WorkflowServiceStubs;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

/**
 * Integration test for {@link WorkflowRunRepository#countAllRunsSince(Instant)} — the global,
 * live-rows-only run count over a trailing window (telemetry runCount). Asserts deltas off a
 * baseline because the booted app may already hold seed rows.
 *
 * <p>{@code created_at} is {@code updatable = false} and set by {@code @PrePersist} to
 * {@code Instant.now()}, so backdating is done with a native UPDATE after the row is saved.
 */
@Transactional
class WorkflowRunCountAllRunsSinceTest extends BaseTest {

    @MockitoBean
    private WorkflowServiceStubs workflowServiceStubs;

    @MockitoBean
    private WorkflowClient workflowClient;

    @Autowired
    private WorkflowRunRepository runRepo;

    @Autowired
    private com.choruskube.core.repository.GraphTemplateRepository graphTemplateRepo;

    @PersistenceContext
    private EntityManager em;

    private UUID templateId;

    @BeforeEach
    void setUp() {
        GraphTemplate template = new GraphTemplate();
        template.setName("Telemetry Count Template");
        template.setGraphId("tel-count-" + UUID.randomUUID().toString().substring(0, 8));
        template.setVersion(1);
        template = graphTemplateRepo.saveAndFlush(template);
        templateId = template.getId();
    }

    /** Persist a run, then backdate its created_at (and optionally soft-delete it). */
    private void seedRun(Instant createdAt, boolean deleted) {
        WorkflowRun run = new WorkflowRun();
        run.setGraphTemplateId(templateId);
        run.setStatus(WorkflowRunStatus.completed);
        run = runRepo.saveAndFlush(run);
        em.createNativeQuery("UPDATE workflow_run SET created_at = :ts, deleted_at = :del WHERE id = :id")
                .setParameter("ts", createdAt)
                .setParameter("del", deleted ? createdAt : null)
                .setParameter("id", run.getId())
                .executeUpdate();
        em.flush();
        em.clear();
    }

    @Test
    void countAllRunsSince_countsLiveRowsInTheTrailingWindow() {
        Instant now = Instant.now();
        Instant sevenDaysAgo = now.minus(7, ChronoUnit.DAYS);

        long baseline = runRepo.countAllRunsSince(sevenDaysAgo);

        // Inside the 7-day window, live → counted (3 rows).
        seedRun(now.minus(1, ChronoUnit.HOURS), false);
        seedRun(now.minus(3, ChronoUnit.DAYS), false);
        seedRun(now.minus(6, ChronoUnit.DAYS), false);

        // Outside the window (older than 7 days) → not counted.
        seedRun(now.minus(10, ChronoUnit.DAYS), false);

        // Inside the window but soft-deleted → not counted.
        seedRun(now.minus(2, ChronoUnit.DAYS), true);

        long count = runRepo.countAllRunsSince(now.minus(7, ChronoUnit.DAYS));

        assertThat(count).isEqualTo(baseline + 3);
    }
}
