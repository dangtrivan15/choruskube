package com.choruskube.core.service;

import com.choruskube.core.dto.TelemetryReportPayload;
import com.choruskube.core.repository.WorkflowRunRepository;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

/**
 * Builds and sends the anonymous telemetry report. {@link #buildPayload()} assembles the wire
 * contract (install id, trailing-7-day global run count, os/arch, app version); {@link #sendReport()}
 * POSTs it and swallows any failure so telemetry can never disrupt the app. See PRIVACY.md.
 */
@Service
public class TelemetryService {

    private static final Logger log = LoggerFactory.getLogger(TelemetryService.class);
    private static final int SCHEMA_VERSION = 1;
    private static final int RUN_COUNT_WINDOW_DAYS = 7;

    private final TelemetryInstallService installService;
    private final WorkflowRunRepository runRepository;
    private final String appVersion;
    private final String endpoint;
    private final RestClient restClient;

    public TelemetryService(
            TelemetryInstallService installService,
            WorkflowRunRepository runRepository,
            @Value("${telemetry.app-version:dev}") String appVersion,
            @Value("${telemetry.endpoint:https://api.choruskube.com/api/public/v1/telemetry}") String endpoint) {
        this.installService = installService;
        this.runRepository = runRepository;
        this.appVersion = appVersion;
        this.endpoint = endpoint;

        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(5));
        factory.setReadTimeout(Duration.ofSeconds(5));
        this.restClient = RestClient.builder().requestFactory(factory).build();
    }

    /** Assemble the wire-contract payload. Pure read — no network. */
    public TelemetryReportPayload buildPayload() {
        UUID installId = installService.getOrCreateInstallId();
        Instant since = Instant.now().minus(RUN_COUNT_WINDOW_DAYS, ChronoUnit.DAYS);
        long runCount = runRepository.countAllRunsSince(since);
        return new TelemetryReportPayload(
                SCHEMA_VERSION,
                installId,
                appVersion,
                System.getProperty("os.name"),
                System.getProperty("os.arch"),
                (int) runCount);
    }

    /**
     * Build the payload and POST it to the receiver. Any failure (network, non-2xx) is swallowed
     * and logged — telemetry must never disrupt the app or throw out of the scheduled tick.
     */
    public void sendReport() {
        try {
            TelemetryReportPayload payload = buildPayload();
            restClient
                    .post()
                    .uri(endpoint)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(payload)
                    .retrieve()
                    .toBodilessEntity();
            log.debug("Telemetry report sent (runCount={})", payload.runCount());
        } catch (Exception e) {
            log.debug("Telemetry report failed (swallowed): {}", e.getMessage());
        }
    }
}
