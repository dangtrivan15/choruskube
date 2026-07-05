package com.choruskube.core.service;

import com.choruskube.core.dto.*;
import com.choruskube.core.repository.FeatureProposalRepository;
import com.choruskube.core.repository.NodeExecutionRepository;
import com.choruskube.core.repository.WorkflowRunRepository;
import java.time.Instant;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(name = "auth.enabled", havingValue = "false", matchIfMissing = true)
public class GlobalAnalyticsService implements AnalyticsService {

    private final WorkflowRunRepository runRepo;
    private final NodeExecutionRepository execRepo;
    private final FeatureProposalRepository proposalRepo;

    public GlobalAnalyticsService(
            WorkflowRunRepository runRepo, NodeExecutionRepository execRepo, FeatureProposalRepository proposalRepo) {
        this.runRepo = runRepo;
        this.execRepo = execRepo;
        this.proposalRepo = proposalRepo;
    }

    @Override
    public AnalyticsOverviewResponse getOverview(String period) {
        Instant since = AnalyticsResultMapper.parsePeriod(period);
        Object[] row = runRepo.getOverviewStats(since);
        return AnalyticsResultMapper.toOverview(row);
    }

    @Override
    public RunTrendResponse getRunTrend(String period) {
        Instant since = AnalyticsResultMapper.parsePeriod(period);
        List<Object[]> rows = runRepo.getDailyRunTrend(since);
        return AnalyticsResultMapper.toRunTrend(rows);
    }

    @Override
    public TemplateAnalyticsResponse getTemplateAnalytics(String period) {
        Instant since = AnalyticsResultMapper.parsePeriod(period);
        List<Object[]> rows = runRepo.getTemplateAnalytics(since);
        return AnalyticsResultMapper.toTemplateAnalytics(rows);
    }

    @Override
    public NodeAnalyticsResponse getNodeAnalytics(String period) {
        Instant since = AnalyticsResultMapper.parsePeriod(period);
        List<Object[]> rows = execRepo.getNodeAnalytics(since);
        return AnalyticsResultMapper.toNodeAnalytics(rows);
    }

    @Override
    public BottleneckResponse getBottlenecks(String period) {
        Instant since = AnalyticsResultMapper.parsePeriod(period);
        List<Object[]> rows = execRepo.getBottleneckNodes(since);
        return AnalyticsResultMapper.toBottlenecks(rows);
    }

    @Override
    public ProposalStatusCountsResponse getProposalStatusCounts() {
        List<Object[]> rows = proposalRepo.getStatusCounts();
        return AnalyticsResultMapper.toProposalStatusCounts(rows);
    }

    @Override
    public ProposalThroughputResponse getProposalThroughput(String period) {
        Instant since = AnalyticsResultMapper.parsePeriod(period);
        List<Object[]> rows = proposalRepo.getThroughput(since);
        return AnalyticsResultMapper.toProposalThroughput(rows);
    }
}
