package com.choruskube.core.reconciler;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.choruskube.core.service.TelemetryService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Pure unit test for {@link TelemetryReconciler} opt-out gating — no Spring, no network. When the
 * telemetry mode is {@code off} the tick must NOT call the service; otherwise it calls it once.
 */
@ExtendWith(MockitoExtension.class)
class TelemetryReconcilerTest {

    @Mock
    private TelemetryService telemetryService;

    @Test
    void tick_whenModeOff_neverCallsService() {
        TelemetryReconciler reconciler = new TelemetryReconciler(telemetryService, "off");

        reconciler.tick();

        verify(telemetryService, never()).sendReport();
    }

    @Test
    void tick_whenModeOn_callsServiceOnce() {
        TelemetryReconciler reconciler = new TelemetryReconciler(telemetryService, "on");

        reconciler.tick();

        verify(telemetryService, times(1)).sendReport();
    }
}
