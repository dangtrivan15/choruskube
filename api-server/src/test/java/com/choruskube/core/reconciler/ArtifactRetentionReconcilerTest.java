package com.choruskube.core.reconciler;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.choruskube.core.service.ArtifactRetentionService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Pure unit test for {@link ArtifactRetentionReconciler} — no Spring, no scheduling. The tick calls
 * both of {@link ArtifactRetentionService}'s passes, and a failure in either must not stop the other
 * from running or escape the tick.
 */
@ExtendWith(MockitoExtension.class)
class ArtifactRetentionReconcilerTest {

    @Mock
    private ArtifactRetentionService retention;

    @Test
    void reconcile_callsBothPassesOnce() {
        ArtifactRetentionReconciler reconciler = new ArtifactRetentionReconciler(retention);

        reconciler.reconcile();

        verify(retention, times(1)).expireDue();
        verify(retention, times(1)).deleteAbandonedStaging();
    }

    @Test
    void reconcile_whenExpireDueThrows_stillRunsTheStagingSweep() {
        ArtifactRetentionReconciler reconciler = new ArtifactRetentionReconciler(retention);
        doThrow(new RuntimeException("boom")).when(retention).expireDue();

        reconciler.reconcile();

        verify(retention, times(1)).deleteAbandonedStaging();
    }

    @Test
    void reconcile_whenStagingSweepThrows_stillRanTheRunExpiryPass_andDoesNotEscape() {
        ArtifactRetentionReconciler reconciler = new ArtifactRetentionReconciler(retention);
        doThrow(new RuntimeException("boom")).when(retention).deleteAbandonedStaging();

        reconciler.reconcile();

        verify(retention, times(1)).expireDue();
    }
}
