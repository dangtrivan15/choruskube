package com.choruskube.core.reconciler;

import com.choruskube.core.service.StorySpecLifecycleService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Records approved Story Design runs as spec versions, then moves Story conflicts on. Polling rather
 * than hooking the gate's finalize paths also covers a crash between completion and the write; both
 * steps are idempotent, so a concurrent pass or replica repeats them harmlessly.
 */
@Component
@ConditionalOnProperty(name = "choruskube.story-spec.enabled", havingValue = "true", matchIfMissing = true)
public class StorySpecReconciler {

    private static final Logger log = LoggerFactory.getLogger(StorySpecReconciler.class);

    private final StorySpecLifecycleService lifecycle;

    public StorySpecReconciler(StorySpecLifecycleService lifecycle) {
        this.lifecycle = lifecycle;
    }

    /** Records first, so a revision that just completed releases its conflicts in the same pass. */
    @Scheduled(fixedDelayString = "${choruskube.story-spec.interval:PT10S}")
    public void reconcile() {
        try {
            int recorded = lifecycle.recordCompletedDesigns();
            if (recorded > 0) {
                log.info("StorySpecReconciler recorded {} approved Story spec version(s)", recorded);
            }
        } catch (Exception e) {
            log.error("StorySpecReconciler could not record approved Story specs: {}", e.getMessage(), e);
        }
        try {
            int processed = lifecycle.processConflicts();
            if (processed > 0) {
                log.info("StorySpecReconciler moved {} Story conflict(s) on", processed);
            }
        } catch (Exception e) {
            log.error("StorySpecReconciler could not process Story conflicts: {}", e.getMessage(), e);
        }
    }
}
