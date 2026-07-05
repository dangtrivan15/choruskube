package com.choruskube.core.service;

import com.choruskube.core.repository.GitRepoRepository;
import com.choruskube.core.repository.WorkflowRunRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.springframework.stereotype.Component;

/**
 * Default {@link MetricsAggregator} implementation. Reads the public landing-page
 * metrics from {@link WorkflowRunRepository} and {@link GitRepoRepository} over a
 * fixed 90-day window for the rate / median.
 */
@Component
public class MetricsAggregatorImpl implements MetricsAggregator {

    private static final int WINDOW_DAYS = 90;

    private final WorkflowRunRepository workflowRunRepository;
    private final GitRepoRepository gitRepoRepository;

    public MetricsAggregatorImpl(WorkflowRunRepository workflowRunRepository, GitRepoRepository gitRepoRepository) {
        this.workflowRunRepository = workflowRunRepository;
        this.gitRepoRepository = gitRepoRepository;
    }

    @Override
    public AggregateMetrics aggregate() {
        long totalRuns = workflowRunRepository.countAllRuns();

        Instant since = Instant.now().minus(WINDOW_DAYS, ChronoUnit.DAYS);

        Object[] successStats = workflowRunRepository.getGlobalSuccessRateStats(since);
        Double successRate = computeSuccessRate(successStats);

        // gitRepoRepository.count() leverages JPA JOINED inheritance: the generated
        // SQL joins software_project + applies discriminator='git_repo' + applies
        // @SQLRestriction(deleted_at IS NULL) automatically. Verified against
        // GitRepo.java + SoftwareProject.java.
        long reposOrchestrated = gitRepoRepository.count();

        Double rawMedian = workflowRunRepository.getGlobalMedianRunSeconds(since);
        Long medianRunSeconds = rawMedian == null ? null : Math.round(rawMedian);

        return new AggregateMetrics(totalRuns, successRate, reposOrchestrated, medianRunSeconds);
    }

    /**
     * Spring Data wraps single-row aggregate native queries in {@code Object[]} where the
     * first element is itself the row {@code Object[]} of column values. We unwrap here
     * defensively to handle either shape; some Spring/Hibernate versions return the row
     * directly. Returns null when there are no terminal runs in the window.
     */
    static Double computeSuccessRate(Object[] row) {
        if (row == null || row.length == 0) {
            return null;
        }
        Object[] cols;
        if (row[0] instanceof Object[] inner) {
            cols = inner;
        } else {
            cols = row;
        }
        if (cols.length < 2 || cols[0] == null || cols[1] == null) {
            return null;
        }
        long completed = ((Number) cols[0]).longValue();
        long terminal = ((Number) cols[1]).longValue();
        if (terminal == 0L) {
            return null;
        }
        return BigDecimal.valueOf(completed)
                .divide(BigDecimal.valueOf(terminal), 4, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100))
                .setScale(1, RoundingMode.HALF_UP)
                .doubleValue();
    }
}
