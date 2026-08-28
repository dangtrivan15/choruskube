package com.choruskube.core.reconciler;

import com.choruskube.core.service.PullRequestStateService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Unlike the other reconcilers in this package, this one makes outbound HTTP calls, so it is
 * gated by {@code choruskube.reconciler.pull-request-state.enabled} and switched off in the test
 * profile — every {@code @SpringBootTest} boots the full context, scheduling included.
 */
@Component
@ConditionalOnProperty(
        name = "choruskube.reconciler.pull-request-state.enabled",
        havingValue = "true",
        matchIfMissing = true)
public class PullRequestStateReconciler {

    private static final Logger log = LoggerFactory.getLogger(PullRequestStateReconciler.class);

    private final PullRequestStateService pullRequestStateService;
    private final int batchSize;

    public PullRequestStateReconciler(
            PullRequestStateService pullRequestStateService,
            @Value("${choruskube.reconciler.pull-request-state.batch-size:50}") int batchSize) {
        this.pullRequestStateService = pullRequestStateService;
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString = "${choruskube.reconciler.pull-request-state.interval:PT2M}")
    public void reconcile() {
        try {
            int merged = pullRequestStateService.refreshBatch(batchSize);
            if (merged > 0) {
                log.info("PullRequestStateReconciler observed {} newly merged pull request(s)", merged);
            }
        } catch (Exception e) {
            log.error("PullRequestStateReconciler tick failed: {}", e.getMessage(), e);
        }
    }
}
