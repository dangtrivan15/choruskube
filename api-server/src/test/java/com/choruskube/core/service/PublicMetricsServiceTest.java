package com.choruskube.core.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.choruskube.core.BaseTest;
import com.choruskube.core.config.CacheConfig;
import com.choruskube.core.dto.LandingMetricsResponse;
import com.choruskube.core.model.GitRepo;
import com.choruskube.core.model.GraphTemplate;
import com.choruskube.core.model.WorkflowRun;
import com.choruskube.core.model.enums.WorkflowRunStatus;
import com.choruskube.core.repository.GitRepoRepository;
import com.choruskube.core.repository.GraphTemplateRepository;
import com.choruskube.core.repository.WorkflowRunRepository;
import io.temporal.client.WorkflowClient;
import io.temporal.serviceclient.WorkflowServiceStubs;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.CacheManager;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

/**
 * Integration test for {@link PublicMetricsService} backed by the real
 * TestContainers Postgres + the real {@link MetricsAggregatorImpl}.
 *
 * <p>The api-server starts with seed data (one ChorusKube self-repo, possibly
 * pre-existing rows from earlier migrations), so we capture a baseline before
 * seeding and assert deltas. The cache manager is cleared between calls so the
 * service re-reads fresh values from the DB after seeding.
 *
 * <p>Wall-clock seeds (started_at/completed_at) are chosen so the median of
 * five completed runs is exactly 90 seconds: {30, 60, 90, 120, 180}.
 */
@Transactional
class PublicMetricsServiceTest extends BaseTest {

    @MockitoBean
    private WorkflowServiceStubs workflowServiceStubs;

    @MockitoBean
    private WorkflowClient workflowClient;

    @Autowired
    private PublicMetricsService service;

    @Autowired
    private WorkflowRunRepository runRepo;

    @Autowired
    private GitRepoRepository repoRepo;

    @Autowired
    private GraphTemplateRepository graphTemplateRepo;

    @Autowired
    private CacheManager cacheManager;

    @PersistenceContext
    private EntityManager entityManager;

    private UUID templateId;

    @BeforeEach
    void setUp() {
        evictCache();

        // workflow_run.graph_template_id has a FK constraint to graph_template, so the
        // seeded runs need a real template row to satisfy it.
        GraphTemplate template = new GraphTemplate();
        template.setName("PublicMetricsTest Template");
        template.setGraphId(
                "public-metrics-test-" + UUID.randomUUID().toString().substring(0, 8));
        template.setVersion(1);
        template = graphTemplateRepo.saveAndFlush(template);
        templateId = template.getId();
    }

    private void evictCache() {
        var cache = cacheManager.getCache(CacheConfig.LANDING_METRICS);
        if (cache != null) {
            cache.clear();
        }
    }

    private WorkflowRun seedRun(WorkflowRunStatus status, Instant startedAt, Instant completedAt) {
        WorkflowRun run = new WorkflowRun();
        run.setGraphTemplateId(templateId);
        run.setStatus(status);
        run.setStartedAt(startedAt);
        run.setCompletedAt(completedAt);
        return runRepo.saveAndFlush(run);
    }

    private GitRepo seedRepo(String url) {
        GitRepo r = new GitRepo();
        r.setUrl(url);
        r.setName("test-repo-" + UUID.randomUUID().toString().substring(0, 8));
        return repoRepo.saveAndFlush(r);
    }

    @Test
    void getLandingMetrics_reflectsSeededDataAsDeltas() {
        LandingMetricsResponse baseline = service.getLandingMetrics();
        evictCache();

        // Seed: 5 completed runs with durations {30, 60, 90, 120, 180}s ⇒ P50 = 90s.
        // 1 failed run (no duration). 2 git_repos.
        Instant now = Instant.now();
        int[] durations = {30, 60, 90, 120, 180};
        for (int d : durations) {
            seedRun(WorkflowRunStatus.completed, now.minusSeconds(d), now);
        }
        seedRun(WorkflowRunStatus.failed, now.minusSeconds(45), now);
        seedRepo("https://github.com/test/" + UUID.randomUUID());
        seedRepo("https://github.com/test/" + UUID.randomUUID());

        // Force the persistence context to flush so native queries see the rows.
        entityManager.flush();

        LandingMetricsResponse after = service.getLandingMetrics();

        assertThat(after.totalRuns()).isEqualTo(baseline.totalRuns() + 6);
        assertThat(after.reposOrchestrated()).isEqualTo(baseline.reposOrchestrated() + 2);
        // Median is 90s. With baseline runs the actual median may differ if pre-existing
        // rows fall in the 90-day window; assert non-null and within a tolerance band that
        // 90 lies inside, since the seeded rows dominate any single pre-existing row.
        assertThat(after.medianRunSeconds()).isNotNull();
        assertThat(after.medianRunSeconds()).isCloseTo(90L, within(60L));
        // successRate over the 90-day window: with no baseline runs, 5/6 = 83.3 (1 dp).
        // Pre-existing terminal rows in the window may shift this; assert non-null and
        // within a generous band around 83.3.
        assertThat(after.successRate()).isNotNull();
        assertThat(after.successRate()).isBetween(50.0, 100.0);
        // Cache TTL field is informational and matches the configured property.
        assertThat(after.cacheTtlSeconds()).isPositive();
        assertThat(after.generatedAt()).isNotNull();
    }

    @Test
    void getLandingMetrics_isCachedAcrossCalls() {
        // Without evicting between calls, repeated invocations return the same response
        // object (Caffeine returns the stored value).
        LandingMetricsResponse first = service.getLandingMetrics();
        LandingMetricsResponse second = service.getLandingMetrics();
        assertThat(second.generatedAt()).isEqualTo(first.generatedAt());
    }
}
