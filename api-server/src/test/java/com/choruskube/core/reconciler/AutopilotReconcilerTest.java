package com.choruskube.core.reconciler;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.choruskube.core.service.AutopilotService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Pure unit test for {@link AutopilotReconciler} — no Spring, no scheduling. Verifies the tick
 * delegates to {@link AutopilotService#tick()} and that the failure boundary swallows exceptions
 * rather than letting them escape.
 */
@ExtendWith(MockitoExtension.class)
class AutopilotReconcilerTest {

    @Mock
    private AutopilotService autopilotService;

    @Test
    void tick_delegatesToServiceOnce() {
        AutopilotReconciler reconciler = new AutopilotReconciler(autopilotService);

        reconciler.tick();

        verify(autopilotService, times(1)).tick();
    }

    @Test
    void tick_whenServiceThrows_doesNotEscape() {
        AutopilotReconciler reconciler = new AutopilotReconciler(autopilotService);
        doThrow(new RuntimeException("boom")).when(autopilotService).tick();

        reconciler.tick();

        verify(autopilotService, times(1)).tick();
    }
}
