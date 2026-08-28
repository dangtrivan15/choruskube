package com.choruskube.core.reconciler;

import com.choruskube.core.service.TelemetryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Opt-out via {@code CHORUSKUBE_TELEMETRY=off} (relaxed-bound to property
 * {@code choruskube.telemetry}; default {@code on}). See PRIVACY.md.
 */
@Component
public class TelemetryReconciler {

    private static final Logger log = LoggerFactory.getLogger(TelemetryReconciler.class);

    private final TelemetryService telemetryService;
    private final String mode;

    public TelemetryReconciler(TelemetryService telemetryService, @Value("${choruskube.telemetry:on}") String mode) {
        this.telemetryService = telemetryService;
        this.mode = mode;
    }

    @Scheduled(
            fixedDelayString = "${telemetry.send-interval:PT168H}",
            initialDelayString = "${telemetry.send-initial-delay:PT2M}")
    public void tick() {
        if ("off".equalsIgnoreCase(mode)) {
            return;
        }
        try {
            telemetryService.sendReport();
        } catch (Exception e) {
            log.debug("TelemetryReconciler tick failed (swallowed): {}", e.getMessage());
        }
    }
}
