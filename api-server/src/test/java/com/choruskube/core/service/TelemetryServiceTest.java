package com.choruskube.core.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.choruskube.core.dto.TelemetryReportPayload;
import com.choruskube.core.repository.WorkflowRunRepository;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Pure unit test for {@link TelemetryService#buildPayload()} with mocked collaborators —
 * no Spring, no DB, no network. Asserts the payload matches the wire contract (schemaVersion,
 * installId, appVersion, os/arch from system props, runCount from the repo query).
 */
@ExtendWith(MockitoExtension.class)
class TelemetryServiceTest {

    @Mock
    private TelemetryInstallService installService;

    @Mock
    private WorkflowRunRepository runRepository;

    @Test
    void buildPayload_assemblesWireContractFromCollaborators() {
        UUID installId = UUID.randomUUID();
        when(installService.getOrCreateInstallId()).thenReturn(installId);
        when(runRepository.countAllRunsSince(org.mockito.ArgumentMatchers.any(Instant.class)))
                .thenReturn(12L);

        TelemetryService service =
                new TelemetryService(installService, runRepository, "1.4.0", "https://example.test/telemetry");

        TelemetryReportPayload payload = service.buildPayload();

        assertThat(payload.schemaVersion()).isEqualTo(1);
        assertThat(payload.installId()).isEqualTo(installId);
        assertThat(payload.appVersion()).isEqualTo("1.4.0");
        assertThat(payload.os()).isEqualTo(System.getProperty("os.name"));
        assertThat(payload.arch()).isEqualTo(System.getProperty("os.arch"));
        assertThat(payload.runCount()).isEqualTo(12);
    }
}
