package com.choruskube.core.reconciler;

import com.choruskube.core.service.AutopilotService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Fires the Autopilot's tick on a timer. Every side effect of a pass — settling
 * runs, starting Tasks, publishing status — lives in {@link AutopilotService#tick()}; this class
 * only owns the schedule and the failure boundary.
 *
 * <p>Gated by {@code choruskube.autopilot.enabled} and switched off in the test profile — every
 * {@code @SpringBootTest} boots the full context, scheduling included, and an engaged Autopilot
 * would start real workflow runs during the unit suite.
 */
@Component
@ConditionalOnProperty(name = "choruskube.autopilot.enabled", havingValue = "true", matchIfMissing = true)
public class AutopilotReconciler {

    private static final Logger log = LoggerFactory.getLogger(AutopilotReconciler.class);

    private final AutopilotService autopilotService;

    public AutopilotReconciler(AutopilotService autopilotService) {
        this.autopilotService = autopilotService;
    }

    @Scheduled(fixedDelayString = "${choruskube.autopilot.interval:PT30S}")
    public void tick() {
        try {
            autopilotService.tick();
        } catch (Exception e) {
            log.error("AutopilotReconciler tick failed: {}", e.getMessage(), e);
        }
    }
}
