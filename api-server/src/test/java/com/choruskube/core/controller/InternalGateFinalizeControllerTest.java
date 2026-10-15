package com.choruskube.core.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.choruskube.core.dto.GateFinalizeDtos.CompareResultItem;
import com.choruskube.core.dto.GateFinalizeDtos.CompleteRequest;
import com.choruskube.core.dto.GateFinalizeDtos.HeldClaim;
import com.choruskube.core.dto.GateFinalizeDtos.HoldRequest;
import com.choruskube.core.dto.GateFinalizeDtos.IssueResultItem;
import com.choruskube.core.dto.GateFinalizeDtos.MaterializeRequest;
import com.choruskube.core.dto.GateFinalizeDtos.MaterializeResponse;
import com.choruskube.core.dto.GateFinalizeDtos.PlanResponse;
import com.choruskube.core.dto.GateFinalizeDtos.PrResultItem;
import com.choruskube.core.dto.GateFinalizeDtos.RefuseRequest;
import com.choruskube.core.dto.GateFinalizeDtos.StepResult;
import com.choruskube.core.exception.NotFoundException;
import com.choruskube.core.service.GateFinalizeService;
import com.choruskube.core.service.RunScopeBinder;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

/** Every step binds the run's scope, and only after the gate execution is checked against the run. */
class InternalGateFinalizeControllerTest {

    private final GateFinalizeService service = mock(GateFinalizeService.class);
    private final List<UUID> boundRuns = new ArrayList<>();
    private boolean inScope;
    private final UUID runId = UUID.randomUUID();
    private final UUID execId = UUID.randomUUID();
    private final UUID approvalId = UUID.randomUUID();
    private InternalGateFinalizeController controller;

    @BeforeEach
    void setUp() {
        RunScopeBinder binder = (run, work) -> {
            boundRuns.add(run);
            inScope = true;
            try {
                work.run();
            } finally {
                inScope = false;
            }
        };
        controller = new InternalGateFinalizeController(service, binder);
    }

    @Test
    void everyStep_runsInTheRunsScope_afterTheGateIsChecked() {
        StepResult held = new StepResult("held", "r", "{}", null);
        when(service.claimHeld(runId, execId)).thenAnswer(inv -> scoped(new HeldClaim(approvalId)));
        when(service.plan(runId, execId, approvalId))
                .thenAnswer(inv -> scoped(new PlanResponse("proceed", null, List.of(), List.of(), null, true)));
        List<CompareResultItem> compares = List.of(new CompareResultItem(UUID.randomUUID(), 1, false));
        when(service.hold(runId, execId, approvalId, compares)).thenAnswer(inv -> scoped(held));
        List<PrResultItem> merges = List.of(new PrResultItem(UUID.randomUUID(), "MERGED", Instant.now(), "m"));
        MaterializeResponse materialized = new MaterializeResponse(Instant.now(), List.of());
        when(service.materialize(runId, execId, approvalId, merges)).thenAnswer(inv -> scoped(materialized));
        List<IssueResultItem> issues =
                List.of(new IssueResultItem(UUID.randomUUID(), 1, "https://github.com/o/r/issues/1", null));
        when(service.complete(runId, execId, approvalId, merges, issues)).thenAnswer(inv -> scoped(held));

        assertThat(controller.held(runId, execId).approvalId()).isEqualTo(approvalId);
        assertThat(controller.plan(runId, execId, approvalId).outcome()).isEqualTo("proceed");
        assertThat(controller.hold(runId, execId, approvalId, new HoldRequest(compares)))
                .isEqualTo(held);
        assertThat(controller.materialize(runId, execId, approvalId, new MaterializeRequest(merges)))
                .isEqualTo(materialized);
        assertThat(controller.complete(runId, execId, approvalId, new CompleteRequest(merges, issues)))
                .isEqualTo(held);
        controller.refuse(runId, execId, approvalId, new RefuseRequest("no", null));

        assertThat(boundRuns).hasSize(6).containsOnly(runId);
        InOrder order = inOrder(service);
        order.verify(service).requireGate(runId, execId);
        order.verify(service).claimHeld(runId, execId);
        order.verify(service).requireGate(runId, execId);
        order.verify(service).plan(runId, execId, approvalId);
        order.verify(service).requireGate(runId, execId);
        order.verify(service).hold(runId, execId, approvalId, compares);
        order.verify(service).requireGate(runId, execId);
        order.verify(service).materialize(runId, execId, approvalId, merges);
        order.verify(service).requireGate(runId, execId);
        order.verify(service).complete(runId, execId, approvalId, merges, issues);
        order.verify(service).requireGate(runId, execId);
        order.verify(service).refuse(runId, execId, approvalId, "no", List.of());
    }

    @Test
    void missingBodies_readAsEmpty() {
        controller.hold(runId, execId, approvalId, null);
        controller.materialize(runId, execId, approvalId, null);
        controller.complete(runId, execId, approvalId, null);
        controller.refuse(runId, execId, approvalId, null);

        InOrder order = inOrder(service);
        order.verify(service).hold(runId, execId, approvalId, List.of());
        order.verify(service).materialize(runId, execId, approvalId, List.of());
        order.verify(service).complete(runId, execId, approvalId, List.of(), List.of());
        order.verify(service).refuse(runId, execId, approvalId, null, List.of());
    }

    @Test
    void complete_missingIssues_readsAsEmpty() {
        List<PrResultItem> merges = List.of(new PrResultItem(UUID.randomUUID(), "MERGED", Instant.now(), "m"));

        controller.complete(runId, execId, approvalId, new CompleteRequest(merges, null));

        verify(service).complete(runId, execId, approvalId, merges, List.of());
    }

    @Test
    void anExecutionOfAnotherRun_isRejectedBeforeAnyScopeIsBound() {
        doThrow(new NotFoundException("Node execution not found: " + execId))
                .when(service)
                .requireGate(runId, execId);

        assertThatThrownBy(() -> controller.plan(runId, execId, approvalId)).isInstanceOf(NotFoundException.class);

        assertThat(boundRuns).isEmpty();
        verify(service).requireGate(any(), any());
        verifyNoMoreInteractions(service);
    }

    @Test
    void anExecutionThatIsNotAnAsyncGate_isRejectedBeforeAnyScopeIsBound() {
        doThrow(new NotFoundException("Node execution not found: " + execId))
                .when(service)
                .requireGate(runId, execId);

        assertThatThrownBy(() -> controller.materialize(runId, execId, approvalId, null))
                .isInstanceOf(NotFoundException.class);

        assertThat(boundRuns).isEmpty();
    }

    private <T> T scoped(T value) {
        assertThat(inScope).as("called inside the run's scope").isTrue();
        return value;
    }
}
