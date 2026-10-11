package com.choruskube.core.reconciler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.choruskube.core.model.GateStamp;
import com.choruskube.core.service.GateRecheckService;
import com.choruskube.core.service.RunScopeBinder;
import com.choruskube.core.service.RunService;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class GateStampReconcilerTest {

    @Mock
    private GateRecheckService gateRecheck;

    @Mock
    private RunService runService;

    private final List<UUID> boundRuns = new ArrayList<>();
    private final RunScopeBinder recordingBinder = (runId, work) -> {
        boundRuns.add(runId);
        work.run();
    };
    private GateStampReconciler reconciler;

    @BeforeEach
    void setUp() {
        reconciler = new GateStampReconciler(gateRecheck, runService, recordingBinder);
    }

    @Test
    void reappliesEveryReadyStampInsideItsRunsScope() {
        GateStamp a = stamp();
        GateStamp b = stamp();
        when(gateRecheck.findReadyToReapply()).thenReturn(List.of(a, b));

        reconciler.reconcile();

        assertThat(boundRuns).containsExactly(a.getWorkflowRunId(), b.getWorkflowRunId());
        verify(runService).applyHeldApproval(a.getId());
        verify(runService).applyHeldApproval(b.getId());
    }

    @Test
    void oneFailureDoesNotStopTheNext() {
        GateStamp a = stamp();
        GateStamp b = stamp();
        when(gateRecheck.findReadyToReapply()).thenReturn(List.of(a, b));
        doThrow(new IllegalStateException("boom")).when(runService).applyHeldApproval(a.getId());

        reconciler.reconcile();

        verify(runService).applyHeldApproval(b.getId());
    }

    @Test
    void voidsEndedRunsBeforeReapplying() {
        when(gateRecheck.findReadyToReapply()).thenReturn(List.of());

        reconciler.reconcile();

        InOrder order = inOrder(gateRecheck);
        order.verify(gateRecheck).voidPendingOnEndedRuns();
        order.verify(gateRecheck).findReadyToReapply();
    }

    private static GateStamp stamp() {
        GateStamp stamp = new GateStamp();
        stamp.setId(UUID.randomUUID());
        stamp.setWorkflowRunId(UUID.randomUUID());
        return stamp;
    }
}
