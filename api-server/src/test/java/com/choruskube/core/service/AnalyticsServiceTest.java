package com.choruskube.core.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.choruskube.core.dto.*;
import com.choruskube.core.repository.NodeExecutionRepository;
import com.choruskube.core.repository.TaskRepository;
import com.choruskube.core.repository.WorkflowRunRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * Unit tests for the GLOBAL analytics implementation ({@link GlobalAnalyticsService}) — auth off.
 * The repository mocks now take a single {@code since} argument because the org clause/param has
 * been removed from the analytics queries; the global impl never resolves an org.
 *
 * <p>The {@code period} parsing tests live with the shared {@link AnalyticsResultMapper} (the impl
 * delegates to it).
 */
class AnalyticsServiceTest {

    private WorkflowRunRepository runRepo;
    private NodeExecutionRepository execRepo;
    private TaskRepository taskRepo;
    private GlobalAnalyticsService service;

    @BeforeEach
    void setUp() {
        runRepo = Mockito.mock(WorkflowRunRepository.class);
        execRepo = Mockito.mock(NodeExecutionRepository.class);
        taskRepo = Mockito.mock(TaskRepository.class);
        service = new GlobalAnalyticsService(runRepo, execRepo, taskRepo);
    }

    // --- parsePeriod tests (shared mapper) ---

    @Test
    void parsePeriod_null_defaults30d() {
        Instant result = AnalyticsResultMapper.parsePeriod(null);
        Instant expected = Instant.now().minus(30, ChronoUnit.DAYS);
        assertThat(result).isBetween(expected.minusSeconds(5), expected.plusSeconds(5));
    }

    @Test
    void parsePeriod_blank_defaults30d() {
        Instant result = AnalyticsResultMapper.parsePeriod("");
        Instant expected = Instant.now().minus(30, ChronoUnit.DAYS);
        assertThat(result).isBetween(expected.minusSeconds(5), expected.plusSeconds(5));
    }

    @Test
    void parsePeriod_7d_returns7DaysAgo() {
        Instant result = AnalyticsResultMapper.parsePeriod("7d");
        Instant expected = Instant.now().minus(7, ChronoUnit.DAYS);
        assertThat(result).isBetween(expected.minusSeconds(5), expected.plusSeconds(5));
    }

    @Test
    void parsePeriod_24h_returns24HoursAgo() {
        Instant result = AnalyticsResultMapper.parsePeriod("24h");
        Instant expected = Instant.now().minus(24, ChronoUnit.HOURS);
        assertThat(result).isBetween(expected.minusSeconds(5), expected.plusSeconds(5));
    }

    @Test
    void parsePeriod_90d_returns90DaysAgo() {
        Instant result = AnalyticsResultMapper.parsePeriod("90d");
        Instant expected = Instant.now().minus(90, ChronoUnit.DAYS);
        assertThat(result).isBetween(expected.minusSeconds(5), expected.plusSeconds(5));
    }

    @Test
    void parsePeriod_invalidFormat_defaults30d() {
        Instant result = AnalyticsResultMapper.parsePeriod("xyz");
        Instant expected = Instant.now().minus(30, ChronoUnit.DAYS);
        assertThat(result).isBetween(expected.minusSeconds(5), expected.plusSeconds(5));
    }

    @Test
    void parsePeriod_negativeDays_defaults30d() {
        Instant result = AnalyticsResultMapper.parsePeriod("-5d");
        Instant expected = Instant.now().minus(30, ChronoUnit.DAYS);
        assertThat(result).isBetween(expected.minusSeconds(5), expected.plusSeconds(5));
    }

    @Test
    void parsePeriod_zeroDays_defaults30d() {
        Instant result = AnalyticsResultMapper.parsePeriod("0d");
        Instant expected = Instant.now().minus(30, ChronoUnit.DAYS);
        assertThat(result).isBetween(expected.minusSeconds(5), expected.plusSeconds(5));
    }

    @Test
    void parsePeriod_tooLargeDays_defaults30d() {
        Instant result = AnalyticsResultMapper.parsePeriod("400d");
        Instant expected = Instant.now().minus(30, ChronoUnit.DAYS);
        assertThat(result).isBetween(expected.minusSeconds(5), expected.plusSeconds(5));
    }

    // --- getOverview tests ---

    @Test
    void getOverview_withData_returnsComputedStats() {
        Object[] row = new Object[] {10L, 7L, 2L, 120.5, 100.0, 300.0};
        Mockito.when(runRepo.getOverviewStats(Mockito.any(Instant.class))).thenReturn(row);

        AnalyticsOverviewResponse result = service.getOverview("30d");

        assertThat(result.totalRuns()).isEqualTo(10);
        assertThat(result.completedRuns()).isEqualTo(7);
        assertThat(result.failedRuns()).isEqualTo(2);
        assertThat(result.successRate()).isEqualTo(70.0);
        assertThat(result.avgDurationSeconds()).isEqualTo(120.5);
        assertThat(result.p50DurationSeconds()).isEqualTo(100.0);
        assertThat(result.p95DurationSeconds()).isEqualTo(300.0);
    }

