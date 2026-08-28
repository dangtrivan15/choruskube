package com.choruskube.core.reconciler;

import com.choruskube.core.service.GitRepoService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Runs concurrently-safe with the afterCommit cleanup in {@code GitRepoService.delete}:
 * both paths share {@code GitRepoService.cleanupAndHardDelete(UUID)}, which is idempotent
 * (DB DELETE uses a WHERE clause scoped to tombstoned rows). Whichever commits the DELETE
 * first wins; the loser's query affects zero rows.
 */
@Component
public class GitRepoReconciler {

    private static final Logger log = LoggerFactory.getLogger(GitRepoReconciler.class);

    private final GitRepoService gitRepoService;
    private final int batchSize;

    public GitRepoReconciler(
            GitRepoService gitRepoService, @Value("${choruskube.reconciler.git-repo.batch-size:100}") int batchSize) {
        this.gitRepoService = gitRepoService;
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString = "${choruskube.reconciler.git-repo.interval:PT1M}")
    public void reconcile() {
        try {
            int cleaned = gitRepoService.reconcileTombstonedBatch(batchSize);
            if (cleaned > 0) {
                log.info("GitRepoReconciler cleaned {} tombstoned row(s)", cleaned);
            }
        } catch (Exception e) {
            log.error("GitRepoReconciler tick failed: {}", e.getMessage(), e);
        }
    }
}
