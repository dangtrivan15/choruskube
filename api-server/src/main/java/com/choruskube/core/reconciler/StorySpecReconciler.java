package com.choruskube.core.reconciler;

import com.choruskube.core.service.StorySpecLifecycleService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Records approved Story Design runs as spec versions. Polling the run's {@code completed} status,
 * rather than hooking the gate's finalize paths, also covers a crash between completion and the
 * write; recording is idempotent on the run, so a concurrent pass or replica records it once.
 */
@Component
@ConditionalOnProperty(name = "choruskube.story-spec.enabled", havingValue = "true", matchIfMissing = true)
public class StorySpecReconciler {

    private static final Logger log = LoggerFactory.getLogger(StorySpecReconciler.class);

    private final StorySpecLifecycleService lifecycle;

    public StorySpecReconciler(StorySpecLifecycleService lifecycle) {
        this.lifecycle = lifecycle;
    }

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
    }
}
