package com.choruskube.core.controller;

import com.choruskube.core.dto.GateFinalizeDtos.CompleteRequest;
import com.choruskube.core.dto.GateFinalizeDtos.HeldClaim;
import com.choruskube.core.dto.GateFinalizeDtos.HoldRequest;
import com.choruskube.core.dto.GateFinalizeDtos.MaterializeRequest;
import com.choruskube.core.dto.GateFinalizeDtos.MaterializeResponse;
import com.choruskube.core.dto.GateFinalizeDtos.PlanResponse;
import com.choruskube.core.dto.GateFinalizeDtos.RefuseRequest;
import com.choruskube.core.dto.GateFinalizeDtos.StepResult;
import com.choruskube.core.service.GateFinalizeService;
import com.choruskube.core.service.RunScopeBinder;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The steps a run's workflow drives to finish an approval on an asynchronous gate. Called only by
 * the orchestrator; a gate execution carries no job secret, so an agent pod's JOB_SECRET cannot
 * reach these routes, and every step refuses an execution that is not an async gate of the run.
 * Each runs in the run's tenant scope, since no request carries one.
 */
@RestController
@RequestMapping("/internal/runs/{runId}/node-executions/{nodeExecId}/gate-finalize")
public class InternalGateFinalizeController {

    private final GateFinalizeService service;
    private final RunScopeBinder runScopeBinder;

    public InternalGateFinalizeController(GateFinalizeService service, RunScopeBinder runScopeBinder) {
        this.service = service;
        this.runScopeBinder = runScopeBinder;
    }

    @PostMapping("/held")
    public HeldClaim held(@PathVariable UUID runId, @PathVariable("nodeExecId") UUID execId) {
        return inScope(runId, execId, () -> service.claimHeld(runId, execId));
    }

    @PostMapping("/{approvalId}/plan")
    public PlanResponse plan(
            @PathVariable UUID runId, @PathVariable("nodeExecId") UUID execId, @PathVariable UUID approvalId) {
        return inScope(runId, execId, () -> service.plan(runId, execId, approvalId));
    }

    @PostMapping("/{approvalId}/hold")
    public StepResult hold(
            @PathVariable UUID runId,
            @PathVariable("nodeExecId") UUID execId,
            @PathVariable UUID approvalId,
            @RequestBody(required = false) HoldRequest request) {
        return inScope(
                runId,
                execId,
                () -> service.hold(runId, execId, approvalId, orEmpty(request == null ? null : request.compares())));
    }

    @PostMapping("/{approvalId}/materialize")
    public MaterializeResponse materialize(
            @PathVariable UUID runId,
            @PathVariable("nodeExecId") UUID execId,
            @PathVariable UUID approvalId,
            @RequestBody(required = false) MaterializeRequest request) {
        return inScope(
                runId,
                execId,
                () -> service.materialize(
                        runId, execId, approvalId, orEmpty(request == null ? null : request.merges())));
    }

    @PostMapping("/{approvalId}/complete")
    public StepResult complete(
            @PathVariable UUID runId,
            @PathVariable("nodeExecId") UUID execId,
            @PathVariable UUID approvalId,
            @RequestBody(required = false) CompleteRequest request) {
        return inScope(
                runId,
                execId,
                () -> service.complete(
                        runId,
                        execId,
                        approvalId,
                        orEmpty(request == null ? null : request.merges()),
                        orEmpty(request == null ? null : request.issues())));
    }

    @PostMapping("/{approvalId}/refuse")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void refuse(
            @PathVariable UUID runId,
            @PathVariable("nodeExecId") UUID execId,
            @PathVariable UUID approvalId,
            @RequestBody(required = false) RefuseRequest request) {
        inScope(runId, execId, () -> {
            service.refuse(
                    runId,
                    execId,
                    approvalId,
                    request == null ? null : request.reason(),
                    orEmpty(request == null ? null : request.merges()));
            return null;
        });
    }

    /** The execution is checked against the run before its scope is bound, so a foreign one binds nothing. */
    private <T> T inScope(UUID runId, UUID execId, Supplier<T> work) {
        service.requireGate(runId, execId);
        Object[] holder = new Object[1];
        runScopeBinder.runInScopeOf(runId, () -> holder[0] = work.get());
        @SuppressWarnings("unchecked")
        T result = (T) holder[0];
        return result;
    }

    private static <T> List<T> orEmpty(List<T> list) {
        return list == null ? List.of() : list;
    }
}