    @Test
    void getOverview_emptyDatabase_returnsZeros() {
        Object[] row = new Object[] {0L, 0L, 0L, 0.0, 0.0, 0.0};
        Mockito.when(runRepo.getOverviewStats(Mockito.any(Instant.class))).thenReturn(row);

        AnalyticsOverviewResponse result = service.getOverview("30d");

        assertThat(result.totalRuns()).isZero();
        assertThat(result.completedRuns()).isZero();
        assertThat(result.failedRuns()).isZero();
        assertThat(result.successRate()).isEqualTo(0.0);
        assertThat(result.avgDurationSeconds()).isNull();
        assertThat(result.p50DurationSeconds()).isNull();
        assertThat(result.p95DurationSeconds()).isNull();
    }

    @Test
    void getOverview_nullRow_returnsZeros() {
        Mockito.when(runRepo.getOverviewStats(Mockito.any(Instant.class))).thenReturn(null);

        AnalyticsOverviewResponse result = service.getOverview("30d");

        assertThat(result.totalRuns()).isZero();
    }

    // --- getRunTrend tests ---

    @Test
    void getRunTrend_withData_returnsTrendPoints() {
        List<Object[]> rows = List.of(new Object[] {"2026-03-01", 5L, 3L, 1L}, new Object[] {"2026-03-02", 8L, 6L, 2L});
        Mockito.when(runRepo.getDailyRunTrend(Mockito.any(Instant.class))).thenReturn(rows);

        RunTrendResponse result = service.getRunTrend("30d");

        assertThat(result.points()).hasSize(2);
        assertThat(result.points().get(0).date()).isEqualTo("2026-03-01");
        assertThat(result.points().get(0).total()).isEqualTo(5);
        assertThat(result.points().get(1).completed()).isEqualTo(6);
    }

    @Test
    void getRunTrend_emptyData_returnsEmptyList() {
        Mockito.when(runRepo.getDailyRunTrend(Mockito.any(Instant.class))).thenReturn(List.of());

        RunTrendResponse result = service.getRunTrend("30d");

        assertThat(result.points()).isEmpty();
    }

    // --- getTemplateAnalytics tests ---

    @Test
    void getTemplateAnalytics_withData_computesSuccessRate() {
        List<Object[]> rows = Collections.singletonList(new Object[] {"My Template", 10L, 8L, 1L});
        Mockito.when(runRepo.getTemplateAnalytics(Mockito.any(Instant.class))).thenReturn(rows);

        TemplateAnalyticsResponse result = service.getTemplateAnalytics("30d");

        assertThat(result.templates()).hasSize(1);
        TemplateAnalytics t = result.templates().get(0);
        assertThat(t.templateName()).isEqualTo("My Template");
        assertThat(t.runCount()).isEqualTo(10);
        assertThat(t.successRate()).isEqualTo(80.0);
    }

    // --- getNodeAnalytics tests ---

    @Test
    void getNodeAnalytics_withData_computesSuccessRate() {
        List<Object[]> rows = Collections.singletonList(new Object[] {"ai_draft_spec", 20L, 18L, 2L});
        Mockito.when(execRepo.getNodeAnalytics(Mockito.any(Instant.class))).thenReturn(rows);

        NodeAnalyticsResponse result = service.getNodeAnalytics("30d");

        assertThat(result.nodes()).hasSize(1);
        NodeAnalytics n = result.nodes().get(0);
        assertThat(n.label()).isEqualTo("ai_draft_spec");
        assertThat(n.successRate()).isEqualTo(90.0);
    }

    // --- getBottlenecks tests ---

    @Test
    void getBottlenecks_withData_returnsSortedByDuration() {
        List<Object[]> rows = List.of(
                new Object[] {"slow_node", 600.123, 500.0, 900.0, 15L},
                new Object[] {"fast_node", 30.456, 25.0, 60.0, 40L});
        Mockito.when(execRepo.getBottleneckNodes(Mockito.any(Instant.class))).thenReturn(rows);

        BottleneckResponse result = service.getBottlenecks("7d");

        assertThat(result.bottlenecks()).hasSize(2);
        assertThat(result.bottlenecks().get(0).label()).isEqualTo("slow_node");
        assertThat(result.bottlenecks().get(0).avgDurationSeconds()).isEqualTo(600.12);
        assertThat(result.bottlenecks().get(1).sampleSize()).isEqualTo(40);
    }

    // --- roadmap (Task) tests ---

    @Test
    void getRoadmapStatusCounts_withData_sumsTotal() {
        List<Object[]> rows = List.of(new Object[] {"backlog", 4L}, new Object[] {"done", 2L});
        Mockito.when(taskRepo.getStatusCounts()).thenReturn(rows);

        RoadmapStatusCountsResponse result = service.getRoadmapStatusCounts();

        assertThat(result.total()).isEqualTo(6);
        assertThat(result.statuses()).hasSize(2);
        assertThat(result.statuses().get(0).status()).isEqualTo("backlog");
    }

    @Test
    void getRoadmapThroughput_withData_returnsPoints() {
        List<Object[]> rows = Collections.singletonList(new Object[] {"2026-03-01", 3L});
        Mockito.when(taskRepo.getThroughput(Mockito.any(Instant.class))).thenReturn(rows);

        RoadmapThroughputResponse result = service.getRoadmapThroughput("30d");

        assertThat(result.points()).hasSize(1);
        assertThat(result.points().get(0).date()).isEqualTo("2026-03-01");
        assertThat(result.points().get(0).count()).isEqualTo(3);
    }
}
